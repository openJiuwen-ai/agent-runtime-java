/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import com.openjiuwen.service.adapters.agentfw.CoreAgentHandler;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.spec.lifecycle.AgentLifecycleContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentHandlerLoaderTest {

    @Test
    void loadsCoreHandlerFromAgentIdConfiguration() {
        ServiceProperties properties = new ServiceProperties();
        properties.setAgentId("configured-agent");
        AgentHandlerLoader loader = new AgentHandlerLoader(properties);
        AgentHandlerHolder holder = new AgentHandlerHolder();

        loader.loadInto(holder, new AgentLifecycleContext("test-app"));

        assertThat(holder.isLoaded()).isTrue();
        assertThat(holder.getDelegate()).isInstanceOf(CoreAgentHandler.class);
    }

    @Test
    void skipsWhenHolderAlreadyLoaded() {
        ServiceProperties properties = new ServiceProperties();
        properties.setAgentId("configured-agent");
        AgentHandlerLoader loader = new AgentHandlerLoader(properties);
        AgentHandlerHolder holder = new AgentHandlerHolder();
        holder.setHandler(new CoreAgentHandler("preset-agent"));

        loader.loadInto(holder, new AgentLifecycleContext("test-app"));

        assertThat(holder.getDelegate()).isInstanceOf(CoreAgentHandler.class);
    }

    @Test
    void skipsWhenAgentIdMissing() {
        AgentHandlerLoader loader = new AgentHandlerLoader(new ServiceProperties());
        AgentHandlerHolder holder = new AgentHandlerHolder();

        loader.loadInto(holder, new AgentLifecycleContext("test-app"));

        assertThat(holder.isLoaded()).isFalse();
    }
}
