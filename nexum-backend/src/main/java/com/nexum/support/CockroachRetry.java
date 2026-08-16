package com.nexum.support;

import java.sql.SQLException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Retries CockroachDB serialization failures (SQLSTATE 40001).
 *
 * <p>CockroachDB runs SERIALIZABLE by default, so concurrent transactions that
 * conflict are aborted with a <em>retryable</em> error rather than being made to
 * wait. Spring's {@code @Transactional} does not retry these. Every Nexum flow
 * with real contention - task claims, concurrent goal-memory writes - must be
 * wrapped in this helper or it will fail intermittently under exactly the
 * conditions the demo creates.
 *
 * <p><strong>Placement matters.</strong> This must wrap the <em>outside</em> of a
 * transaction boundary, never the inside: once a transaction is marked
 * rollback-only, retrying within it is pointless. Call a {@code @Transactional}
 * method from here, not the reverse.
 *
 * <pre>{@code
 * retry.execute("claim-task", () -> taskService.claim(taskId, runId));
 * }</pre>
 */
@Component
public class CockroachRetry {

    private static final Logger log = LoggerFactory.getLogger(CockroachRetry.class);

    /** PostgreSQL/CockroachDB SQLSTATE for serialization failure. */
    private static final String SERIALIZATION_FAILURE = "40001";

    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MILLIS = 25L;

    public <T> T execute(String operation, Supplier<T> action) {
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            }
            catch (RuntimeException ex) {
                if (!isRetryable(ex)) {
                    throw ex;
                }
                lastFailure = ex;
                log.warn("Serialization failure on '{}' (attempt {}/{}), retrying",
                        operation, attempt, MAX_ATTEMPTS);
                backoff(attempt);
            }
        }

        throw new IllegalStateException(
                "Operation '" + operation + "' still failing after " + MAX_ATTEMPTS
                        + " serialization retries", lastFailure);
    }

    public void run(String operation, Runnable action) {
        execute(operation, () -> {
            action.run();
            return null;
        });
    }

    /** Walks the cause chain looking for SQLSTATE 40001. */
    public static boolean isRetryable(Throwable throwable) {
        Throwable cursor = throwable;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (cursor instanceof SQLException sqlException
                    && SERIALIZATION_FAILURE.equals(sqlException.getSQLState())) {
                return true;
            }
            if (cursor.getCause() == cursor) {
                break;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private void backoff(int attempt) {
        try {
            // Exponential with a small base; contention windows here are short.
            Thread.sleep(BASE_BACKOFF_MILLIS * (1L << (attempt - 1)));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", ex);
        }
    }
}
