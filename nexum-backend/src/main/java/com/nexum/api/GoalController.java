package com.nexum.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nexum.goal.GoalRepository;
import com.nexum.goal.GoalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Goals, their tasks, and their membership.
 *
 * <p>This is the operator plane. Agents do not call it - they run inside the
 * process and reach memory through {@code MemoryService}. Keeping the two apart
 * is what stops the HTTP surface from becoming a way around the access policy.
 */
@RestController
@RequestMapping("/api/goals")
class GoalController {

    private final GoalService goals;

    GoalController(GoalService goals) {
        this.goals = goals;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> createGoal(@Valid @RequestBody CreateGoal request) {
        return Map.of("goalId", this.goals.createGoal(request.title(), request.description()));
    }

    @GetMapping
    List<GoalRepository.GoalView> listGoals() {
        return this.goals.goals();
    }

    @GetMapping("/{goalId}")
    GoalRepository.GoalView goal(@PathVariable UUID goalId) {
        return this.goals.goal(goalId);
    }

    @PostMapping("/{goalId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> createTask(@PathVariable UUID goalId, @Valid @RequestBody CreateTask request) {
        this.goals.requireGoal(goalId);
        return Map.of("taskId", this.goals.createTask(goalId, request.title(),
                request.description(), (request.priority() != null) ? request.priority() : 0));
    }

    @GetMapping("/{goalId}/tasks")
    List<GoalRepository.TaskView> tasks(@PathVariable UUID goalId) {
        this.goals.requireGoal(goalId);
        return this.goals.tasks(goalId);
    }

    /**
     * Adds an agent to the goal.
     *
     * <p>Also the recovery entry point: pointing a fresh agent at a goal whose
     * worker died is the whole of "replacement". There is no resurrect endpoint,
     * because resurrection is not the model.
     */
    @PostMapping("/{goalId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> join(@PathVariable UUID goalId, @Valid @RequestBody JoinGoal request) {
        this.goals.requireGoal(goalId);
        this.goals.requireAgent(request.agentId());
        this.goals.join(goalId, request.agentId(), request.role());
        return Map.of("goalId", goalId, "agentId", request.agentId());
    }

    @GetMapping("/{goalId}/members")
    List<GoalRepository.MemberView> members(@PathVariable UUID goalId) {
        this.goals.requireGoal(goalId);
        return this.goals.members(goalId);
    }

    @GetMapping("/{goalId}/runs")
    List<GoalRepository.RunView> runs(@PathVariable UUID goalId) {
        this.goals.requireGoal(goalId);
        return this.goals.runs(goalId);
    }

    /** The record of every detected failure, and the checkpoint each one preserved. */
    @GetMapping("/{goalId}/failures")
    List<GoalRepository.FailureView> failures(@PathVariable UUID goalId) {
        this.goals.requireGoal(goalId);
        return this.goals.failures(goalId);
    }

    record CreateGoal(@NotBlank String title, String description) {
    }

    record CreateTask(@NotBlank String title, String description, Integer priority) {
    }

    record JoinGoal(@NotNull UUID agentId, @NotBlank String role) {
    }
}
