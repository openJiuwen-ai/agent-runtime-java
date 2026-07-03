/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * Reset conversation API request body (aligned with Python
 * {@code ResetConversationRequest}).
 *
 * @since 0.1.0
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResetConversationRequest {

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("user_id")
    private String userId;
}
