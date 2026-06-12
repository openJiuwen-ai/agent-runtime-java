/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.lifecycle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Context passed to lifecycle hooks during init and shutdown.
 */
public final class AgentLifecycleContext {

    private final String appName;
    private final Map<String, Object> attributes;

    public AgentLifecycleContext(String appName) {
        this(appName, new LinkedHashMap<>());
    }

    public AgentLifecycleContext(String appName, Map<String, Object> attributes) {
        this.appName = appName;
        this.attributes = new LinkedHashMap<>(attributes);
    }

    /**
     * {@code spring.application.name} of the running Agent Service process.
     */
    public String getAppName() {
        return appName;
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
}
