package com.nexum.event;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Append-only event log.
 *
 * <p>SSE is served <em>from</em> this table rather than instead of it. That
 * gives replay after a browser reconnect, the audit trail the spec asks for,
 * and a dashboard that survives a refresh mid-demo - all from one table.
 *
 * <p>Appends are deliberately failure-tolerant: losing an event must never roll
 * back the state transition that produced it.
 */
@Component
public class EventLog {

    private static final Logger log = LoggerFactory.getLogger(EventLog.class);

    private final JdbcTemplate jdbc;

    public EventLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID goalId, EventType type, String payloadJson) {
        try {
            this.jdbc.update("""
                    INSERT INTO events (goal_id, event_type, payload)
                    VALUES (?, ?, ?::JSONB)
                    """, goalId, type.name(), (payloadJson != null) ? payloadJson : "{}");
        }
        catch (RuntimeException ex) {
            // Observability must not be able to break the thing it observes.
            log.warn("Failed to append {} event for goal {}", type, goalId, ex);
        }
    }

    public List<Event> since(UUID goalId, long afterSeq, int limit) {
        return this.jdbc.query("""
                SELECT seq, goal_id, event_type, payload::TEXT AS payload, created_at
                FROM events
                WHERE goal_id = ? AND seq > ?
                ORDER BY seq
                LIMIT ?
                """,
                (rs, rowNum) -> new Event(
                        rs.getLong("seq"),
                        rs.getObject("goal_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()),
                goalId, afterSeq, limit);
    }

    public record Event(long seq, UUID goalId, String type, String payload,
            java.time.Instant createdAt) {
    }
}
