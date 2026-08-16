package com.nexum.agent;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * The seeded document set agents research against.
 *
 * <p>Deliberately offline and deterministic. Live web access would put a third
 * party on the critical path of a demo that has to work on the first take, and
 * would make every run return something slightly different - which destroys the
 * one thing this demo must show clearly: that agent D retrieved <em>the specific
 * finding</em> agent A wrote before it died. Fixed documents mean the narrative
 * is reproducible and any failure is ours.
 *
 * <p>The documents are synthetic. They are shaped so that several of them
 * genuinely relate to pricing - the thread the demo follows - while others are
 * near-miss distractors, so retrieval has something to actually discriminate
 * between rather than trivially matching the only relevant row.
 */
@Component
public class CompetitorCorpus {

    private static final List<Document> DOCUMENTS = List.of(
            new Document("doc-01", "Kuda Q2 pricing update",
                    "Kuda reduced transaction pricing by 8 percent across peer-to-peer "
                            + "transfers in Q2, citing lower settlement costs after moving "
                            + "volume onto NIBSS instant payments."),
            new Document("doc-02", "Flutterwave merchant fees",
                    "Flutterwave held merchant discount rates flat at 1.4 percent but "
                            + "introduced a tiered pricing band for merchants processing "
                            + "over 50 million naira monthly."),
            new Document("doc-03", "Paystack settlement times",
                    "Paystack moved to same-day settlement for Nigerian merchants, down "
                            + "from T+1. No change to headline pricing was announced."),
            new Document("doc-04", "Chipper Cash expansion",
                    "Chipper Cash expanded card issuing into Kenya and Uganda, positioning "
                            + "against incumbent mobile money rather than on price."),
            new Document("doc-05", "M-Pesa tariff revision",
                    "Safaricom revised M-Pesa tariffs downward for low-value transfers "
                            + "under 100 shillings, absorbing the excise duty increase."),
            new Document("doc-06", "OPay agent network",
                    "OPay grew its agent network past 500,000 points, subsidising agent "
                            + "commissions to hold transaction pricing below competitors."),
            new Document("doc-07", "Wave Senegal pricing",
                    "Wave sustained a flat 1 percent transfer fee in Senegal, roughly a "
                            + "third of incumbent pricing, funded by a lean agent model."),
            new Document("doc-08", "Moniepoint POS volumes",
                    "Moniepoint reported POS transaction volumes up 40 percent "
                            + "year-on-year, with terminal deployment as the growth driver."),
            new Document("doc-09", "PalmPay funding round",
                    "PalmPay raised a Series B extension to fund credit products; the "
                            + "filing does not disclose changes to transfer pricing."),
            new Document("doc-10", "Interswitch regulatory filing",
                    "Interswitch disclosed a CBN review of switching fees that may compress "
                            + "industry-wide transaction pricing over the next two quarters."));

    /**
     * Keyword search, scored by how many distinct query terms a document
     * contains.
     *
     * <p>Not pretending to be semantic: the semantic story in this project
     * belongs to CockroachDB's vector index over <em>memory</em>, and a second
     * half-hearted ranker over source documents would only blur what is being
     * demonstrated. This is a tool an agent calls; the interesting retrieval
     * happens on what agents choose to remember.
     */
    public List<Document> search(String query, int limit) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        return DOCUMENTS.stream()
                .map((document) -> new Match(document, document.score(terms)))
                .filter((match) -> match.score() > 0)
                // Ties break on document id so repeated runs return an identical
                // ordering - a demo that reorders between takes looks broken.
                .sorted(Comparator.comparingInt(Match::score).reversed()
                        .thenComparing((match) -> match.document().id()))
                .limit(limit)
                .map(Match::document)
                .toList();
    }

    public Optional<Document> byId(String id) {
        return DOCUMENTS.stream().filter((document) -> document.id().equals(id)).findFirst();
    }

    public int size() {
        return DOCUMENTS.size();
    }

    private static List<String> terms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return List.of(query.toLowerCase(Locale.ROOT).split("\\W+")).stream()
                .filter((term) -> term.length() > 2)
                .distinct()
                .toList();
    }

    public record Document(String id, String title, String body) {

        int score(List<String> terms) {
            String haystack = (this.title + " " + this.body).toLowerCase(Locale.ROOT);
            return (int) terms.stream().filter(haystack::contains).count();
        }

        /** How a document is shown to the model, and cited as evidence. */
        public String asContext() {
            return this.id + " | " + this.title + " | " + this.body;
        }
    }

    private record Match(Document document, int score) {
    }
}
