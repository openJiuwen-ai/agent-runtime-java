/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * AgentCore middleware <em>registration</em>: wire {@code adapters-common} clients/config into
 * agent-core-java SPI (e.g. {@code CheckpointerFactory}, {@code RunnerConfig}) via init hooks
 * or auto-configuration — typically before {@code Runner.start()}.
 */
package com.openjiuwen.service.adapters.middleware;
