/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * Engine-agnostic external egress: retry / timeout / circuit breaker / audit
 * DFX,
 * shared error semantics, and outbound execution helpers.
 * <p>
 * Binding into an execution engine SPI is done in the engine leaf
 * {@code external} package
 * Outbound integration types for adapter modules (custom {@code AgentHandler}
 * implementations).
 */

package com.openjiuwen.service.adapters.common.external;
