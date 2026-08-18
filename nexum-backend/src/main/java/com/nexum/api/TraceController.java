package com.nexum.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexum.agent.CheckpointRepository;
import com.nexum.goal.GoalService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What was actually done on a task, step by step.
 *
 * <p>The rest of the API answers "what is the state now". This answers "how did
 * it get here", which is the question the whole project is an argument about. A
 * task that was orphaned and picked up by a replacement looks, in every other
 * view, like a task that is simply running - the same status, the same progress.
 * Only the trace shows the seam: one continuous sequence of steps whose author
 * changes partway through.
 *
 * <p>Read-only and unscoped by agent on purpose. This is the operator plane, and
 * a task's history is a property of the mission rather than of any participant
 * in it - which is the same reason checkpoints are keyed by task and not by run.
 */
@RestController
@RequestMapping("/api/goals/{goalId}/tasks/{taskId}")
class TraceController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CheckpointRepository checkpoints;
    private final GoalService goals;

    TraceController(CheckpointRepository checkpoints, GoalService goals) {
        this.checkpoints = checkpoints;
        this.goals = goals;
    }

    @GetMapping("/trace")
    List<StepView> trace(@PathVariable UUID goalId, @PathVariable UUID taskId) {
        this.goals.requireGoal(goalId);

        return this.checkpoints.traceFor(taskId).stream()
                .map(TraceController::toView)
                .toList();
    }

    /**
     * Lifts the model's stated reason out of the stored context blob.
     *
     * <p>Unpacked here rather than handed to the browser as raw JSON, because
     * the shape of a checkpoint's context is an internal detail that has already
     * changed once. A parse failure yields a null reason and an otherwise intact
     * step: a step that happened is not made not to have happened by a context
     * blob nobody can read.
     */
    private static StepView toView(CheckpointRepository.Step step) {
        String reason = null;
        Integer number = null;
        try {
            JsonNode context = MAPPER.readTree(step.context());
            reason = context.path("reason").isMissingNode() ? null
                    : context.path("reason").asText(null);
            number = context.path("step").isMissingNode() ? null
                    : context.path("step").asInt();
        }
        catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Deliberately swallowed; see the method comment.
        }

        return new StepView(step.seq(), number, step.runId(), step.agentId(), step.agentName(),
                step.action(), reason, step.progressSummary(), step.createdAt());
    }

    /**
     * @param seq position in the task's single, unbroken sequence - it does not
     *        reset when the task changes hands, which is the point
     * @param step the agent's own step counter within its run, which does
     * @param reason the model's stated justification for this action
     */
    record StepView(int seq, Integer step, UUID runId, UUID agentId, String agentName,
            String action, String reason, String progressSummary, Instant at) {
    }
}
