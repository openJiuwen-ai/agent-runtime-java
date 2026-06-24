/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.foundation.tool.mcp.McpClientFactory;
import com.openjiuwen.core.foundation.tool.mcp.McpClientProvider;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientFactory;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientProvider;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Snapshot of Core provider registries used to restore static test state.
 *
 * @since 2026-06-24
 */
public final class CoreProviderRegistrySnapshot implements AutoCloseable {
    private final Map<String, McpClientProvider> mcpProviders;
    private final Map<String, RemoteClientProvider> remoteProviders;

    private CoreProviderRegistrySnapshot(
            Map<String, McpClientProvider> mcpProviders,
            Map<String, RemoteClientProvider> remoteProviders) {
        this.mcpProviders = mcpProviders;
        this.remoteProviders = remoteProviders;
    }

    /**
     * Captures the current Core MCP and remote provider registries.
     *
     * @return snapshot of the current provider registries
     */
    public static CoreProviderRegistrySnapshot capture() {
        return new CoreProviderRegistrySnapshot(
                new HashMap<>(mcpRegistry()),
                new HashMap<>(remoteRegistry()));
    }

    @Override
    public void close() {
        restore(mcpRegistry(), mcpProviders);
        restore(remoteRegistry(), remoteProviders);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, McpClientProvider> mcpRegistry() {
        return (Map<String, McpClientProvider>) registry(McpClientFactory.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, RemoteClientProvider> remoteRegistry() {
        return (Map<String, RemoteClientProvider>) registry(RemoteClientFactory.class);
    }

    private static Map<?, ?> registry(Class<?> factoryType) {
        try {
            Field field = factoryType.getDeclaredField("REGISTRY");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to access Core provider registry: " + factoryType.getName(), ex);
        }
    }

    private static <T> void restore(Map<String, T> registry, Map<String, T> snapshot) {
        registry.clear();
        registry.putAll(snapshot);
    }
}
