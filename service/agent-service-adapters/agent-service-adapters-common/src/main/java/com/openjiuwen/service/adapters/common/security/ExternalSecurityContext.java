/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import java.util.Map;

/**
 * Outbound security audit context held by decorating adapters.
 *
 * @param adapterType adapter category
 * @param targetId configured endpoint id
 * @param tlsEnabled whether TLS is enabled
 * @param authType configured auth type
 * @param materialExtensions SPI output extensions
 * @since 0.1.0
 */
public record ExternalSecurityContext(String adapterType, String targetId, boolean tlsEnabled, String authType,
    Map<String, Object> materialExtensions) {
}
