/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.reset;

import com.openjiuwen.service.spec.dto.ResetConversationRequest;

import java.util.Map;

/**
 * Shared ingress logic for reset conversation controllers.
 */
public final class ResetIngressSupport {

    private ResetIngressSupport() {
    }

    public static ValidationResult validate(ResetConversationRequest request) {
        if (request == null || request.getConversationId() == null
                || request.getConversationId().isBlank()) {
            return ValidationResult.error(400, Map.of("type", "error", "error", "conversation_id is required"));
        }
        return ValidationResult.ok(request.getConversationId());
    }

    public record ValidationResult(boolean valid, int errorStatus, Map<String, Object> errorBody,
                                   String conversationId) {

        static ValidationResult ok(String conversationId) {
            return new ValidationResult(true, 0, null, conversationId);
        }

        static ValidationResult error(int status, Map<String, Object> body) {
            return new ValidationResult(false, status, body, null);
        }
    }
}
