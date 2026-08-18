package com.nexum.inspect;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nexum.memory.MemoryAccessPolicy;
import com.nexum.memory.MemoryRepository;
import com.nexum.memory.QueryEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Evidence that the claims this project makes about CockroachDB are true.
 *
 * <p>Every other part of Nexum <em>asserts</em> that scope is enforced by the
 * storage engine before similarity is computed, and that retrieval is served by
 * a distributed vector index rather than a scan. Those are the load-bearing
 * claims, and a reader has no way to check either one from the outside: a
 * retrieval endpoint returns the same JSON whether the rows came from a vector
 * index or a full table scan with a sort bolted on. This class closes that gap
 * by asking the database to describe its own execution and handing the answer
 * back verbatim.
 *
 * <p><strong>It explains the statement the application actually runs.</strong>
 * The SQL comes from {@link MemoryRepository#semanticSql} and the predicates
 * from {@link MemoryAccessPolicy}, so there is no second query written for the
 * benefit of the dashboard. If the retrieval path changes, the plan shown here
 * changes with it - including changing to something unflattering, which is the
 * only way this is worth anything.
 *
 * <p><strong>This is not a SQL tool.</strong> It accepts no statement, fragment,
 * or identifier from a caller: an agent id, a goal id, and a search string are
 * the entire input surface, and each is bound as a parameter. The statements are
 * fixed constants in this file. Invariant 10 forbids giving agents general SQL,
 * and an "inspection" endpoint that took a query string would be exactly that
 * with a friendlier name.
 *
 * <p>{@code EXPLAIN ANALYZE} rather than {@code EXPLAIN}: the estimated plan is
 * a prediction, and predictions are what this class exists to test. Running it
 * reports the index that was really used, the rows really read, and the time
 * really spent. The cost is executing the read twice, which is acceptable on an
 * operator endpoint that no agent calls.
 */
@Service
public class CockroachInspector {

    private static final Logger log = LoggerFactory.getLogger(CockroachInspector.class);

    /** Matches {@code table: memories@memories_goal_scope_embedding_idx}. */
    private static final Pattern TABLE = Pattern.compile("table:\\s*(\\S+)");

    /** Matches both {@code prefix spans:} (vector search) and plain {@code spans:}. */
    private static final Pattern SPANS = Pattern.compile("(?:prefix )?spans:\\s*(.+)");

    private static final Pattern EXECUTION_TIME = Pattern.compile("execution time:\\s*(\\S+)");

    /**
     * The plan node CockroachDB emits when it serves a query from a vector
     * index. Its presence is the whole proof; its absence means the optimiser
     * chose a scan, and the dashboard says so rather than hiding it.
     */
    private static final String VECTOR_SEARCH_NODE = "• vector search";

    private final JdbcTemplate jdbc;
    private final MemoryAccessPolicy policy;
    private final QueryEmbedder embedder;

    public CockroachInspector(JdbcTemplate jdbc, MemoryAccessPolicy policy,
            QueryEmbedder embedder) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.embedder = embedder;
    }

    /**
     * What the application is actually connected to.
     *
     * <p>Worth surfacing because the wire protocol is PostgreSQL's, so nothing a
     * viewer can otherwise see distinguishes this from a Postgres deployment.
     * The version string does, and the vector index definitions could not exist
     * on Postgres at all.
     */
    public Cluster cluster() {
        String version = this.jdbc.queryForObject("SELECT version()", String.class);
        String isolation = this.jdbc.queryForObject("SHOW default_transaction_isolation",
                String.class);

        // create_statement is the authoritative DDL. Parsing it for VECTOR INDEX
        // lines beats reconstructing them from SHOW INDEXES, which reports vector
        // indexes as ordinary multi-column ones and loses vector_cosine_ops.
        String ddl = this.jdbc.queryForObject(
                "SELECT create_statement FROM [SHOW CREATE TABLE memories]", String.class);

        List<VectorIndex> indexes = new ArrayList<>();
        for (String line : (ddl != null) ? ddl.split("\n") : new String[0]) {
            String trimmed = line.strip().replaceAll(",$", "");
            if (trimmed.startsWith("VECTOR INDEX")) {
                indexes.add(new VectorIndex(
                        trimmed.replaceFirst("VECTOR INDEX (\\S+).*", "$1"), trimmed));
            }
        }

        return new Cluster(version, isolation, indexes);
    }

    /**
     * Runs the real retrieval for one agent and reports how CockroachDB served
     * it, one plan per grant.
     *
     * <p>One plan per grant rather than one for the whole recall, because that is
     * how retrieval genuinely executes - the split into separate indexed searches
     * is the design decision that keeps scope on the index prefix, and collapsing
     * the report back into a single plan would hide precisely what it is meant to
     * show.
     *
     * <p>A non-member yields no grants and therefore no plans. That is not an
     * empty result to apologise for: it is the access policy denying a query
     * before it reaches the memory table, which is worth seeing.
     */
    public RecallPlan explainRecall(UUID agentId, UUID goalId, String query, int limit) {
        Embedded embedded = embed(query);
        List<Plan> plans = new ArrayList<>();

        for (MemoryAccessPolicy.Grant grant : this.policy.grantsWithin(agentId, goalId)) {
            List<Object> args = new ArrayList<>();
            args.add(MemoryRepository.toLiteral(embedded.vector()));
            args.addAll(grant.parameters());
            args.add(limit);

            String sql = MemoryRepository.semanticSql(grant.sql());
            plans.add(explain(grant.sql(), sql, args));
        }

        return new RecallPlan(embedded.source(), embedded.vector().length, plans);
    }

    private Plan explain(String predicate, String sql, List<Object> args) {
        List<String> lines;
        try {
            lines = this.jdbc.queryForList("EXPLAIN ANALYZE " + inline(sql, args),
                    String.class);
        }
        catch (RuntimeException ex) {
            // An inspection endpoint must never be able to break the demo it is
            // inspecting. Report the failure in place of a plan and move on.
            log.warn("Could not explain retrieval for predicate [{}]", predicate, ex);
            return new Plan(predicate, List.of("EXPLAIN failed: " + ex.getMessage()),
                    null, false, null, null);
        }

        String index = null;
        String spans = null;
        String executionTime = null;
        boolean vectorSearch = false;

        for (String line : lines) {
            if (line.contains(VECTOR_SEARCH_NODE)) {
                vectorSearch = true;
            }
            // The plan names several tables - the vector index and then the
            // primary-key lookup that fetches the rows. The first is the access
            // path being demonstrated; the join back is plumbing.
            Matcher table = TABLE.matcher(line);
            if (index == null && table.find() && !table.group(1).endsWith("_pkey")) {
                index = table.group(1);
            }
            Matcher span = SPANS.matcher(line);
            if (spans == null && span.find()) {
                spans = span.group(1).strip().replaceAll("\"$", "");
            }
            Matcher time = EXECUTION_TIME.matcher(line);
            if (time.find()) {
                // Last one wins: the outermost node's time is the total, and the
                // per-node times printed above it are components of it.
                executionTime = time.group(1);
            }
        }

        return new Plan(predicate, lines, index, vectorSearch, spans, executionTime);
    }

    /**
     * Substitutes bound parameters into the statement as literals, because
     * {@code EXPLAIN} rejects placeholders: CockroachDB answers {@code EXPLAIN
     * does not support placeholders}, and there is no prepared-statement form
     * that avoids it - {@code EXPLAIN ANALYZE EXECUTE} needs literal arguments
     * too.
     *
     * <p>String-building SQL is exactly the practice this codebase avoids
     * everywhere else, so the safety here does not rest on the values happening
     * to be harmless today. {@link #literal} accepts three proven-safe shapes and
     * <em>throws</em> on anything else, which means a parameter type added later
     * fails loudly at this boundary instead of quietly becoming an injection
     * point. Note also what is not on the list: the caller's search text never
     * reaches SQL at all - it is turned into a vector long before this point.
     */
    private static String inline(String sql, List<Object> args) {
        StringBuilder out = new StringBuilder(sql.length() + 64);
        int next = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '?') {
                if (next >= args.size()) {
                    throw new IllegalStateException(
                            "More placeholders than parameters in: " + sql);
                }
                out.append(literal(args.get(next++)));
            }
            else {
                out.append(ch);
            }
        }
        if (next != args.size()) {
            throw new IllegalStateException("Unused parameters for: " + sql);
        }
        return out.toString();
    }

    /** Digits, signs, exponents and separators - the output of {@code toLiteral}. */
    private static final Pattern VECTOR_LITERAL = Pattern.compile("\\[[-0-9.,eE]*]");

    private static String literal(Object value) {
        // A UUID's toString is hex and hyphens by construction - it cannot carry
        // a quote, so it cannot escape the one it is wrapped in.
        if (value instanceof UUID uuid) {
            return "'" + uuid + "'";
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        if (value instanceof String text && VECTOR_LITERAL.matcher(text).matches()) {
            return "'" + text + "'";
        }
        throw new IllegalArgumentException(
                "Refusing to inline a parameter of type "
                        + ((value != null) ? value.getClass().getName() : "null")
                        + "; extend literal() deliberately if this is genuinely safe");
    }

    /**
     * Embeds the query, or falls back to a probe vector - and says which.
     *
     * <p>The fallback exists because the plan is a property of the query's
     * <em>shape</em>, not of the values in the vector, so the index can be
     * demonstrated before any embedding provider is reachable. It is labelled
     * rather than hidden: presenting a plan built from a zero vector as though it
     * were a real search would be the same class of dishonesty as showing
     * recency-ordered rows and calling them semantic.
     */
    private Embedded embed(String query) {
        return this.embedder.embed(query)
                .map((vector) -> new Embedded(vector, "query embedding"))
                .orElseGet(() -> new Embedded(new float[1024],
                        "probe vector (no embedding available)"));
    }

    private record Embedded(float[] vector, String source) {
    }

    /**
     * @param version the {@code version()} string, which names CockroachDB and
     *        its release - the one unambiguous signal that this is not Postgres
     * @param isolation the default transaction isolation, {@code serializable} -
     *        the guarantee the atomic task claim depends on
     */
    public record Cluster(String version, String isolation, List<VectorIndex> vectorIndexes) {
    }

    public record VectorIndex(String name, String definition) {
    }

    /**
     * @param vectorSource whether the plan was built from a real query embedding
     *        or a probe vector
     * @param dimensions the vector width actually used, which must match the
     *        {@code VECTOR(1024)} column or the query would not run at all
     */
    public record RecallPlan(String vectorSource, int dimensions, List<Plan> plans) {
    }

    /**
     * @param predicate the access-policy fragment this search was scoped by,
     *        quoted from {@link MemoryAccessPolicy} rather than rewritten
     * @param vectorSearch whether CockroachDB served this from the vector index;
     *        false is a real answer, not an error
     * @param prefixSpans the index prefix the engine was constrained to - this is
     *        scope enforcement happening in the storage engine, made visible
     */
    public record Plan(String predicate, List<String> lines, String index,
            boolean vectorSearch, String prefixSpans, String executionTime) {
    }
}
