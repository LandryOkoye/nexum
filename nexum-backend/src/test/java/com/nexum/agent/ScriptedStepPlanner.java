package com.nexum.agent;

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

    public void script(Proposal... proposals) {
        this.script.addAll(List.of(proposals));
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
        // Running past the end of the script completes rather than looping, so
        // a mis-scripted test fails on an assertion instead of on a timeout.
        return (next != null) ? next : Proposal.complete("Nothing further to do", "script ended");
    }
}
