package com.nexum.agent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.nexum.coordination.AgentRunRepository;
import com.nexum.event.EventLog;
import com.nexum.event.EventType;
import com.nexum.support.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs agent workers as tasks in this JVM.
 *
 * <p>The run row is created here, before the worker starts, so a caller gets a
 * run id it can immediately act on - the kill endpoint in particular, which
 * would otherwise be racing the worker's own startup.
 *
 * <p>Handles are kept only for the ability to kill a worker. They are not the
 * source of truth for anything: if this process died, every fact about these
 * runs would still be in CockroachDB, and their leases would lapse and be
 * reaped exactly as if they had been killed individually. The map is a
 * convenience for the demo, not a registry the system depends on.
 */
@Component
public class LocalThreadPoolDispatcher implements AgentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalThreadPoolDispatcher.class);

    private final AgentLoop loop;
    private final AgentRunRepository runs;
    private final EventLog events;
    private final ExecutorService workers;
    private final int maxConcurrent;

    private final Map<UUID, RunHandle> handles = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();

    public LocalThreadPoolDispatcher(AgentLoop loop, AgentRunRepository runs, EventLog events,
            @Qualifier("agentWorkerExecutor") ExecutorService workers,
            @Value("${nexum.agent.max-concurrent:3}") int maxConcurrent) {
        this.loop = loop;
        this.runs = runs;
        this.events = events;
        this.workers = workers;
        this.maxConcurrent = maxConcurrent;
    }

    @Override
    public Optional<UUID> dispatch(UUID agentId, UUID goalId) {
        if (this.active.get() >= this.maxConcurrent) {
            log.info("Refusing dispatch for agent {}: {} runs already active", agentId,
                    this.active.get());
            return Optional.empty();
        }

        UUID runId = this.runs.start(agentId, goalId);
        RunHandle handle = new RunHandle(runId);
        this.handles.put(runId, handle);
        this.active.incrementAndGet();

        this.events.append(goalId, EventType.RUN_STARTED,
                Json.object("runId", runId, "agentId", agentId));
        this.events.append(goalId, EventType.AGENT_REJOINED_GOAL,
                Json.object("runId", runId, "agentId", agentId));

        Future<?> worker = this.workers.submit(() -> {
            try {
                AgentLoop.Outcome outcome = this.loop.run(agentId, goalId, runId, handle);
                log.info("Run {} for agent {} finished: {}", runId, agentId, outcome);
            }
            catch (RuntimeException ex) {
                log.error("Run {} terminated unexpectedly", runId, ex);
            }
            finally {
                this.active.decrementAndGet();
                this.handles.remove(runId);
            }
        });

        handle.attachWorker(worker);
        return Optional.of(runId);
    }

    @Override
    public boolean kill(UUID runId) {
        RunHandle handle = this.handles.get(runId);
        if (handle == null) {
            return false;
        }

        handle.kill();
        // Backdates the run's own heartbeat column. The run row now agrees with
        // reality; the TASK is still untouched, and stays that way until the
        // reaper works out for itself that the lease has lapsed.
        this.runs.stopHeartbeat(runId);
        log.info("Killed run {}. The task is untouched - the reaper has to notice.", runId);
        return true;
    }

    @Override
    public int activeRuns() {
        return this.active.get();
    }
}
