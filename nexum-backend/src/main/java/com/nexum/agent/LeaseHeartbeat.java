package com.nexum.agent;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.nexum.coordination.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a task's lease alive for as long as its worker is alive.
 *
 * <p>Runs on its own schedule rather than once per loop iteration, and that is
 * the whole point. A step can take as long as the model takes; if the lease were
 * only extended between steps it would have to be longer than the slowest
 * imaginable model call, which would make genuine failure take that long to
 * detect. Separating the two lets the lease be short - failure is noticed in
 * seconds - while a worker legitimately blocked in a 30-second call is not
 * mistaken for a dead one.
 *
 * <p>That is also what makes the demo kill honest. Stopping this heartbeat is
 * indistinguishable, from the database's point of view, from the process having
 * been killed: nothing announces a failure, the lease simply stops being
 * renewed, and the reaper works it out on its own.
 */
class LeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeat.class);

    private final UUID taskId;
    private final UUID runId;
    private final ScheduledFuture<?> scheduled;

    /**
     * True once the database has told us this run no longer holds the lease.
     * Volatile because the loop thread reads what the scheduler thread writes.
     */
    private volatile boolean lost;

    private LeaseHeartbeat(UUID taskId, UUID runId, ScheduledExecutorService scheduler,
            TaskRepository tasks, int leaseSeconds, long intervalMillis) {

        this.taskId = taskId;
        this.runId = runId;
        this.scheduled = scheduler.scheduleAtFixedRate(() -> beat(tasks, leaseSeconds),
                0L, intervalMillis, TimeUnit.MILLISECONDS);
    }

    static LeaseHeartbeat start(UUID taskId, UUID runId, ScheduledExecutorService scheduler,
            TaskRepository tasks, int leaseSeconds, long intervalMillis) {
        return new LeaseHeartbeat(taskId, runId, scheduler, tasks, leaseSeconds, intervalMillis);
    }

    private void beat(TaskRepository tasks, int leaseSeconds) {
        try {
            if (!tasks.heartbeat(this.taskId, this.runId, leaseSeconds)) {
                // Someone else owns this task now - almost certainly the reaper
                // decided we were dead. Stop renewing immediately: continuing to
                // work would mean two owners, which is Invariant 1 broken from
                // the direction people forget to check.
                this.lost = true;
                stop();
                log.info("Run {} lost the lease on task {}; standing down", this.runId,
                        this.taskId);
            }
        }
        catch (RuntimeException ex) {
            // A failed beat is not proof the lease is gone - the database may
            // simply be briefly unreachable. Let the lease expire naturally if
            // this keeps happening rather than abandoning work on one blip.
            log.warn("Heartbeat failed for run {}", this.runId, ex);
        }
    }

    boolean isLost() {
        return this.lost;
    }

    /** Stops renewing. The lease now runs out on its own. */
    void stop() {
        this.scheduled.cancel(false);
    }
}
