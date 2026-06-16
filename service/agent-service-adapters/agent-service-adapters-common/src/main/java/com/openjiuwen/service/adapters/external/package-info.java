/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * Engine-agnostic external egress: HTTP client templates, MCP transport setup, retry/timeout
 * DFX, and shared outbound configuration.
 * <p>Binding into an execution engine SPI (e.g. Core ToolMgr / {@code McpClient}) is done in
 * the engine leaf {@code external} package.
 */
package com.openjiuwen.service.adapters.external;
