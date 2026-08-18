package com.nexum.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Live web research, via Tavily.
 *
 * <p>This is what makes the collective's memory worth persisting. A goal whose
 * findings come from a fixture is a demonstration of plumbing; a goal whose
 * findings come from the real web - with real sources a reader can open and
 * check - is the thing the plumbing is for. It is also what makes agent death
 * expensive in the way this project claims: re-researching a question costs real
 * time and real API calls, so a replacement agent inheriting what the dead one
 * learned is a genuine saving rather than a staged one.
 *
 * <p>Tavily rather than a general search API because it returns extracted page
 * text, not a list of links. An agent given links would need a second fetch-and-
 * parse step per result, which is more moving parts, more latency inside a lease
 * window, and more ways to fail on camera.
 *
 * <p><strong>Retrieved sources are cached so citations can be verified.</strong>
 * {@link #byId} must only ever resolve something genuinely fetched - it is what
 * stops a model from inventing a plausible URL and having it accepted as
 * evidence. The cache is bounded and shared across agents on purpose: a citation
 * written by an agent that has since died still resolves for its replacement,
 * which is exactly the continuity the goal-scoped memory is meant to have.
 */
class WebResearchTool implements ResearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebResearchTool.class);

    private static final String ENDPOINT = "https://api.tavily.com/search";

    /**
     * How many retrieved sources stay resolvable.
     *
     * <p>Sized for a long mission rather than a single task: citations must
     * survive the agent that wrote them, so this holds far more than one run
     * could produce. Bounded all the same, because an unbounded map on a
     * long-running control plane is a memory leak with a schedule.
     */
    private static final int CACHE_LIMIT = 2000;

    /**
     * Page text is truncated before it reaches the model.
     *
     * <p>Five full articles will not fit in a prompt alongside recalled memory
     * and the task, and the part of a search result that answers a question is
     * almost always near the top. Truncating here rather than letting the
     * provider decide keeps the prompt budget predictable.
     */
    private static final int MAX_BODY_CHARS = 1200;

    private final RestClient http;
    private final String apiKey;
    private final Map<String, Source> retrieved;

    WebResearchTool(RestClient.Builder builder, String apiKey, Duration timeout) {
        this.apiKey = apiKey;

        // An HTTP call with no timeout would block an agent worker indefinitely
        // while its heartbeat cheerfully renews the lease, so the task never
        // becomes reclaimable and the run never ends. The reaper cannot help: the
        // agent is not dead, just permanently stuck. Bounded here so the failure
        // becomes an ordinary empty search result instead.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        this.http = builder.requestFactory(factory).baseUrl(ENDPOINT).build();
        this.retrieved = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Source> eldest) {
                return size() > CACHE_LIMIT;
            }
        });
    }

    @Override
    public String name() {
        return "search_web";
    }

    @Override
    public String describe() {
        return "Live web search — Tavily";
    }

    @Override
    public List<Source> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            TavilyResponse response = this.http.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + this.apiKey)
                    .body(new TavilyRequest(query, Math.clamp(limit, 1, 10), "basic"))
                    .retrieve()
                    .body(TavilyResponse.class);

            if (response == null || response.results() == null) {
                log.warn("Tavily returned no results object for query [{}]", query);
                return List.of();
            }

            List<Source> sources = new ArrayList<>();
            for (TavilyResult result : response.results()) {
                if (result.url() == null || result.content() == null) {
                    continue;
                }
                Source source = new Source(idFor(result.url()),
                        (result.title() != null) ? result.title() : result.url(),
                        truncate(result.content()), result.url());
                this.retrieved.put(source.id(), source);
                sources.add(source);
            }
            return sources;
        }
        catch (RuntimeException ex) {
            // Contract of ResearchTool.search: never throw. A search provider
            // outage must degrade the agent, not kill a run that holds a lease -
            // that would manufacture exactly the kind of failure the reaper is
            // meant to detect for real reasons.
            log.warn("Web search failed for query [{}]: {}", query, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public Optional<Source> byId(String id) {
        return Optional.ofNullable((id != null) ? this.retrieved.get(id) : null);
    }

    /**
     * A short, stable token derived from the URL.
     *
     * <p>Derived rather than sequential so the same page keeps the same id
     * across searches, runs and agents: a citation recorded in memory yesterday
     * still resolves today. Short because the model has to reproduce it exactly,
     * and a long identifier is one it will eventually mistype.
     */
    private static String idFor(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            return "web-" + HexFormat.of().formatHex(digest, 0, 4);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
    }

    private static String truncate(String body) {
        String collapsed = body.replaceAll("\\s+", " ").strip();
        return (collapsed.length() <= MAX_BODY_CHARS)
                ? collapsed : collapsed.substring(0, MAX_BODY_CHARS) + "…";
    }

    private record TavilyRequest(String query, int max_results, String search_depth) {
    }

    private record TavilyResponse(String query, String answer, List<TavilyResult> results) {
    }

    private record TavilyResult(String title, String url, String content, Double score) {
    }
}
