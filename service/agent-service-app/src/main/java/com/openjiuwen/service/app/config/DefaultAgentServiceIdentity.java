package com.openjiuwen.service.app.config;

import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import org.springframework.core.env.Environment;

/**
 * Resolves {@link AgentServiceIdentity} from {@code spring.application.name}.
 */
public final class DefaultAgentServiceIdentity implements AgentServiceIdentity {

    private final String appName;

    public DefaultAgentServiceIdentity(Environment environment) {
        this.appName = environment.getProperty("spring.application.name", "application");
    }

    public DefaultAgentServiceIdentity(String appName) {
        this.appName = appName;
    }

    @Override
    public String getAppName() {
        return appName;
    }
}
