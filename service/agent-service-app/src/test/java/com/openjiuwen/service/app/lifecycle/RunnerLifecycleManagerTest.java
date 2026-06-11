package com.openjiuwen.service.app.lifecycle;

import com.openjiuwen.service.adapters.agentfw.CoreAgentHandler;
import com.openjiuwen.service.app.config.DefaultAgentServiceIdentity;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerLifecycleManagerTest {

    @Test
    void skipsRunnerForNonCoreHandler() {
        RunnerLifecycleManager manager = newManager(true);
        manager.startIfNeeded(stubHandler());
        assertThat(manager.isRunnerStarted()).isFalse();
        manager.stopIfStarted();
        assertThat(manager.isRunnerStarted()).isFalse();
    }

    @Test
    void skipsRunnerWhenAutoStartDisabled() {
        ServiceProperties properties = new ServiceProperties();
        properties.setAutoStartRunner(false);
        RunnerLifecycleManager manager = new RunnerLifecycleManager(
                properties, new DefaultAgentServiceIdentity("test-app"));

        manager.startIfNeeded(new CoreAgentHandler("agent-id"));

        assertThat(manager.isRunnerStarted()).isFalse();
    }

    @Test
    void startsAndStopsRunnerForCoreAgentHandler() {
        RunnerLifecycleManager manager = newManager(true);
        manager.startIfNeeded(new CoreAgentHandler("agent-id"));

        assertThat(manager.isRunnerStarted()).isTrue();

        manager.stopIfStarted();
        assertThat(manager.isRunnerStarted()).isFalse();
    }

    @Test
    void startsRunnerForHolderWithCoreDelegate() {
        RunnerLifecycleManager manager = newManager(true);
        AgentHandlerHolder holder = new AgentHandlerHolder();
        holder.setHandler(new CoreAgentHandler("agent-id"));

        manager.startIfNeeded(holder);

        assertThat(manager.isRunnerStarted()).isTrue();
        manager.stopIfStarted();
    }

    @Test
    void startIfNeededIsIdempotent() {
        RunnerLifecycleManager manager = newManager(true);
        CoreAgentHandler handler = new CoreAgentHandler("agent-id");

        manager.startIfNeeded(handler);
        manager.startIfNeeded(handler);

        assertThat(manager.isRunnerStarted()).isTrue();
        manager.stopIfStarted();
    }

    private static RunnerLifecycleManager newManager(boolean autoStartRunner) {
        ServiceProperties properties = new ServiceProperties();
        properties.setAutoStartRunner(autoStartRunner);
        return new RunnerLifecycleManager(properties, new DefaultAgentServiceIdentity("test-app"));
    }

    private static AgentHandler stubHandler() {
        return new AgentHandler() {
            @Override
            public com.openjiuwen.service.spec.dto.QueryResponse query(
                    com.openjiuwen.service.spec.dto.ServeRequest request) {
                return null;
            }

            @Override
            public void streamQuery(
                    com.openjiuwen.service.spec.dto.ServeRequest request,
                    com.openjiuwen.service.spec.spi.QueryStreamObserver observer) {
            }
        };
    }
}
