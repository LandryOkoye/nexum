package com.nexum.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nexum.goal.GoalService;
import com.nexum.memory.MemoryService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading goal memory, as a named agent.
 *
 * <p><strong>{@code asAgent} is required, and that is the interesting part.</strong>
 * There is no way to ask this endpoint for "all memory on this goal". Every
 * query is answered from the perspective of a specific agent and runs through
 * exactly the same {@code MemoryAccessPolicy} the agents themselves go through -
 * so what comes back is precisely what that agent could see, no more.
 *
 * <p>That makes the isolation guarantee demonstrable rather than merely claimed.
 * Ask as the author and a private memory is there; ask as a colleague on the
 * same goal and it is gone; ask as a non-member and the goal returns nothing at
 * all. Same endpoint, same policy, three different answers - which is a far
 * better argument than a paragraph asserting that scoping works.
 *
 * <p>This is the operator/inspection plane. Agents never reach memory over HTTP;
 * they call the service in-process. Neither plane can widen what the policy
 * permits.
 */
@RestController
@RequestMapping("/api/goals/{goalId}/memory")
class MemoryController {

    private static final int DEFAULT_LIMIT = 20;

    private final MemoryService memories;
    private final GoalService goals;

    MemoryController(MemoryService memories, GoalService goals) {
        this.memories = memories;
        this.goals = goals;
    }

    @GetMapping
    RecallResponse recall(@PathVariable UUID goalId,
            @RequestParam UUID asAgent,
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(required = false) Integer limit) {

        this.goals.requireGoal(goalId);
        this.goals.requireAgent(asAgent);

        MemoryService.Recall recall = this.memories.recall(asAgent, goalId, query,
                (limit != null) ? Math.clamp(limit, 1, 100) : DEFAULT_LIMIT);

        List<MemoryView> found = recall.memories().stream()
                .map((scored) -> new MemoryView(
                        scored.memory().id(),
                        scored.memory().agentId(),
                        scored.memory().taskId(),
                        scored.memory().scope().name(),
                        scored.memory().type().name(),
                        scored.memory().content(),
                        scored.memory().source(),
                        scored.memory().confidence(),
                        scored.memory().embeddingStatus(),
                        scored.distance(),
                        scored.similarity(),
                        scored.memory().createdAt()))
                .toList();

        return new RecallResponse(asAgent, recall.strategy().name(), found.size(), found);
    }

    /**
     * @param strategy SEMANTIC or STRUCTURED - reported rather than hidden, so a
     *        viewer always knows whether they are looking at vector ranking or
     *        the confidence-and-recency fallback
     */
    record RecallResponse(UUID asAgent, String strategy, int count, List<MemoryView> memories) {
    }

    record MemoryView(UUID id, UUID authorAgentId, UUID taskId, String scope, String type,
            String content, String source, double confidence, String embeddingStatus,
            Double distance, Double similarity, Instant createdAt) {
    }
}
