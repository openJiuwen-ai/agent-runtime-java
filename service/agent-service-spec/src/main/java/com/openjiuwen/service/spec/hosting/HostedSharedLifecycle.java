/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.hosting;

/**
 * Framework integration for process resources borrowed by hosted handlers.
 * Implementations must tolerate stop after partial preparation or startup.
 *
 * @since 0.1.2
 */
public interface HostedSharedLifecycle {
    /**
     * Validates and prepares shared resources before instance assembly.
     *
     * @param applicationName frozen runtime application identity
     * @param definitions immutable handler declarations
     */
    void prepare(String applicationName, HostedAgentDefinitions definitions);

    /** Starts the shared resource once, before any hosted handler starts. */
    void start();

    /** Stops the owned resource once, after all hosted handlers are processed. */
    void stop();

    /**
     * Stops with the remaining process shutdown wait budget. Implementations with
     * an explicit wait should override this method; ordinary cleanup keeps its
     * existing stop behavior. This does not forcibly terminate blocking cleanup.
     *
     * @param remainingWaitMs nonnegative remaining wait budget in milliseconds
     */
    default void stop(long remainingWaitMs) {
        stop();
    }
}
