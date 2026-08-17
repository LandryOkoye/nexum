package com.nexum.agent;

import java.util.List;

import com.nexum.memory.MemoryType;
import com.nexum.memory.ScoredMemory;

/**
 * Decides what an agent does next.
 *
 * <p>An interface, not a class, for the same reason {@code AgentDispatcher} is:
 * the reasoning provider is a swappable detail, and pinning the loop to one
 * makes the loop untestable. The recovery sequence has to be run dozens of
 * times, and a test whose outcome depends on what a 70B model felt like saying
 * is not a test. A scripted planner makes the kill-and-recover path
 * deterministic; {@link ModelStepPlanner} is what runs in the demo.
 *
 * <p>Implementations must never write authoritative state. A planner proposes;
 * {@code AgentLoop} validates and persists. That boundary is Invariant 7, and it
 * is why the model has no database access of any kind.
 */
public interface StepPlanner {

    Proposal plan(Context context);

    /**
     * Everything the agent knows at this step.
     *
     * @param taskTitle what the task is
     * @param taskDescription the fuller statement of it
     * @param step which iteration this is, 1-based
     * @param maxSteps the bound; autonomy here is finite by construction
     * @param progressSummary what previous steps achieved - possibly written by
     *        a different agent, on a previous run, before this one existed
     * @param recalled memory this agent is permitted to see, already scoped
     * @param lastToolResult output of the previous tool call, if any
     */
    record Context(String taskTitle, String taskDescription, int step, int maxSteps,
            String progressSummary, List<ScoredMemory> recalled, String lastToolResult) {
    }

    /**
     * A proposed next action.
     *
     * <p>{@code confidence} is the model's self-report and is treated as such -
     * the backend caps it when no evidence backs the claim.
     */
    record Proposal(Action action, String query, String finding, MemoryType memoryType,
            String evidenceDocId, double confidence, String summary, String reason) {

        public enum Action {

            /** Look something up in the seeded corpus. */
            SEARCH,

            /** Commit a finding to the goal's shared memory. */
            REMEMBER,

            /** Declare the task done. */
            COMPLETE

        }

        public static Proposal search(String query, String reason) {
            return new Proposal(Action.SEARCH, query, null, null, null, 0.5,
                    "Searched the corpus for: " + query, reason);
        }

        public static Proposal remember(String finding, MemoryType type, String evidenceDocId,
                double confidence, String reason) {
            return new Proposal(Action.REMEMBER, null, finding, type, evidenceDocId, confidence,
                    "Recorded a finding: " + finding, reason);
        }

        public static Proposal complete(String summary, String reason) {
            return new Proposal(Action.COMPLETE, null, null, null, null, 0.9, summary, reason);
        }
    }
}
