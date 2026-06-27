/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import java.util.Map;
import lombok.Data;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal DTO wrapping A2A SDK parsed message context.
 *
 * @since 0.1.0
 */
@Data
public class A2AMessageContext {

    private static final Logger log = LoggerFactory.getLogger(A2AMessageContext.class);

    private Message a2aMessage;
    private String contextId;
    private String taskId;
    private Map<String, Object> metadata;
    private Map<String, String> headers;

    public static A2AMessageContext from(RequestContext ctx) {
        A2AMessageContext c = new A2AMessageContext();
        c.a2aMessage = ctx.getMessage();
        c.contextId = ctx.getContextId();
        c.taskId = ctx.getTaskId();
        c.metadata = ctx.getMetadata(); // MessageSendParams.metadata() passthrough

        Task existingTask = ctx.getTask();
        if (existingTask != null) {
            log.info(
                    "A2A RESUME detected taskId={} contextId={} existingTaskId={} existingTaskContextId={} historySize={}",
                    c.taskId, c.contextId, existingTask.id(), existingTask.contextId(),
                    existingTask.history() != null ? existingTask.history().size() : 0);
        } else {
            log.info("A2A NEW task taskId={} contextId={}", c.taskId, c.contextId);
        }

        return c;
    }
}
