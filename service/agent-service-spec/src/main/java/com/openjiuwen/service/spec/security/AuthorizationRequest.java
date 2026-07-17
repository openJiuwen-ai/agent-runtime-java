/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.security;

import java.util.Map;

/**
 * Fine-grained authorization input assembled by the runtime from resource annotation,
 * tenant headers, and extension metadata.
 *
 * @param resource resource identifier from {@link AuthorizedResource#resource()}
 * @param action action identifier from {@link AuthorizedResource#action()}
 * @param userId {@code X-User-ID} header value, may be {@code null}
 * @param spaceId {@code X-Space-ID} header value, may be {@code null}
 * @param tenantId {@code X-Tenant-ID} header value, may be {@code null}
 * @param extensions reserved extension map for SPI implementations; empty by default
 * @since 0.1.0
 */
public record AuthorizationRequest(String resource, String action, String userId, String spaceId, String tenantId,
    Map<String, Object> extensions) {
}
