/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.autoconfigure.AgentHttpAutoConfiguration;
import com.openjiuwen.service.app.autoconfigure.AuthAutoConfiguration;
import com.openjiuwen.service.app.autoconfigure.TlsAutoConfiguration;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.app.controller.query.QueryWebFluxController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class HttpIngressConfigurationTest {
    @Test
    void bindsDefaultTrueAndExplicitFalseAndRejectsInvalidBoolean() {
        var runner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);
        runner.run(context -> assertThat(context.getBean(ServiceProperties.class).getHttp().isEnabled()).isTrue());
        runner.withPropertyValues("openjiuwen.service.http.enabled=false")
                .run(context -> assertThat(context.getBean(ServiceProperties.class).getHttp().isEnabled()).isFalse());
        runner.withPropertyValues("openjiuwen.service.http.enabled=invalid")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void nonWebDoesNotCreateHttpConfigurationEvenWithDefaultEnabled() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AgentHttpAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(AgentHttpAutoConfiguration.class));
    }

    @Test
    void directImportsAndReactiveOptionalControllerRespectDisabledSwitch() {
        new ReactiveWebApplicationContextRunner().withUserConfiguration(DirectImports.class)
                .withPropertyValues("openjiuwen.service.http.enabled=false",
                        "openjiuwen.service.security.enabled=true", "openjiuwen.service.security.auth.enabled=true",
                        "openjiuwen.service.security.tls.enabled=true", "openjiuwen.service.query.webflux.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(QueryWebFluxController.class);
                    assertThat(context).doesNotHaveBean(AuthAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(TlsAutoConfiguration.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServiceProperties.class)
    static class PropertiesConfiguration { }

    @Configuration(proxyBeanMethods = false)
    @Import({AuthAutoConfiguration.class, TlsAutoConfiguration.class, QueryWebFluxController.class})
    static class DirectImports { }
}
