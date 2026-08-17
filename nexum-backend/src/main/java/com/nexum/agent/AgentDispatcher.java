package com.nexum.agent;

import java.util.Optional;
import java.util.UUID;

/**
 * Starts agent workers, wherever they happen to run.
 *
 * <p>The seam exists because <em>where</em> a worker executes is not supposed to
 * matter to anything else in Nexum. {@link LocalThreadPoolDispatcher} runs them
 * in this JVM; a Lambda-backed implementation would run them somewhere with no
 * shared memory, no shared heap, and no way to be told to shut down cleanly -
 * and nothing above this interface would change, because the coordination
 * substrate is the database rather than the process.
 *
 * <p>That is the thesis stated as an interface: worker lifetime is independent
 * of cognition lifetime. A lease expiring on a thread you killed proves it just
 * as well as a Lambda timing out, which is why the local implementation is
 * enough for the demo and the Lambda one stays optional.
 */
public interface AgentDispatcher {

    /**
     * Starts a run for this agent on this goal and returns its run id.
     *
     * <p>Returns empty when the concurrency cap is reached. The cap is not
     * ceremony: several agents each holding an LLM call, a lease, and a database
     * connection is exactly how a laptop demo falls over.
     */
    Optional<UUID> dispatch(UUID agentId, UUID goalId);

    /**
     * Kills a run the way a crash would: the worker and its heartbeat stop, and
     * the task is not touched. Returns false if the run is unknown here.
     */
    boolean kill(UUID runId);

    int activeRuns();
}
