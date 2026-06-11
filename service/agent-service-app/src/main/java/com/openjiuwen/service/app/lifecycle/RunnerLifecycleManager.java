package com.openjiuwen.service.app.lifecycle;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.service.adapters.agentfw.CoreAgentHandler;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts/stops AgentCore {@link Runner} during lifecycle init/shutdown when a {@link CoreAgentHandler} is in use.
 */
public final class RunnerLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(RunnerLifecycleManager.class);

    private final ServiceProperties serviceProperties;
    private final AgentServiceIdentity identity;
    private volatile boolean started;

    public RunnerLifecycleManager(ServiceProperties serviceProperties, AgentServiceIdentity identity) {
        this.serviceProperties = serviceProperties;
        this.identity = identity;
    }

    public void startIfNeeded(AgentHandler handler) {
        if (!serviceProperties.isAutoStartRunner() || started || !usesRunner(handler)) {
            return;
        }
        log.info("Starting AgentCore Runner for application '{}'", identity.getAppName());
        Runner.start();
        started = true;
    }

    public void stopIfStarted() {
        if (!started) {
            return;
        }
        log.info("Stopping AgentCore Runner for application '{}'", identity.getAppName());
        try {
            Runner.stop();
        } catch (Exception ex) {
            log.error("Failed to stop AgentCore Runner for application '{}'", identity.getAppName(), ex);
            throw ex;
        } finally {
            started = false;
        }
    }

    boolean isRunnerStarted() {
        return started;
    }

    private static boolean usesRunner(AgentHandler handler) {
        if (handler instanceof CoreAgentHandler) {
            return true;
        }
        if (handler instanceof AgentHandlerHolder holder) {
            AgentHandler delegate = holder.getDelegate();
            return delegate instanceof CoreAgentHandler;
        }
        return false;
    }
}
