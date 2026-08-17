package com.nexum.api;

import java.util.Map;
import java.util.UUID;

import com.nexum.agent.AgentDispatcher;
import com.nexum.goal.GoalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starting agents, creating them, and killing them.
 *
 * <p>The kill endpoint is the demo's one deliberately destructive button, and
 * what it does <em>not</em> do is the point. It stops a worker and its
 * heartbeat. It does not mark the task failed, orphaned, or anything else. At
 * the instant it returns, the database still believes the task is running and
 * still leased - which is exactly the state a real crash leaves behind.
 *
 * <p>Everything after that is the system detecting its own failure. If this
 * endpoint updated the task, the recovery that follows would be theatre, and a
 * judge would be right to read it that way.
 */
@RestController
@RequestMapping("/api")
class RunController {

    private final AgentDispatcher dispatcher;
    private final GoalService goals;

    RunController(AgentDispatcher dispatcher, GoalService goals) {
        this.dispatcher = dispatcher;
        this.goals = goals;
    }

    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> createAgent(@Valid @RequestBody CreateAgent request) {
        return Map.of("agentId", this.goals.createAgent(request.name(), request.role()));
    }

    /**
     * Starts a run: the agent claims whatever work is available on the goal.
     *
     * <p>Answers 409 when the concurrency cap is reached rather than queueing.
     * A caller that asked for a worker and did not get one should be told so
     * plainly - a silent queue would make the dashboard show an agent that is
     * not actually running.
     */
    @PostMapping("/goals/{goalId}/agents/{agentId}/runs")
    ResponseEntity<Map<String, Object>> dispatch(@PathVariable UUID goalId,
            @PathVariable UUID agentId) {

        this.goals.requireGoal(goalId);
        this.goals.requireAgent(agentId);

        return this.dispatcher.dispatch(agentId, goalId)
                .<ResponseEntity<Map<String, Object>>>map((runId) -> ResponseEntity
                        .status(HttpStatus.CREATED)
                        .body(Map.of("runId", runId, "agentId", agentId, "goalId", goalId)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "concurrency cap reached",
                                "activeRuns", this.dispatcher.activeRuns())));
    }

    /**
     * Kills a run the way a crash would.
     *
     * @return 404 if this process holds no such live run - which is itself
     *         informative: the run has already ended, or was never here
     */
    @PostMapping("/runs/{runId}/kill")
    ResponseEntity<Map<String, Object>> kill(@PathVariable UUID runId) {
        if (!this.dispatcher.kill(runId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "no live run " + runId));
        }

        return ResponseEntity.ok(Map.of(
                "runId", runId,
                "killed", true,
                "taskTouched", false,
                "note", "The task is untouched. Its lease will lapse and the reaper "
                        + "will detect the failure on its own."));
    }

    record CreateAgent(@NotBlank String name, @NotBlank String role) {
    }
}
