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

    /**
     * Creates an {@link A2AMessageContext} from the SDK request context.
     *
     * @param ctx the SDK request context
     * @return the populated message context
     */
    public static A2AMessageContext from(RequestContext ctx) {
        A2AMessageContext c = new A2AMessageContext();
        c.a2aMessage = ctx.getMessage();
        c.contextId = ctx.getContextId();
        c.taskId = ctx.getTaskId();
        c.metadata = ctx.getMetadata();

        Task existingTask = ctx.getTask();
        if (existingTask != null) {
            int historySize = existingTask.history() != null ? existingTask.history().size() : 0;
            log.info("A2A RESUME taskId={} contextId={} existingTaskId={} existingContextId={} historySize={}",
                    c.taskId, c.contextId, existingTask.id(), existingTask.contextId(), historySize);
        } else {
            log.info("A2A NEW task taskId={} contextId={}", c.taskId, c.contextId);
        }

        return c;
    }
}
