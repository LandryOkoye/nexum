package com.nexum.agent;

import java.util.List;
import java.util.Optional;

/**
 * Where an agent gets information it does not already have.
 *
 * <p>An interface for the same reason {@link StepPlanner} and
 * {@code AgentDispatcher} are: what the collective researches is a deployment
 * decision, not a property of the runtime. The memory layer, the access policy
 * and the recovery path are indifferent to whether a finding came from the live
 * web or a fixture, and pinning the loop to one source would make that
 * indifference impossible to demonstrate.
 *
 * <p>There are two implementations and the difference between them matters on a
 * demo day: {@link WebResearchTool} reaches the real internet, and
 * {@link CorpusResearchTool} answers from a fixed set of documents. The second
 * is not a toy - it is what runs when the network is hostile, and it keeps the
 * kill-and-recover story runnable in a room with bad wifi.
 *
 * <p><strong>{@link #byId} is a security boundary, not a convenience.</strong>
 * It exists so that {@code AgentLoop} can resolve a citation against what was
 * actually retrieved instead of trusting the model's word for it. An
 * implementation must never synthesise a source here, and must only return
 * material it genuinely fetched - otherwise an invented citation would be
 * laundered into evidence, and evidence is what lifts a claim past the
 * unevidenced confidence ceiling.
 */
public interface ResearchTool {

    /** Short identifier for this tool, recorded on every {@code TOOL_CALLED} event. */
    String name();

    /** Human-readable description of where results come from, shown in the UI. */
    String describe();

    /**
     * Finds material relevant to a query, best first.
     *
     * <p>Must never throw. A research tool that is down is a degraded agent, not
     * a failed one: returning an empty list lets the loop record that it found
     * nothing and move on, whereas an exception would kill a run holding a lease
     * and turn a third party's outage into a task that needs recovering.
     */
    List<Source> search(String query, int limit);

    /** Resolves a citation to something previously returned by {@link #search}. */
    Optional<Source> byId(String id);

    /**
     * One retrievable thing, whatever it came from.
     *
     * @param id the token an agent cites; must be stable enough that a citation
     *        written at step 2 still resolves at step 6
     * @param url where a human can verify the claim, or null for offline sources
     */
    record Source(String id, String title, String body, String url) {

        /**
         * How a source is shown to the model.
         *
         * <p>The id is stated first and labelled, because the model has to copy
         * it back verbatim to cite the source, and a citation it garbles is a
         * citation that fails to resolve and costs its claim the confidence it
         * would otherwise have earned.
         */
        public String asContext() {
            StringBuilder text = new StringBuilder()
                    .append("[").append(this.id).append("] ").append(this.title);
            if (this.url != null) {
                text.append(" <").append(this.url).append(">");
            }
            return text.append("\n").append(this.body).toString();
        }
    }
}
