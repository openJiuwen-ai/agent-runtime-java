/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.ComponentScan;

/** Registers Runtime-owned HTTP endpoints independently of the core lifecycle. */
@AutoConfiguration(after = AgentServiceAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "openjiuwen.service.http", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = "com.openjiuwen.service.app.controller")
public class AgentHttpAutoConfiguration {
}
