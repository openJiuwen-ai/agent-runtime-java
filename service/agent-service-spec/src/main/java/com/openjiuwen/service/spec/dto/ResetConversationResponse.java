/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import lombok.Data;

/**
 * Reset conversation API response (aligned with Python AgentApp).
 *
 * @since 0.1.0
 */
@Data
public class ResetConversationResponse {
    private String status;
    private String message;

    /** Default constructor for JSON deserialization. */
    public ResetConversationResponse() {
    }

    /**
     * Creates a reset response with explicit status and message.
     *
     * @param status the response status
     * @param message the response message
     */
    public ResetConversationResponse(String status, String message) {
        this.status = status;
        this.message = message;
    }

    /**
     * Creates a successful reset response.
     *
     * @param conversationId the reset conversation identifier
     * @return the success response
     */
    public static ResetConversationResponse ok(String conversationId) {
        return new ResetConversationResponse("ok", "Conversation " + conversationId + " reset");
    }
}
