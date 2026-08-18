package com.nexum.api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nexum.agent.CompetitorCorpus;
import com.nexum.memory.MemoryRepository;
import com.nexum.memory.MemoryScope;
import com.nexum.memory.MemoryType;
import com.nexum.memory.NewMemory;

import org.springframework.stereotype.Component;

/**
 * The work the collective did before the demo started.
 *
 * <p>A mission seeded with an empty memory table misrepresents the system twice
 * over. It suggests a collective's memory is a handful of rows, and - more
 * damagingly - it makes CockroachDB's vector index unusable: the optimiser
 * correctly declines to run a vector search over eight rows when scanning them
 * is cheaper, so a dashboard explaining the retrieval would truthfully report a
 * scan and the whole argument for the index would be invisible. Measured on this
 * schema, the plan switches to {@code vector search} at roughly fifty
 * goal-scoped memories.
 *
 * <p>So the demo goal opens with a realistic backlog. These are not filler
 * strings: each is a distinct analytical reading of a specific corpus document,
 * carries that document as evidence, and is authored by one of the agents on the
 * mission. Retrieval therefore has to discriminate between genuinely similar
 * statements about overlapping subjects, which is the situation a vector index
 * exists for and the one a keyword filter handles badly.
 *
 * <p>Written through the repository rather than {@code MemoryService} on
 * purpose. The service emits a {@code MEMORY_CREATED} event per write, and
 * seventy of those would bury the live timeline under history before the demo
 * begins. The timeline is for what happens while someone is watching; this is
 * what happened before they arrived.
 *
 * <p>Every entry carries evidence, so none is subject to the unevidenced
 * confidence ceiling and the confidences below are stored as written.
 */
@Component
class DemoBacklog {

    /**
     * One analytical angle on a document.
     *
     * <p>Each template consumes the document's title ({@code %1$s}) and its body
     * ({@code %2$s}), so every lens produces a different sentence for every
     * document rather than ten copies of one phrasing. Near-duplicates would
     * cluster in vector space and make retrieval look better than it is.
     */
    private record Lens(MemoryType type, MemoryScope scope, double confidence, String template) {
    }

    private static final List<Lens> LENSES = List.of(
            new Lens(MemoryType.FACT, MemoryScope.GOAL, 0.88,
                    "Confirmed from %1$s. %2$s"),
            new Lens(MemoryType.OBSERVATION, MemoryScope.GOAL, 0.77,
                    "Pricing signal in %1$s: %2$s Recorded because it changes the "
                            + "baseline we compare our own transaction fees against."),
            new Lens(MemoryType.HYPOTHESIS, MemoryScope.GOAL, 0.54,
                    "Working hypothesis from %1$s — if this holds, the competitor is "
                            + "buying share rather than margin. Source claim: %2$s"),
            new Lens(MemoryType.OUTCOME, MemoryScope.GOAL, 0.71,
                    "Positioning consequence of %1$s. %2$s The practical effect is that "
                            + "a like-for-like fee comparison no longer tells the whole story."),
            new Lens(MemoryType.LESSON, MemoryScope.GOAL, 0.66,
                    "Lesson recorded while reviewing %1$s: headline rates and effective "
                            + "cost to the merchant diverge here. %2$s"),
            new Lens(MemoryType.SUMMARY, MemoryScope.PRIVATE, 0.62,
                    "Own note on %1$s, not yet worth promoting to the mission: %2$s "
                            + "Needs a second source before anyone builds on it."),
            new Lens(MemoryType.FACT, MemoryScope.GOAL, 0.83,
                    "Regulatory and cost context for %1$s. %2$s"));

    private final MemoryRepository memories;
    private final CompetitorCorpus corpus;

    DemoBacklog(MemoryRepository memories, CompetitorCorpus corpus) {
        this.memories = memories;
        this.corpus = corpus;
    }

    /**
     * Writes the backlog and returns how many memories it created.
     *
     * <p>Authorship rotates through the agents that actually joined the goal, so
     * the "viewing as" control has something to distinguish: the private notes
     * belong to one agent each and are invisible to the others, exactly as they
     * would be had the agents written them live.
     */
    int seed(UUID goalId, List<UUID> memberIds) {
        List<CompetitorCorpus.Document> documents = this.corpus.all();
        List<UUID> authors = new ArrayList<>(memberIds);
        if (authors.isEmpty()) {
            return 0;
        }

        int written = 0;
        int turn = 0;
        for (CompetitorCorpus.Document document : documents) {
            for (Lens lens : LENSES) {
                UUID author = authors.get(turn++ % authors.size());
                String content = lens.template()
                        .formatted(document.title(), document.body());

                NewMemory memory = new NewMemory(goalId, author, null, lens.scope(),
                        lens.type(), content, document.id(), lens.confidence(),
                        List.of(new NewMemory.Evidence("DOCUMENT", document.id(),
                                document.body(), lens.confidence())));

                UUID id = this.memories.create(memory, lens.confidence());
                this.memories.addEvidence(id, memory.evidence());
                written++;
            }
        }
        return written;
    }
}
