/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import com.openjiuwen.service.spec.lifecycle.AgentInterruptHandler;
import com.openjiuwen.service.spec.lifecycle.InterruptReason;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

/**
 * Cancels in-flight streaming execution and notifies interrupt hooks.
 *
 * @since 0.1.0
 */
public final class ActiveStreamInterruptor {

    private static final Logger log = LoggerFactory.getLogger(ActiveStreamInterruptor.class);

    private final ObjectProvider<ServeOrchestrator> orchestratorProvider;
    private final List<AgentInterruptHandler> interruptHandlers;

    public ActiveStreamInterruptor(ObjectProvider<ServeOrchestrator> orchestratorProvider,
            List<AgentInterruptHandler> interruptHandlers) {
        this.orchestratorProvider = orchestratorProvider;
        this.interruptHandlers = interruptHandlers;
    }

    /**
     * Interrupts active execution for the given conversation.
     *
     * @param conversationId the conversation identifier
     */
    public void interrupt(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            log.warn("interrupt ignored: conversation_id is blank");
            return;
        }
        log.info("Interrupting active execution for conversation_id={}", conversationId);
        ServeOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator != null) {
            orchestrator.cancelActive(conversationId);
        } else {
            log.warn("No ServeOrchestrator available, skip cancelActive for conversation_id={}", conversationId);
        }
        for (AgentInterruptHandler handler : interruptHandlers) {
            try {
                handler.interrupt(conversationId, InterruptReason.LIFECYCLE_INTERRUPT);
            } catch (Exception ex) {
                log.warn("AgentInterruptHandler failed: {}, conversation_id={}", handler.getClass().getName(),
                        conversationId, ex);
            }
        }
    }
}
