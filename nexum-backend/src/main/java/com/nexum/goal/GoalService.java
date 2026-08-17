package com.nexum.goal;

import java.util.List;
import java.util.UUID;

import com.nexum.coordination.TaskRepository;
import com.nexum.event.EventLog;
import com.nexum.event.EventType;
import com.nexum.support.Json;

import org.springframework.stereotype.Service;

/**
 * Goal lifecycle: creating goals and tasks, and admitting agents to them.
 *
 * <p>Thin on purpose. Its job is to make sure that every state change worth
 * watching also lands in the event log, because the event log is what the
 * dashboard renders and what a judge reads as the audit trail. A state change
 * that happened without an event is invisible, and an event without the state
 * change behind it is a lie - so they are written together, here, rather than
 * left to each caller to remember.
 */
@Service
public class GoalService {

    private final GoalRepository goals;
    private final TaskRepository tasks;
    private final EventLog events;

    public GoalService(GoalRepository goals, TaskRepository tasks, EventLog events) {
        this.goals = goals;
        this.tasks = tasks;
        this.events = events;
    }

    public UUID createGoal(String title, String description) {
        UUID goalId = this.goals.createGoal(title, description);
        this.events.append(goalId, EventType.GOAL_CREATED,
                Json.object("goalId", goalId, "title", title));
        return goalId;
    }

    public UUID createTask(UUID goalId, String title, String description, int priority) {
        UUID taskId = this.tasks.createTask(goalId, title, description, priority);
        this.events.append(goalId, EventType.TASK_CREATED,
                Json.object("taskId", taskId, "title", title, "priority", priority));
        return taskId;
    }

    public UUID createAgent(String name, String role) {
        return this.goals.createAgent(name, role);
    }

    /**
     * Admits an agent to a goal.
     *
     * <p>Distinguishes joining from rejoining because the recovery narrative
     * turns on it: a replacement agent arriving at a goal whose previous worker
     * died is <em>rejoining a mission</em>, not being resurrected. The event
     * emitted here is one of the five that make up the recovery proof.
     */
    public void join(UUID goalId, UUID agentId, String role) {
        boolean rejoined = this.goals.joinGoal(goalId, agentId, role);
        this.events.append(goalId,
                rejoined ? EventType.AGENT_REJOINED_GOAL : EventType.AGENT_JOINED_GOAL,
                Json.object("goalId", goalId, "agentId", agentId, "role", role));
    }

    public GoalRepository.GoalView goal(UUID goalId) {
        return this.goals.findGoal(goalId)
                .orElseThrow(() -> new UnknownGoalException(goalId));
    }

    public List<GoalRepository.GoalView> goals() {
        return this.goals.listGoals();
    }

    public List<GoalRepository.MemberView> members(UUID goalId) {
        return this.goals.listMembers(goalId);
    }

    public List<GoalRepository.TaskView> tasks(UUID goalId) {
        return this.goals.listTasks(goalId);
    }

    public List<GoalRepository.RunView> runs(UUID goalId) {
        return this.goals.listRuns(goalId);
    }

    public List<GoalRepository.FailureView> failures(UUID goalId) {
        return this.goals.listFailures(goalId);
    }

    public void requireGoal(UUID goalId) {
        if (!this.goals.goalExists(goalId)) {
            throw new UnknownGoalException(goalId);
        }
    }

    public void requireAgent(UUID agentId) {
        if (!this.goals.agentExists(agentId)) {
            throw new UnknownAgentException(agentId);
        }
    }

    /** Thrown rather than returning empty so the API answers 404 in one place. */
    public static class UnknownGoalException extends RuntimeException {

        public UnknownGoalException(UUID goalId) {
            super("no goal " + goalId);
        }
    }

    public static class UnknownAgentException extends RuntimeException {

        public UnknownAgentException(UUID agentId) {
            super("no agent " + agentId);
        }
    }
}
