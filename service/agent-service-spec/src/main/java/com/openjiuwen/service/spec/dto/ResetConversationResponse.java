/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

/**
 * Reset conversation API response (aligned with Python AgentApp).
 */
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
