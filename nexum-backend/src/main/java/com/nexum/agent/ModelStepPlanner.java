package com.nexum.agent;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexum.memory.MemoryType;
import com.nexum.memory.ScoredMemory;
import com.nexum.support.TokenBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The reasoning planner, backed by Groq.
 *
 * <p>Everything here assumes the model is an unreliable narrator. It is asked
 * for JSON and frequently returns JSON wrapped in prose, or a field it invented,
 * or a confidence of 95 when asked for 0-1. None of that is allowed to stop an
 * agent: a step that cannot be parsed degrades to a sensible default rather than
 * throwing, because the alternative is a task stalling until its lease lapses
 * and the reaper declares a failure that never happened. A demo about resilience
 * should not be brought down by a stray backtick.
 */
@Component
public class ModelStepPlanner implements StepPlanner {

    private static final Logger log = LoggerFactory.getLogger(ModelStepPlanner.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM = """
            You are one agent in a collective pursuing a shared goal. Other agents
            work on other tasks and share what they learn through goal memory. You
            may be resuming work that a different agent started and did not finish.

            Choose exactly ONE action per step:
              SEARCH   - research a question. Provide "query" as the words you
                         would type into a search engine, not a sentence.
              REMEMBER - record a finding for the whole collective. Provide
                         "finding", "memoryType", "evidenceDocId", "confidence".
              COMPLETE - the task is done. Provide "summary".

            Sources are shown as [id] Title <url>. To cite one, copy its id into
            "evidenceDocId" exactly as written. A finding whose citation does not
            match a source you were actually shown is recorded as unsupported and
            its confidence is capped - so never cite from memory or invent an id.

            A finding must be specific enough to be useful to an agent who has
            not read the source: name the company, the number, the date. "Prices
            are changing" is worthless; "Kuda cut P2P transfer fees 8% in Q2
            2026" is a finding.

            Do not repeat a finding already in your recalled memory - the
            collective already knows it. Search for what is missing instead.
            Prefer REMEMBER once you have found something concrete, and COMPLETE
            once the task question is answered.

            Reply with JSON only, no prose and no code fences:
            {"action":"SEARCH|REMEMBER|COMPLETE","query":"","finding":"",
             "memoryType":"FACT|OBSERVATION|HYPOTHESIS|LESSON|SUMMARY",
             "evidenceDocId":"","confidence":0.0,"summary":"","reason":""}
            """;

    private final ObjectProvider<ChatClient.Builder> chatClients;
    private final TokenBucket limiter;

    public ModelStepPlanner(ObjectProvider<ChatClient.Builder> chatClients,
            @Value("${nexum.llm.permits-per-minute:25}") long permitsPerMinute) {
        this.chatClients = chatClients;
        this.limiter = new TokenBucket(permitsPerMinute);
    }

    @Override
    public Proposal plan(Context context) {
        ChatClient.Builder builder = this.chatClients.getIfAvailable();
        if (builder == null) {
            return fallback(context, "no reasoning provider configured");
        }

        try {
            this.limiter.acquire();
            String reply = builder.build().prompt()
                    .system(SYSTEM)
                    .user(userPrompt(context))
                    .call()
                    .content();
            return parse(reply, context);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return fallback(context, "interrupted waiting for a rate-limit permit");
        }
        catch (RuntimeException ex) {
            return fallback(context, "model call failed: " + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage());
        }
    }

    private String userPrompt(Context context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("TASK: ").append(context.taskTitle()).append('\n');
        if (context.taskDescription() != null) {
            prompt.append(context.taskDescription()).append('\n');
        }
        prompt.append("\nSTEP ").append(context.step()).append(" of ").append(context.maxSteps())
                .append('\n');

        if (context.progressSummary() != null && !context.progressSummary().isBlank()) {
            prompt.append("\nPROGRESS SO FAR (may be from another agent):\n")
                    .append(context.progressSummary()).append('\n');
        }

        if (!context.recalled().isEmpty()) {
            prompt.append("\nWHAT THE COLLECTIVE ALREADY KNOWS:\n");
            for (ScoredMemory scored : context.recalled()) {
                prompt.append("- [").append(scored.memory().type()).append("] ")
                        .append(scored.memory().content()).append('\n');
            }
        }

        if (context.lastToolResult() != null && !context.lastToolResult().isBlank()) {
            prompt.append("\nSEARCH RESULTS:\n").append(context.lastToolResult()).append('\n');
        }

        prompt.append("\nRespond with the JSON object only.");
        return prompt.toString();
    }

    /**
     * Extracts the JSON object from whatever the model actually sent.
     *
     * <p>Scans for the outermost braces rather than trusting the whole reply to
     * be JSON, which handles the two things this model does constantly: wrapping
     * the object in a code fence, and prefacing it with a sentence of
     * explanation nobody asked for.
     */
    private Proposal parse(String reply, Context context) {
        if (reply == null || reply.isBlank()) {
            return fallback(context, "model returned an empty response");
        }

        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return fallback(context, "model response contained no JSON object");
        }

        try {
            JsonNode node = MAPPER.readTree(reply.substring(start, end + 1));
            Proposal.Action action = action(text(node, "action"), context);
            String reason = text(node, "reason");

            return switch (action) {
                case SEARCH -> {
                    String query = text(node, "query");
                    yield Proposal.search(!query.isBlank() ? query : context.taskTitle(), reason);
                }
                case REMEMBER -> Proposal.remember(text(node, "finding"),
                        memoryType(text(node, "memoryType")), text(node, "evidenceDocId"),
                        node.path("confidence").asDouble(0.5), reason);
                case COMPLETE -> {
                    String summary = text(node, "summary");
                    yield Proposal.complete(!summary.isBlank() ? summary : "Task complete", reason);
                }
            };
        }
        catch (Exception ex) {
            log.warn("Could not parse model reply at step {}: {}", context.step(), reply, ex);
            return fallback(context, "model response was not valid JSON");
        }
    }

    private static Proposal.Action action(String raw, Context context) {
        try {
            return Proposal.Action.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException | NullPointerException ex) {
            // An unrecognised action late in the budget should finish rather
            // than burn a step searching again.
            return lastStep(context) ? Proposal.Action.COMPLETE : Proposal.Action.SEARCH;
        }
    }

    private static MemoryType memoryType(String raw) {
        try {
            return MemoryType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException | NullPointerException ex) {
            // OBSERVATION is the honest default: it claims less than FACT.
            return MemoryType.OBSERVATION;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    /**
     * What to do when the model is unavailable or unintelligible: search early,
     * finish late. Never stall.
     *
     * <p>Always logs. An earlier version degraded silently on some paths, and the
     * result was an agent that looked like it was working - searching, saving
     * checkpoints, completing - while every single step was actually a fallback,
     * because the model was returning 404 and nothing said so. Graceful
     * degradation that is also invisible is worse than a crash: the system lies
     * to you convincingly.
     */
    private static Proposal fallback(Context context, String why) {
        log.warn("Step {} of {} fell back to a default action: {}", context.step(),
                context.maxSteps(), why);

        if (lastStep(context)) {
            return Proposal.complete(context.progressSummary() != null ? context.progressSummary()
                    : "Completed without model guidance", why);
        }
        return Proposal.search(context.taskTitle(), why);
    }

    private static boolean lastStep(Context context) {
        return context.step() >= context.maxSteps();
    }
}
