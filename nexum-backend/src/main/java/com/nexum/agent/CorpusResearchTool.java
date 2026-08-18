package com.nexum.agent;

import java.util.List;
import java.util.Optional;

/**
 * The offline research tool: a fixed document set, keyword-matched.
 *
 * <p>Kept deliberately after live web research became the default. It is the
 * answer to two situations that are otherwise unrecoverable - a venue with
 * hostile wifi, and a search provider that changes its API or rate-limits an
 * account on the morning of a deadline. The recovery sequence is what this
 * project is actually arguing for, and it must stay demonstrable without a
 * network.
 *
 * <p>It is also the fixture the agent tests run against, which is why its
 * results are deterministic: a test whose assertions depend on what the live
 * internet said this morning is not a test.
 */
class CorpusResearchTool implements ResearchTool {

    private final CompetitorCorpus corpus;

    CorpusResearchTool(CompetitorCorpus corpus) {
        this.corpus = corpus;
    }

    @Override
    public String name() {
        return "search_corpus";
    }

    @Override
    public String describe() {
        return "Offline corpus — " + this.corpus.size() + " fixed documents";
    }

    @Override
    public List<Source> search(String query, int limit) {
        return this.corpus.search(query, limit).stream()
                .map(CorpusResearchTool::toSource)
                .toList();
    }

    @Override
    public Optional<Source> byId(String id) {
        return this.corpus.byId(id).map(CorpusResearchTool::toSource);
    }

    /** No URL: these documents are synthetic and there is nowhere to send a reader. */
    private static Source toSource(CompetitorCorpus.Document document) {
        return new Source(document.id(), document.title(), document.body(), null);
    }
}
