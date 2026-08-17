package com.nexum.api;

import java.util.List;
import java.util.UUID;

import com.nexum.event.EventLog;
import com.nexum.goal.GoalService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The event timeline for a goal, as a page or as a live stream.
 *
 * <p>Both read the same table. The recovery sequence - AGENT_FAILED,
 * TASK_ORPHANED, AGENT_REJOINED_GOAL, CHECKPOINT_RESTORED, TASK_RESUMED - is
 * what a viewer is here to see, and it is durable, so it can be watched live or
 * read back afterwards with identical results.
 */
@RestController
@RequestMapping("/api/goals/{goalId}/events")
class EventController {

    private final EventLog events;
    private final EventStream stream;
    private final GoalService goals;

    EventController(EventLog events, EventStream stream, GoalService goals) {
        this.events = events;
        this.stream = stream;
        this.goals = goals;
    }

    @GetMapping
    List<EventLog.Event> list(@PathVariable UUID goalId,
            @RequestParam(required = false, defaultValue = "0") long after,
            @RequestParam(required = false, defaultValue = "200") int limit) {

        this.goals.requireGoal(goalId);
        return this.events.since(goalId, after, Math.clamp(limit, 1, 500));
    }

    /**
     * Live stream.
     *
     * <p>Honours {@code Last-Event-ID}, which browsers resend automatically on
     * reconnect - so a dropped connection mid-demo resumes exactly where it left
     * off instead of losing the part worth watching. An explicit {@code after}
     * wins over the header, for replaying a goal from the beginning.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable UUID goalId,
            @RequestParam(required = false) Long after,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {

        this.goals.requireGoal(goalId);

        long from = (after != null) ? after : (lastEventId != null) ? lastEventId : 0L;
        return this.stream.subscribe(goalId, from);
    }
}
