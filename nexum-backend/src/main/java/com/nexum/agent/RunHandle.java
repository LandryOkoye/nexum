package com.nexum.agent;

import java.util.UUID;
import java.util.concurrent.Future;

/**
 * A live worker, and the means to kill it.
 *
 * <p>Killing here means what it would mean if the process had crashed: the
 * worker stops and its heartbeat stops. It deliberately does <strong>not</strong>
 * touch the task. Nothing marks the task failed, orphaned, or anything else -
 * the row is left exactly as a crashed worker would have left it, still RUNNING
 * and still leased to a run that will never renew it again.
 *
 * <p>Everything after that is the system's own work: the lease lapses, the
 * reaper notices, and recovery follows. That gap is the difference between a
 * demo that writes a status and a system that detects failure, and it is why
 * this class is so careful about what it does not do.
 */
public class RunHandle {

    private final UUID runId;

    private volatile Future<?> worker;

    private volatile LeaseHeartbeat heartbeat;

    private volatile boolean killed;

    RunHandle(UUID runId) {
        this.runId = runId;
    }

    public UUID runId() {
        return this.runId;
    }

    void attachWorker(Future<?> worker) {
        this.worker = worker;
        if (this.killed) {
            // Killed between submission and attachment - honour it now rather
            // than leaving an orphaned worker running.
            worker.cancel(true);
        }
    }

    void attachHeartbeat(LeaseHeartbeat heartbeat) {
        this.heartbeat = heartbeat;
        if (this.killed) {
            heartbeat.stop();
        }
    }

    /**
     * Simulates worker death.
     *
     * <p>The heartbeat is stopped first and deliberately: it is the only thing
     * the database can see, so stopping it is what starts the clock on
     * detection. Cancelling the worker afterwards may or may not interrupt it
     * promptly - a thread inside an HTTP call to the model does not necessarily
     * stop on request - but that does not matter, because a worker that keeps
     * running without a heartbeat will discover it has lost the lease and stand
     * down on its own.
     */
    public void kill() {
        this.killed = true;
        LeaseHeartbeat beating = this.heartbeat;
        if (beating != null) {
            beating.stop();
        }
        Future<?> running = this.worker;
        if (running != null) {
            running.cancel(true);
        }
    }

    public boolean isKilled() {
        return this.killed;
    }

    boolean isFinished() {
        Future<?> running = this.worker;
        return running != null && running.isDone();
    }
}
