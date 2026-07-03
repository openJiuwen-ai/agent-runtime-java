/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.lifecycle;

/**
 * Application display name for lifecycle logs; consumed by Issue #8
 * {@code GET /health} {@code app} field.
 * <p>
 * Value is {@code spring.application.name}.
 */
public interface AgentServiceIdentity {

    String getAppName();
}
