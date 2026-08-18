package com.nexum.inspect;

import java.util.UUID;

import com.nexum.goal.GoalService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The inspection plane: how CockroachDB served the last question asked of it.
 *
 * <p>Read-only, fixed-statement, and deliberately separate from
 * {@code /api/goals/**}. Retrieval and the explanation of retrieval are different
 * concerns with different audiences - agents call the former in-process and never
 * call the latter at all.
 *
 * <p>Every parameter here is an identifier or a search string, bound as a query
 * parameter. Nothing a caller sends becomes SQL; see {@link CockroachInspector}
 * for why that boundary is drawn strictly rather than conveniently.
 */
@RestController
@RequestMapping("/api/cockroach")
class InspectController {

    private static final int DEFAULT_LIMIT = 20;

    private final CockroachInspector inspector;
    private final GoalService goals;

    InspectController(CockroachInspector inspector, GoalService goals) {
        this.inspector = inspector;
        this.goals = goals;
    }

    /** What we are connected to, and which vector indexes exist on it. */
    @GetMapping
    CockroachInspector.Cluster cluster() {
        return this.inspector.cluster();
    }

    /**
     * The execution plan for this agent's view of this goal's memory.
     *
     * <p>Takes the same {@code asAgent} the retrieval endpoint takes, and for the
     * same reason: the plan is a property of who is asking. Ask as a member and
     * the response carries two indexed searches; ask as a non-member and it
     * carries none, because the policy returned no grants and no query ran.
     */
    @GetMapping("/goals/{goalId}/recall-plan")
    CockroachInspector.RecallPlan recallPlan(@PathVariable UUID goalId,
            @RequestParam UUID asAgent,
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(required = false) Integer limit) {

        this.goals.requireGoal(goalId);
        this.goals.requireAgent(asAgent);

        return this.inspector.explainRecall(asAgent, goalId, query,
                (limit != null) ? Math.clamp(limit, 1, 100) : DEFAULT_LIMIT);
    }
}
