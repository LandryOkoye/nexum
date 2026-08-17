package com.nexum.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.nexum.event.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events, served <em>from</em> the events table rather than instead
 * of it.
 *
 * <p>Pushing events straight from the code that produced them would be less
 * work, and would lose the two properties that matter here. A browser that
 * reconnects mid-demo could not catch up, so a dropped connection during the
 * recovery sequence would silently cost exactly the moment worth watching. And
 * the timeline on screen would be a different artefact from the audit trail in
 * the database, leaving no way to show that what a judge watched is what was
 * actually recorded.
 *
 * <p>Reading from the table means every subscriber is just a cursor over durable
 * rows. Reconnecting with {@code Last-Event-ID} replays from that point, and the
 * screen and the audit trail cannot disagree because they are the same data.
 *
 * <p>A poll rather than a listener: the spec's non-goals rule out a broker, the
 * demo has a handful of subscribers, and a 500ms cursor query on an indexed
 * table is not a problem worth solving with infrastructure.
 */
@Component
class EventStream {

    private static final Logger log = LoggerFactory.getLogger(EventStream.class);

    private static final int BATCH = 200;

    /** Long enough to outlast a demo; the browser reconnects and replays anyway. */
    private static final long TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final EventLog events;

    private final Map<SseEmitter, Cursor> subscribers = new ConcurrentHashMap<>();

    EventStream(EventLog events) {
        this.events = events;
    }

    /**
     * @param afterSeq replay point; events strictly after this are delivered.
     *        Sequence numbers from {@code SERIAL} are monotonic but not gapless,
     *        so this is always "greater than what I last saw" and never
     *        "the next number".
     */
    SseEmitter subscribe(UUID goalId, long afterSeq) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        this.subscribers.put(emitter, new Cursor(goalId, afterSeq));

        emitter.onCompletion(() -> this.subscribers.remove(emitter));
        emitter.onTimeout(() -> {
            this.subscribers.remove(emitter);
            emitter.complete();
        });
        emitter.onError((throwable) -> this.subscribers.remove(emitter));

        try {
            // An immediate comment flushes headers, so the browser's
            // EventSource opens rather than sitting in "connecting" until the
            // first real event - which on a quiet goal could be a long time.
            emitter.send(SseEmitter.event().comment("connected"));
        }
        catch (IOException ex) {
            this.subscribers.remove(emitter);
            emitter.completeWithError(ex);
        }

        return emitter;
    }

    @Scheduled(fixedDelayString = "${nexum.events.poll-ms:500}")
    void push() {
        if (this.subscribers.isEmpty()) {
            return;
        }

        this.subscribers.forEach((emitter, cursor) -> {
            try {
                List<EventLog.Event> batch =
                        this.events.since(cursor.goalId(), cursor.seq(), BATCH);

                for (EventLog.Event event : batch) {
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(event.seq()))
                            .name(event.type())
                            .data(payload(event)));
                    cursor.advanceTo(event.seq());
                }
            }
            catch (IOException | IllegalStateException ex) {
                // The client went away, or the response is already closed.
                // Normal, and not worth a stack trace.
                this.subscribers.remove(emitter);
                emitter.complete();
            }
            catch (RuntimeException ex) {
                log.warn("Dropping SSE subscriber after an unexpected failure", ex);
                this.subscribers.remove(emitter);
                emitter.completeWithError(ex);
            }
        });
    }

    /**
     * Wraps the stored payload with its envelope. The payload column is already
     * JSON, so it is spliced in rather than re-encoded - re-serialising it would
     * turn the object into a quoted string.
     */
    private static String payload(EventLog.Event event) {
        return """
                {"seq":%d,"type":"%s","goalId":%s,"at":"%s","payload":%s}"""
                .formatted(event.seq(), event.type(),
                        (event.goalId() != null) ? "\"" + event.goalId() + "\"" : "null",
                        event.createdAt(),
                        (event.payload() != null && !event.payload().isBlank())
                                ? event.payload() : "{}");
    }

    int subscriberCount() {
        return this.subscribers.size();
    }

    /** One subscriber's replay position. */
    private static final class Cursor {

        private final UUID goalId;

        private final AtomicLong seq;

        Cursor(UUID goalId, long seq) {
            this.goalId = goalId;
            this.seq = new AtomicLong(seq);
        }

        UUID goalId() {
            return this.goalId;
        }

        long seq() {
            return this.seq.get();
        }

        void advanceTo(long delivered) {
            this.seq.accumulateAndGet(delivered, Math::max);
        }
    }
}
