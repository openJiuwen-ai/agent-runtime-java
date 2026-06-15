/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentApp lifecycle configuration (Issue #5).
 */
@Data
@ConfigurationProperties(prefix = "openjiuwen.service.lifecycle")
public class LifecycleProperties {

    private long shutdownTimeoutMs = 30000L;

    private boolean initFailFast = true;
}
