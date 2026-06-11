package com.openjiuwen.service.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAgentServiceIdentityTest {

    @Test
    void readsSpringApplicationName() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "my-agent");

        DefaultAgentServiceIdentity identity = new DefaultAgentServiceIdentity(environment);

        assertThat(identity.getAppName()).isEqualTo("my-agent");
    }

    @Test
    void defaultsWhenPropertyMissing() {
        DefaultAgentServiceIdentity identity = new DefaultAgentServiceIdentity(new MockEnvironment());

        assertThat(identity.getAppName()).isEqualTo("application");
    }
}
