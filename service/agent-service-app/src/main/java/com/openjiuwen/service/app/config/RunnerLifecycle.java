package com.openjiuwen.service.app.config;

import com.openjiuwen.core.runner.Runner;
import org.springframework.context.SmartLifecycle;

/**
 * Starts/stops the agent-core-java {@code Runner} alongside the Spring context.
 */
public class RunnerLifecycle implements SmartLifecycle {

    private volatile boolean running;

    @Override
    public void start() {
        Runner.start();
        running = true;
    }

    @Override
    public void stop() {
        try {
            Runner.stop();
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
