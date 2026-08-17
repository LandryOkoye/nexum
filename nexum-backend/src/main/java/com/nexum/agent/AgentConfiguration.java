package com.nexum.agent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two pools agent execution needs.
 */
@Configuration(proxyBeanMethods = false)
class AgentConfiguration {

    /**
     * Agent workers, one virtual thread each.
     *
     * <p>A worker spends nearly all its life blocked on the model or the
     * database, which is the case virtual threads exist for: concurrency is
     * capped deliberately by the dispatcher for resource reasons, not
     * accidentally by the size of a thread pool.
     */
    @Bean(destroyMethod = "close")
    ExecutorService agentWorkerExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Lease heartbeats.
     *
     * <p>Platform threads, and few of them: these are short, fixed-rate database
     * updates. Virtual threads buy nothing here, and a scheduled executor over
     * carriers is the better-understood option for something whose reliability
     * decides whether live agents get declared dead.
     */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean
    ScheduledExecutorService leaseHeartbeatScheduler() {
        return Executors.newScheduledThreadPool(2, (runnable) -> {
            Thread thread = new Thread(runnable, "nexum-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}
