package com.nexum.coordination;

import java.util.UUID;

/** A task successfully claimed by a run, with the lease already held. */
public record TaskClaim(UUID taskId, UUID goalId, String title, String description,
        int maxSteps, int attemptCount) {
}
