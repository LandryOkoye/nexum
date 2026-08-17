package com.nexum.support;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A token bucket, sized for a free-tier API.
 *
 * <p>Groq's free tier rate-limits per minute, and Nexum runs several agents at
 * once by design - so the moment the demo does the interesting thing, every
 * worker calls the model simultaneously and some of them get a 429. Rather than
 * discovering that while recording, requests queue here and are paced.
 *
 * <p>Blocking rather than rejecting is the right shape for this caller: an agent
 * that waits 200ms for a permit is fine, an agent whose step fails because a
 * sibling was faster is a lost task. Callers are on virtual threads, so blocking
 * costs a park rather than a platform thread.
 */
public class TokenBucket {

    private final ReentrantLock lock = new ReentrantLock(true);

    private final long capacity;
    private final long refillIntervalNanos;

    private long available;
    private long lastRefillNanos;

    /**
     * @param permitsPerMinute sustained rate; also the burst capacity, so a
     *        quiet period lets a fleet of agents start together
     */
    public TokenBucket(long permitsPerMinute) {
        if (permitsPerMinute <= 0) {
            throw new IllegalArgumentException("permitsPerMinute must be positive");
        }
        this.capacity = permitsPerMinute;
        this.available = permitsPerMinute;
        this.refillIntervalNanos = TimeUnit.MINUTES.toNanos(1) / permitsPerMinute;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Waits until a permit is available, then consumes it.
     *
     * <p>The fair lock matters: without it a busy agent can repeatedly win the
     * lock and starve another, which in this system means one task making no
     * progress until its lease lapses and it gets reaped - a failure that would
     * look like a bug in the recovery logic rather than in the rate limiter.
     */
    public void acquire() throws InterruptedException {
        long waitNanos;

        this.lock.lock();
        try {
            refill();
            if (this.available >= 1) {
                this.available--;
                return;
            }
            waitNanos = this.refillIntervalNanos - (System.nanoTime() - this.lastRefillNanos);
            this.available--;
            this.lastRefillNanos += this.refillIntervalNanos;
        }
        finally {
            this.lock.unlock();
        }

        if (waitNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - this.lastRefillNanos;
        if (elapsed < this.refillIntervalNanos) {
            return;
        }
        long earned = elapsed / this.refillIntervalNanos;
        this.available = Math.min(this.capacity, this.available + earned);
        this.lastRefillNanos += earned * this.refillIntervalNanos;
    }
}
