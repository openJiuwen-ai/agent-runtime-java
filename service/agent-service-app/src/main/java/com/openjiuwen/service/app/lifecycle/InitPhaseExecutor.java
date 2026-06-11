package com.openjiuwen.service.app.lifecycle;

import com.openjiuwen.service.app.config.LifecycleProperties;
import com.openjiuwen.service.spec.lifecycle.AgentInitHook;
import com.openjiuwen.service.spec.lifecycle.AgentLifecycleContext;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Runs {@link AgentInitHook}s, performs built-in Agent loading, starts Runner when needed,
 * and updates readiness after init.
 */
public final class InitPhaseExecutor {

    private static final Logger log = LoggerFactory.getLogger(InitPhaseExecutor.class);

    private final AgentServiceIdentity identity;
    private final AgentLifecycleHooks hooks;
    private final DefaultAgentReadiness readiness;
    private final ObjectProvider<AgentHandler> agentHandlerProvider;
    private final AgentHandlerLoader agentHandlerLoader;
    private final RunnerLifecycleManager runnerLifecycleManager;
    private final LifecycleProperties properties;

    public InitPhaseExecutor(
            AgentServiceIdentity identity,
            AgentLifecycleHooks hooks,
            DefaultAgentReadiness readiness,
            ObjectProvider<AgentHandler> agentHandlerProvider,
            AgentHandlerLoader agentHandlerLoader,
            RunnerLifecycleManager runnerLifecycleManager,
            LifecycleProperties properties) {
        this.identity = identity;
        this.hooks = hooks;
        this.readiness = readiness;
        this.agentHandlerProvider = agentHandlerProvider;
        this.agentHandlerLoader = agentHandlerLoader;
        this.runnerLifecycleManager = runnerLifecycleManager;
        this.properties = properties;
    }

    public void run() {
        String appName = identity.getAppName();
        log.info("Starting Agent init phase for application '{}', hookCount={}",
                appName, hooks.initHooks().size());
        AgentLifecycleContext context = new AgentLifecycleContext(appName);
        try {
            for (AgentInitHook hook : hooks.initHooks()) {
                log.debug("Running AgentInitHook: {}", hook.getClass().getName());
                hook.onInit(context);
            }
            AgentHandler handler = agentHandlerProvider.getIfAvailable();
            if (handler instanceof AgentHandlerHolder holder) {
                agentHandlerLoader.loadInto(holder, context);
            }
            if (handler == null) {
                readiness.markAgentLoaded(false);
                log.warn("Agent init phase completed for application '{}' without AgentHandler bean, agent_loaded=false",
                        appName);
                return;
            }
            runnerLifecycleManager.startIfNeeded(handler);
            if (isAgentLoaded(handler)) {
                readiness.markAgentLoaded(true);
                log.info("Agent init phase completed for application '{}', agent_loaded=true", appName);
            } else {
                readiness.markAgentLoaded(false);
                log.warn("Agent init phase completed for application '{}' but agent is not loaded, agent_loaded=false",
                        appName);
            }
        } catch (Exception ex) {
            readiness.markAgentLoaded(false);
            if (properties.isInitFailFast()) {
                log.error("Agent init phase failed for application '{}' (init-fail-fast=true)", appName, ex);
                throw new IllegalStateException("Agent init phase failed", ex);
            }
            log.error("Agent init phase failed for application '{}' (init-fail-fast=false), agent_loaded remains false",
                    appName, ex);
        }
    }

    private static boolean isAgentLoaded(AgentHandler handler) {
        if (handler instanceof AgentHandlerHolder holder) {
            return holder.isLoaded();
        }
        return true;
    }
}
