package com.nexum.skeleton;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.nexum.support.CockroachRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Walking skeleton: proves every layer of the Nexum stack works together
 * <em>before</em> any domain code depends on it.
 *
 * <p>Run it with:
 * <pre>{@code
 * ./gradlew :nexum-backend:bootRun --args='--spring.profiles.active=local,skeleton'
 * }</pre>
 *
 * <p>Each check targets a specific assumption that would be expensive to
 * discover was wrong on day three. Delete this package once the real domain
 * layer is in place.
 */
@Component
@Profile("skeleton")
public class SkeletonProbe implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SkeletonProbe.class);

    private static final int EXPECTED_DIMENSIONS = 1024;
    private static final int CLAIM_CONTENDERS = 8;

    private final ConfigurableApplicationContext context;
    private final ProbeRecordRepository records;
    private final VectorProbe vectors;
    private final CockroachRetry retry;
    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final ObjectProvider<ChatClient.Builder> chatClients;

    public SkeletonProbe(ConfigurableApplicationContext context, ProbeRecordRepository records,
            VectorProbe vectors, CockroachRetry retry,
            ObjectProvider<EmbeddingModel> embeddingModels,
            ObjectProvider<ChatClient.Builder> chatClients) {
        this.context = context;
        this.records = records;
        this.vectors = vectors;
        this.retry = retry;
        this.embeddingModels = embeddingModels;
        this.chatClients = chatClients;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Check> results = new ArrayList<>();

        results.add(check("cockroach reachable", this::checkCluster));
        results.add(check("flyway migrations applied", this::checkMigrations));
        results.add(check("jpa insert/read via CockroachDialect", this::checkJpa));
        results.add(check("conditional claim: exactly one winner", this::checkClaimRace));

        EmbeddingModel embeddingModel = this.embeddingModels.getIfAvailable();
        float[] pricingVector = null;
        if (embeddingModel == null) {
            results.add(Check.skipped("embedding provider", "no EmbeddingModel bean configured"));
            results.add(Check.skipped("vector write", "requires embeddings"));
            results.add(Check.skipped("vector similarity search", "requires embeddings"));
        }
        else {
            Check embedCheck = check("embedding provider", () -> checkEmbeddings(embeddingModel));
            results.add(embedCheck);
            if (embedCheck.ok()) {
                pricingVector = embeddingModel.embed("competitor pricing changes");
                results.add(check("vector write", () -> checkVectorWrite(embeddingModel)));
                float[] query = pricingVector;
                results.add(check("vector similarity search", () -> checkVectorSearch(query)));
            }
        }

        ChatClient.Builder chatBuilder = this.chatClients.getIfAvailable();
        if (chatBuilder == null) {
            results.add(Check.skipped("groq reasoning", "no ChatClient.Builder bean configured"));
        }
        else {
            results.add(check("groq reasoning", () -> checkChat(chatBuilder)));
        }

        report(results);

        boolean allPassed = results.stream().noneMatch(Check::failed);
        System.exit(SpringApplication.exit(this.context, () -> allPassed ? 0 : 1));
    }

    // --- checks ----------------------------------------------------------

    private String checkCluster() {
        String version = this.vectors.serverVersion();
        if (version == null || !version.toLowerCase().contains("cockroach")) {
            throw new IllegalStateException(
                    "connected to something that is not CockroachDB: " + version);
        }
        return version.split("\\(")[0].trim();
    }

    private String checkMigrations() {
        int applied = this.vectors.countMigrations();
        if (applied < 2) {
            throw new IllegalStateException("expected >= 2 successful migrations, found " + applied);
        }
        return applied + " migrations applied";
    }

    private String checkJpa() {
        UUID id = UUID.randomUUID();
        this.records.save(new ProbeRecord(id, "jpa-roundtrip"));
        ProbeRecord loaded = this.records.findById(id)
                .orElseThrow(() -> new IllegalStateException("row did not persist"));
        if (loaded.getCreatedAt() == null) {
            throw new IllegalStateException("created_at default was not read back");
        }
        return "round-tripped " + loaded.getLabel();
    }

    /**
     * Invariant 1 rehearsal: many contenders, one row, exactly one winner.
     * This is the shape the real task claim takes - a conditional UPDATE whose
     * affected-row count is the authority, wrapped in serialization retry.
     */
    private String checkClaimRace() throws Exception {
        UUID id = UUID.randomUUID();
        this.records.save(new ProbeRecord(id, "claim-race"));

        AtomicInteger winners = new AtomicInteger();
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CLAIM_CONTENDERS; i++) {
                String claimant = "run-" + i;
                futures.add(pool.submit(() -> {
                    startGun.await();
                    boolean won = this.retry.execute("claim-race",
                            () -> this.vectors.tryClaim(id, claimant));
                    if (won) {
                        winners.incrementAndGet();
                    }
                    return null;
                }));
            }
            startGun.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        int won = winners.get();
        if (won != 1) {
            throw new IllegalStateException(
                    "expected exactly 1 winner across " + CLAIM_CONTENDERS + " contenders, got " + won);
        }
        return "1 winner / " + CLAIM_CONTENDERS + " contenders";
    }

    private String checkEmbeddings(EmbeddingModel embeddingModel) {
        float[] embedding = embeddingModel.embed("nexum walking skeleton");
        if (embedding.length != EXPECTED_DIMENSIONS) {
            throw new IllegalStateException("embedding provider returned " + embedding.length
                    + " dimensions but the schema declares VECTOR(" + EXPECTED_DIMENSIONS + "). "
                    + "Change the model or the schema - they must agree.");
        }
        return embedding.length + " dimensions";
    }

    private String checkVectorWrite(EmbeddingModel embeddingModel) {
        // Three memories: two about competitor pricing, one unrelated.
        record Sample(String label, String text) {
        }
        List<Sample> samples = List.of(
                new Sample("pricing-cut", "Competitor X reduced transaction pricing by 8 percent."),
                new Sample("pricing-raise", "Competitor Y raised its monthly subscription fees."),
                new Sample("unrelated", "The office coffee machine was replaced on Tuesday."));

        for (Sample sample : samples) {
            this.vectors.insert(UUID.randomUUID(), sample.label(), embeddingModel.embed(sample.text()));
        }
        return samples.size() + " vectors written";
    }

    private String checkVectorSearch(float[] query) {
        List<VectorProbe.Neighbour> neighbours = this.vectors.nearest(query, 3);
        if (neighbours.isEmpty()) {
            throw new IllegalStateException("similarity search returned nothing");
        }
        String closest = neighbours.getFirst().label();
        if (!closest.startsWith("pricing")) {
            throw new IllegalStateException("semantic ranking looks wrong - nearest neighbour to "
                    + "'competitor pricing changes' was '" + closest + "'");
        }
        StringBuilder detail = new StringBuilder("nearest=" + closest);
        for (VectorProbe.Neighbour neighbour : neighbours) {
            detail.append(String.format("  [%s %.4f]", neighbour.label(), neighbour.distance()));
        }
        return detail.toString();
    }

    private String checkChat(ChatClient.Builder builder) {
        String reply = builder.build().prompt()
                .user("Reply with exactly one word: NEXUM")
                .call()
                .content();
        if (reply == null || reply.isBlank()) {
            throw new IllegalStateException("model returned an empty response");
        }
        return "responded: " + reply.strip().replaceAll("\\s+", " ");
    }

    // --- plumbing --------------------------------------------------------

    private Check check(String name, ThrowingSupplier action) {
        long startedAt = System.nanoTime();
        try {
            String detail = action.get();
            return new Check(name, Status.PASS, detail, elapsedMillis(startedAt));
        }
        catch (Exception ex) {
            log.debug("check '{}' failed", name, ex);
            String message = (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName();
            return new Check(name, Status.FAIL, message, elapsedMillis(startedAt));
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private void report(List<Check> results) {
        StringBuilder out = new StringBuilder("\n");
        out.append("=".repeat(78)).append("\n");
        out.append("  NEXUM WALKING SKELETON\n");
        out.append("=".repeat(78)).append("\n");
        for (Check result : results) {
            out.append(String.format("  %-6s %-42s %6s  %s%n",
                    result.status(), result.name(),
                    (result.status() == Status.SKIP) ? "-" : result.millis() + "ms",
                    result.detail()));
        }
        out.append("=".repeat(78)).append("\n");
        long passed = results.stream().filter(Check::ok).count();
        long failed = results.stream().filter(Check::failed).count();
        long skipped = results.stream().filter((check) -> check.status() == Status.SKIP).count();
        out.append(String.format("  %d passed, %d failed, %d skipped%n", passed, failed, skipped));
        if (failed == 0) {
            out.append("  Stack verified. Safe to build the domain layer on it.\n");
        }
        else {
            out.append("  Stack NOT verified. Fix the above before writing domain code.\n");
        }
        out.append("=".repeat(78));
        log.info(out.toString());
    }

    @FunctionalInterface
    private interface ThrowingSupplier {

        String get() throws Exception;

    }

    private enum Status {

        PASS, FAIL, SKIP

    }

    private record Check(String name, Status status, String detail, long millis) {

        static Check skipped(String name, String reason) {
            return new Check(name, Status.SKIP, reason, 0L);
        }

        boolean ok() {
            return this.status == Status.PASS;
        }

        boolean failed() {
            return this.status == Status.FAIL;
        }

    }
}
