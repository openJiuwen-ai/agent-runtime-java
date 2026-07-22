/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config;

import org.a2aproject.sdk.server.config.A2AConfigProvider;
import org.springframework.core.env.Environment;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolves A2A SDK configuration from the Spring environment before falling back to SDK defaults.
 *
 * @since 0.1.0
 */
public class SpringEnvironmentConfigProvider implements A2AConfigProvider {
    private final Environment environment;

    private final A2AConfigProvider defaults;

    /**
     * Creates a Spring environment backed A2A configuration provider.
     *
     * @param environment the Spring environment
     * @param defaults the initialized SDK defaults provider
     */
    public SpringEnvironmentConfigProvider(Environment environment, A2AConfigProvider defaults) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.defaults = Objects.requireNonNull(defaults, "defaults must not be null");
    }

    @Override
    public String getValue(String name) {
        String value = environment.getProperty(name);
        return value != null ? value : defaults.getValue(name);
    }

    @Override
    public Optional<String> getOptionalValue(String name) {
        return Optional.ofNullable(environment.getProperty(name)).or(() -> defaults.getOptionalValue(name));
    }
}
