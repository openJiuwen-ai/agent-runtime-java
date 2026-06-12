/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import lombok.Data;

/**
 * Reset conversation API response (aligned with Python AgentApp).
 */
@Data
public class ResetConversationResponse {

    private String status;
    private String message;

    public ResetConversationResponse() {
    }

    public ResetConversationResponse(String status, String message) {
        this.status = status;
        this.message = message;
    }

    public static ResetConversationResponse ok(String conversationId) {
        return new ResetConversationResponse("ok", "Conversation " + conversationId + " reset");
    }
}
