package com.nexum.agent;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The two pools agent execution needs, and the tool it researches with.
 */
@Configuration(proxyBeanMethods = false)
class AgentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentConfiguration.class);

    /**
     * Live web research when a key is configured, the offline corpus when not.
     *
     * <p>Chosen by whether the credential exists rather than by a profile, so a
     * deployment cannot end up asking for web search it has no key for and
     * failing every search at runtime. A missing key is a supported
     * configuration, not a broken one.
     *
     * <p>Logged loudly either way. The most expensive hour of this build was
     * spent on agents that looked like they were working while every model call
     * silently fell back, so which tool is live is stated at startup rather than
     * inferred later from empty results.
     */
    @Bean
    ResearchTool researchTool(RestClient.Builder builder, CompetitorCorpus corpus,
            @Value("${nexum.research.tavily-api-key:}") String tavilyApiKey,
            @Value("${nexum.research.timeout-seconds:20}") int timeoutSeconds) {

        if (tavilyApiKey == null || tavilyApiKey.isBlank()) {
            log.warn("No TAVILY_API_KEY configured - agents will research the offline corpus "
                    + "of {} documents. Set it to enable live web research.", corpus.size());
            return new CorpusResearchTool(corpus);
        }

        log.info("Live web research enabled (Tavily), {}s timeout", timeoutSeconds);
        return new WebResearchTool(builder, tavilyApiKey, Duration.ofSeconds(timeoutSeconds));
    }

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
