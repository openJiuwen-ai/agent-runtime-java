/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.lifecycle;

/**
 * Application display name for lifecycle logs; consumed by Issue #8
 * {@code GET /health} {@code app} field.
 * <p>
 * Value is {@code spring.application.name}.
 *
 * @since 0.1.0
 */
public interface AgentServiceIdentity {

    /**
     * Returns the application display name.
     *
     * @return the application name
     */
    String getAppName();
}
