package com.nexum.event;

/**
 * Every durable state transition worth showing a judge.
 *
 * <p>The recovery sequence - AGENT_FAILED, TASK_ORPHANED, AGENT_REJOINED_GOAL,
 * CHECKPOINT_RESTORED, TASK_RESUMED - is the demo's spine. Those five events in
 * order, with timestamps, ARE the proof that the collective survived.
 */
public enum EventType {

    GOAL_CREATED,
    AGENT_JOINED_GOAL,
    AGENT_REJOINED_GOAL,
    TASK_CREATED,
    TASK_CLAIMED,
    TASK_RESUMED,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_ORPHANED,
    RUN_STARTED,
    RUN_HEARTBEAT_LOST,
    AGENT_FAILED,
    RECOVERY_STARTED,
    CHECKPOINT_SAVED,
    CHECKPOINT_RESTORED,
    MEMORY_CREATED,
    MEMORY_RETRIEVED,
    MEMORY_PROMOTED,
    DECISION_CREATED,
    TOOL_CALLED

}
