/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.lifecycle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Context passed to lifecycle hooks during init and shutdown.
 *
 * @since 0.1.0
 */
public final class AgentLifecycleContext {
    private final String appName;
    private final Map<String, Object> attributes;

    /**
     * Creates a lifecycle context for the given application name.
     *
     * @param appName the application name
     */
    public AgentLifecycleContext(String appName) {
        this(appName, new LinkedHashMap<>());
    }

    /**
     * Creates a lifecycle context with initial attributes.
     *
     * @param appName the application name
     * @param attributes the initial attribute map
     */
    public AgentLifecycleContext(String appName, Map<String, Object> attributes) {
        this.appName = appName;
        this.attributes = new LinkedHashMap<>(attributes);
    }

    /**
     * {@code spring.application.name} of the running Agent Service process.
     *
     * @return String
     */
    public String getAppName() {
        return appName;
    }

    /**
     * Returns an unmodifiable view of lifecycle attributes.
     *
     * @return the attribute map
     */
    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * Stores a lifecycle attribute.
     *
     * @param key the attribute key
     * @param value the attribute value
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Returns a typed lifecycle attribute.
     *
     * @param key the attribute key
     * @param <T> the expected value type
     * @return the attribute value, or {@code null} when absent
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
}
