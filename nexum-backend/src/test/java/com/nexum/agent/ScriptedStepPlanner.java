package com.nexum.agent;

import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A planner that does exactly what the test tells it to.
 *
 * <p>The recovery sequence has to be asserted on precisely and run many times.
 * With a real model in the loop, "agent D continued the work" would depend on
 * what a 70B model chose to say that afternoon, and a flaky test of the one
 * behaviour this project is judged on is worse than no test.
 *
 * <p>It also records every {@link Context} it was handed, which is how a test
 * can assert the more interesting thing: not just that the replacement finished
 * the task, but that it was <em>shown</em> memory written by an agent it never
 * overlapped with.
 */
public class ScriptedStepPlanner implements StepPlanner {

    private final Queue<Proposal> script = new ConcurrentLinkedQueue<>();

    private final List<Context> seen = new CopyOnWriteArrayList<>();

    /**
     * Sentinel meaning "park here until this worker is killed". Compared by
     * identity, so it can never collide with a real scripted proposal.
     */
    private static final Proposal BLOCK =
            Proposal.complete("unreachable", "block sentinel");

    public void script(Proposal... proposals) {
        this.script.addAll(List.of(proposals));
    }

    /**
     * Scripts a step that never returns on its own.
     *
     * <p>Needed to kill an agent while it is genuinely mid-task rather than
     * between tasks. Without it the loop would finish long before a test could
     * kill anything, and the recovery being demonstrated would be recovery of
     * work that was already complete - which proves nothing.
     */
    public void scriptBlockUntilKilled() {
        this.script.add(BLOCK);
    }

    public List<Context> seen() {
        return List.copyOf(this.seen);
    }

    public void reset() {
        this.script.clear();
        this.seen.clear();
    }

    @Override
    public Proposal plan(Context context) {
        this.seen.add(context);
        Proposal next = this.script.poll();

        if (next == BLOCK) {
            block();
        }

        // Running past the end of the script completes rather than looping, so
        // a mis-scripted test fails on an assertion instead of on a timeout.
        return (next != null) ? next : Proposal.complete("Nothing further to do", "script ended");
    }

    /**
     * Stands in for a long model call. Throwing on interrupt is what a killed
     * worker really does, and it exercises the loop's error path rather than a
     * tidier one invented for the test.
     */
    private void block() {
        try {
            Thread.sleep(Duration.ofMinutes(5));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("worker killed mid-step", ex);
        }
    }
}
