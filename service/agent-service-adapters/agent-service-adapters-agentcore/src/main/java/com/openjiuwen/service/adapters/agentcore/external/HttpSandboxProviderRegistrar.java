/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.sysop.sandbox.SandboxRegistry;

/**
 * Registers HTTP sandbox operation providers in the Core sandbox registry.
 *
 * @since 2026-06-24
 */
final class HttpSandboxProviderRegistrar {
    private HttpSandboxProviderRegistrar() {
    }

    static void register(AgentCoreExternalProperties.SandboxPolicy policy) {
        if (policy == null || !policy.isEnabled()) {
            return;
        }
        for (AgentCoreExternalProperties.SandboxServer server : policy.getServers()) {
            if (server == null || server.getSandboxType() == null || server.getSandboxType().isBlank()) {
                continue;
            }
            String sandboxType = server.getSandboxType();
            registerIfMissing(sandboxType, "fs", HttpSandboxFsProvider.class);
            registerIfMissing(sandboxType, "shell", HttpSandboxShellProvider.class);
            registerIfMissing(sandboxType, "code", HttpSandboxCodeProvider.class);
        }
    }

    private static void registerIfMissing(String sandboxType, String operationType, Class<?> providerClass) {
        if (SandboxRegistry.getProviderClass(sandboxType, operationType) != null) {
            return;
        }
        SandboxRegistry.registerProvider(sandboxType, operationType, providerClass);
    }
}
