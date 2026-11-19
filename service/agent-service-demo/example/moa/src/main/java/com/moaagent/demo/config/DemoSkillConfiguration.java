/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.moaagent.demo.config;

import com.moaagent.agent.skill.SkillScriptRunner;
import com.moaagent.runtime.skill.NativeSkillConfiguration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configures A2A-only Skills and direct access from the standalone demo page.
 *
 * @since 0.1.3
 */
@AutoConfiguration(before = NativeSkillConfiguration.class)
@Profile({"l1", "l2"})
@ConditionalOnProperty(name = "moa.skill-agent.enabled", havingValue = "true")
public class DemoSkillConfiguration {
    @Bean
    @Profile("l1")
    WebMvcConfigurer demoCors() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/a2a").allowedOrigins("null")
                        .allowedMethods("POST", "OPTIONS").allowedHeaders("*");
            }
        };
    }

    @Bean
    SkillScriptRunner demoScriptRunner() {
        return (session, source, input) -> {
            throw new IllegalStateException("moa-agent-demo only supports A2A Skill operations");
        };
    }
}
