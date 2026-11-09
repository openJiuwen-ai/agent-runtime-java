/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.storage;

import org.a2aproject.sdk.server.tasks.TaskStore;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Creates raw, agent-scoped task stores for an extension checkpoint backend.
 *
 * @since 0.1.4
 */
public interface A2ATaskStoreProvider {
    /**
     * Returns the extension token used by checkpointer.type. Built-in types are reserved.
     *
     * @return extension backend type
     */
    String type();

    /**
     * Creates an unthrottled store; shared resources remain owned by the application.
     *
     * @param context agent scope and connection reference
     * @return raw task store
     */
    TaskStore create(TaskStoreContext context);

    /**
     * Immutable scope supplied for one ordinary or hosted agent. Register scope-owned cleanup
     * synchronously during create; the framework runs it once on failure or shutdown.
     * Shared application resources must be managed by their application owner instead.
     */
    record TaskStoreContext(String applicationName, String agentId, String connectionRef, Consumer<Runnable> onClose) {
        public TaskStoreContext {
            Objects.requireNonNull(applicationName, "applicationName");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(connectionRef, "connectionRef");
            Objects.requireNonNull(onClose, "onClose");
            if (applicationName.isBlank() || agentId.isBlank() || connectionRef.isBlank()) {
                throw new IllegalArgumentException("Task store scope and connection-ref must not be blank");
            }
        }
    }
}
