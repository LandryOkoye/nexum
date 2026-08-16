package com.nexum.memory;

import java.util.List;
import java.util.UUID;

import com.nexum.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access-control guarantee, made checkable.
 *
 * <p>"Memory is scoped" is a claim anyone can make about a system. These tests
 * are what turns it into a fact: an agent cannot read another agent's private
 * reasoning, and an agent that is not a member of a goal cannot read that goal's
 * memory at all - through either retrieval path, semantic or structured.
 *
 * <p>Both paths are exercised on purpose. A policy that holds for the query a
 * reviewer happens to read, and leaks through the other one, is the normal way
 * this kind of bug ships.
 */
@SpringBootTest
@ActiveProfiles("test")
class MemoryIsolationTests {

    private static final int DIMENSIONS = 1024;

    @Autowired
    private MemoryService memories;

    @Autowired
    private MemoryRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;

    private UUID goal;

    private UUID alice;

    private UUID bob;

    private UUID outsider;

    @BeforeEach
    void setUp() {
        this.data = new TestData(this.jdbc);
        this.goal = this.data.goal("Analyse competitors in African fintech");
        this.alice = this.data.agent("alice", "RESEARCHER");
        this.bob = this.data.agent("bob", "ANALYST");
        this.outsider = this.data.agent("outsider", "ANALYST");

        this.data.join(this.goal, this.alice, "RESEARCHER");
        this.data.join(this.goal, this.bob, "ANALYST");
        // outsider deliberately never joins.
    }

    @Test
    @DisplayName("an agent cannot read another agent's PRIVATE memory on a goal they share")
    void privateMemoryIsInvisibleToTheOtherMembersOfTheGoal() {
        this.memories.remember(privateHunch());
        this.memories.remember(sharedFinding());

        List<String> visibleToBob = contentsOf(this.memories.recall(
                this.bob, this.goal, "competitor pricing", 10));

        assertThat(visibleToBob)
                .as("goal memory is shared with every active member")
                .contains(SHARED_FINDING)
                .as("private reasoning belongs to its author alone")
                .doesNotContain(PRIVATE_HUNCH);

        assertThat(contentsOf(this.memories.recall(this.alice, this.goal, "competitor pricing", 10)))
                .as("the author still sees their own private memory")
                .contains(PRIVATE_HUNCH, SHARED_FINDING);
    }

    @Test
    @DisplayName("a non-member cannot read a goal's GOAL memory")
    void goalMemoryIsInvisibleToNonMembers() {
        this.memories.remember(sharedFinding());

        assertThat(contentsOf(this.memories.recall(this.outsider, this.goal, "competitor pricing", 10)))
                .as("membership is the boundary; a non-member sees nothing at all")
                .isEmpty();
    }

    @Test
    @DisplayName("an agent that left the goal loses access to its memory")
    void goalMemoryIsInvisibleAfterLeaving() {
        this.memories.remember(sharedFinding());
        this.data.leave(this.goal, this.bob);

        assertThat(contentsOf(this.memories.recall(this.bob, this.goal, "competitor pricing", 10)))
                .as("visibility follows ACTIVE membership, not having once been a member")
                .isEmpty();
    }

    @Test
    @DisplayName("the same policy applies on the semantic path")
    void vectorSearchCannotReachAnotherAgentsPrivateMemory() {
        UUID hunch = this.memories.remember(privateHunch());
        UUID finding = this.memories.remember(sharedFinding());

        // Both memories are placed at the same point in vector space, so the
        // private one is the strongest possible candidate for Bob's query. Only
        // the access predicate keeps it out of his results.
        float[] axis = TestData.axis(7, DIMENSIONS);
        this.repository.attachEmbedding(hunch, axis);
        this.repository.attachEmbedding(finding, axis);

        List<ScoredMemory> hits = this.repository.searchSemantic(this.bob, this.goal, axis, 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().memory().content()).isEqualTo(SHARED_FINDING);
        assertThat(hits.getFirst().distance())
                .as("an identical vector should rank at essentially zero distance")
                .isNotNull()
                .isLessThan(0.001);

        assertThat(this.repository.searchSemantic(this.outsider, this.goal, axis, 10))
                .as("a non-member gets nothing from the vector index either")
                .isEmpty();
    }

    @Test
    @DisplayName("retrieval works before any embedding exists")
    void structuredRetrievalCarriesTheCollectiveWhileVectorsAreMissing() {
        this.memories.remember(sharedFinding());

        MemoryService.Recall recall = this.memories.recall(this.bob, this.goal, "pricing", 10);

        assertThat(recall.strategy())
                .as("no provider is configured in tests, so this must degrade, not fail")
                .isEqualTo(MemoryService.Recall.Strategy.STRUCTURED);
        assertThat(contentsOf(recall)).contains(SHARED_FINDING);
        assertThat(recall.memories().getFirst().distance())
                .as("a structured hit reports no distance rather than inventing one")
                .isNull();
    }

    @Test
    @DisplayName("an unevidenced claim cannot outrank an evidenced one")
    void confidenceIsCappedWhenNoEvidenceIsAttached() {
        this.memories.remember(new NewMemory(this.goal, this.alice, null, MemoryScope.GOAL,
                MemoryType.FACT, "Competitor Z is about to exit the market", "model hunch",
                0.99, List.of()));
        this.memories.remember(sharedFinding());

        List<ScoredMemory> ranked = this.memories.recall(this.bob, this.goal, "competitors", 10)
                .memories();

        assertThat(ranked.getFirst().memory().content())
                .as("structured ranking is confidence-ordered, and evidence is what earns confidence")
                .isEqualTo(SHARED_FINDING);
        assertThat(ranked.getLast().memory().confidence())
                .as("the model asserted 0.99 for a claim with nothing behind it")
                .isLessThanOrEqualTo(0.5);
    }

    // --- fixtures --------------------------------------------------------

    private static final String PRIVATE_HUNCH =
            "Hunch: their pricing page looks different, but I have not verified it yet";

    private static final String SHARED_FINDING =
            "Competitor X reduced transaction pricing by 8 percent in Q2";

    private NewMemory privateHunch() {
        return new NewMemory(this.goal, this.alice, null, MemoryScope.PRIVATE,
                MemoryType.HYPOTHESIS, PRIVATE_HUNCH, "scratch", 0.9, List.of());
    }

    private NewMemory sharedFinding() {
        return new NewMemory(this.goal, this.alice, null, MemoryScope.GOAL, MemoryType.FACT,
                SHARED_FINDING, "q2-filing", 0.9,
                List.of(new NewMemory.Evidence("DOCUMENT", "q2-filing#p4",
                        "transaction pricing reduced by 8%", 0.9)));
    }

    private static List<String> contentsOf(MemoryService.Recall recall) {
        return recall.memories().stream().map((scored) -> scored.memory().content()).toList();
    }
}
