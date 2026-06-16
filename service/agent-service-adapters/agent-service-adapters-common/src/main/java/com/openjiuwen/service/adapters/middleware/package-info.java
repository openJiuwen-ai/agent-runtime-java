/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * Engine-agnostic middleware: connection factories, configuration properties, and client
 * implementations (Redis, KV, object storage, tracing helpers, etc.).
 * <p>Registration into a specific execution engine (e.g. Core {@code CheckpointerFactory})
 * lives in the corresponding engine leaf under its own {@code middleware} package.
 */
package com.openjiuwen.service.adapters.middleware;
