/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.security;

/**
 * Describes an outbound external service target for authentication and TLS wiring.
 *
 * @param adapterType adapter category ({@code MCP}, {@code Remote}, {@code Sandbox})
 * @param targetId configured endpoint identifier
 * @param url outbound service URL
 * @param credentialSceneType credential scene id used when decrypting auth tokens
 * @since 0.1.0
 */
public record ExternalTargetRef(String adapterType, String targetId, String url, int credentialSceneType) {
}
