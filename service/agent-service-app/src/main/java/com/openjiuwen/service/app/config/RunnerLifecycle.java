package com.openjiuwen.service.app.config;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Starts/stops the agent-core-java {@code Runner} alongside the Spring context.
 */
public class RunnerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunnerLifecycle.class);

    private final AgentServiceIdentity identity;
    private volatile boolean running;

    public RunnerLifecycle(AgentServiceIdentity identity) {
        this.identity = identity;
    }

    @Override
    public void start() {
        log.info("Starting AgentCore Runner for application '{}'", identity.getAppName());
        Runner.start();
        running = true;
    }

    @Override
    public void stop() {
        log.info("Stopping AgentCore Runner for application '{}'", identity.getAppName());
        try {
            Runner.stop();
        } catch (Exception ex) {
            log.error("Failed to stop AgentCore Runner for application '{}'",
                    identity.getAppName(), ex);
            throw ex;
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
