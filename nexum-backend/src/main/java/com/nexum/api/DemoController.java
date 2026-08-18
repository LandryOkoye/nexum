package com.nexum.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nexum.goal.GoalService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Builds a goal ready to demonstrate, in one call.
 *
 * <p>Not convenience for its own sake. The demo has to be repeatable on a
 * three-minute clock, and a run that starts with six curl commands is a run
 * where the fifth one gets mistyped on camera. This makes the starting state a
 * single request, so what follows is only the part worth watching.
 *
 * <p>Note the fourth agent: it is created but deliberately <strong>not</strong>
 * joined to the goal. It is the replacement, and having it wait outside makes
 * the recovery step honest - a fresh agent joining a mission it had no part in,
 * rather than a spare worker that was already a member all along.
 */
@RestController
@RequestMapping("/api/demo")
class DemoController {

    private static final String GOAL_TITLE =
            "Analyse competitors in African fintech: pricing and positioning";

    private static final String GOAL_DESCRIPTION = """
            Identify which competitors have changed transaction pricing, what the \
            change was, and what it implies for our positioning. Record findings as \
            goal memory with evidence so any agent on this mission can build on them.""";

    private record SeedTask(String title, String description, int priority) {
    }

    private static final List<SeedTask> TASKS = List.of(
            new SeedTask("Find transaction pricing changes",
                    "Search the corpus for competitors that changed transaction pricing. "
                            + "Record each confirmed change as goal memory, citing the document.",
                    30),
            new SeedTask("Assess positioning against price leaders",
                    "Using what the collective already knows about pricing, identify which "
                            + "competitors compete on price and which compete on distribution.",
                    20),
            new SeedTask("Summarise the regulatory pressure on fees",
                    "Determine whether any regulatory action is likely to compress fees, and "
                            + "record the implication for our pricing strategy.",
                    10));

    private record SeedAgent(String name, String role, boolean joins) {
    }

    private static final List<SeedAgent> AGENTS = List.of(
            new SeedAgent("Ada", "RESEARCHER", true),
            new SeedAgent("Bello", "ANALYST", true),
            new SeedAgent("Chidi", "STRATEGIST", true),
            // The replacement. Waits outside the goal until recovery needs it.
            new SeedAgent("Dara", "ANALYST", false));

    private final GoalService goals;
    private final DemoBacklog backlog;

    DemoController(GoalService goals, DemoBacklog backlog) {
        this.goals = goals;
        this.backlog = backlog;
    }

    @PostMapping("/seed")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> seed() {
        UUID goalId = this.goals.createGoal(GOAL_TITLE, GOAL_DESCRIPTION);

        List<Map<String, Object>> agents = new ArrayList<>();
        List<UUID> members = new ArrayList<>();
        for (SeedAgent seed : AGENTS) {
            // Names are suffixed so seeding twice does not produce two agents a
            // demo operator cannot tell apart on screen.
            UUID agentId = this.goals.createAgent(
                    seed.name() + "-" + shortId(goalId), seed.role());
            if (seed.joins()) {
                this.goals.join(goalId, agentId, seed.role());
                members.add(agentId);
            }
            agents.add(new LinkedHashMap<>(Map.of(
                    "agentId", agentId,
                    "name", seed.name(),
                    "role", seed.role(),
                    "member", seed.joins())));
        }

        // Written after the agents join, because the backlog is attributed to
        // them - it is the mission's own history, not anonymous fixture data.
        int seededMemories = this.backlog.seed(goalId, members);

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (SeedTask seed : TASKS) {
            tasks.add(new LinkedHashMap<>(Map.of(
                    "taskId", this.goals.createTask(goalId, seed.title(), seed.description(),
                            seed.priority()),
                    "title", seed.title())));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("goalId", goalId);
        response.put("agents", agents);
        response.put("tasks", tasks);
        // Reported so an operator knows the vectors are still arriving: the
        // embedding worker sweeps these in batches, and semantic retrieval only
        // becomes the chosen plan once enough of them are READY.
        response.put("seededMemories", seededMemories);
        response.put("next", List.of(
                "POST /api/goals/" + goalId + "/agents/{agentId}/runs   (start an agent)",
                "GET  /api/goals/" + goalId + "/memory?asAgent={agentId}",
                "GET  /api/goals/" + goalId + "/events/stream",
                "POST /api/runs/{runId}/kill                            (the interesting one)"));
        return response;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
