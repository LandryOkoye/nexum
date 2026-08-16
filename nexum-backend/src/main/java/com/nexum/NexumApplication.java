package com.nexum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Nexum control plane.
 *
 * <p>Nexum treats the <em>goal</em> - not the agent - as the persistent cognitive
 * boundary. Agents are temporary participants; goal-scoped memory outlives them.
 * This application owns authoritative orchestration and state; CockroachDB owns
 * the durable cognition.
 */
@SpringBootApplication
@EnableScheduling
public class NexumApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexumApplication.class, args);
    }
}
