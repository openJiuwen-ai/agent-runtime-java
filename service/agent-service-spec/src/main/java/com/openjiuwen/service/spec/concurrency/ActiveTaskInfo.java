/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.concurrency;

/**
 * Information about a single active task (DFX-002).
 *
 * @since 0.1.2
 */
public final class ActiveTaskInfo {
    private final String taskId;

    private final String conversationId;

    private final String status;

    private final String startedAt;

    /**
     * Creates an active task info record.
     *
     * @param taskId A2A task identifier
     * @param conversationId conversation identifier
     * @param status task status (e.g. {@code WORKING})
     * @param startedAt ISO-8601 timestamp when the task started (e.g. {@code "2026-08-20T17:45:44.123Z"})
     */
    public ActiveTaskInfo(String taskId, String conversationId, String status, String startedAt) {
        this.taskId = taskId;
        this.conversationId = conversationId;
        this.status = status;
        this.startedAt = startedAt;
    }

    /**
     * A2A task identifier.
     *
     * @return task id
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Conversation identifier.
     *
     * @return conversation id
     */
    public String getConversationId() {
        return conversationId;
    }

    /**
     * Task status string (e.g. {@code WORKING}).
     *
     * @return status
     */
    public String getStatus() {
        return status;
    }

    /**
     * ISO-8601 timestamp when the task started.
     *
     * @return start timestamp (e.g. {@code "2026-08-20T17:45:44.123Z"})
     */
    public String getStartedAt() {
        return startedAt;
    }
}
