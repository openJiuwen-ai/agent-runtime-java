/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.security;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outbound HTTP authentication overlay merged into headers and query parameters.
 *
 * @param headers HTTP headers to attach to outbound requests
 * @param queryParams query parameters to attach to outbound requests
 * @param materialExtensions SPI output extensions; not written to HTTP in P0
 * @since 0.1.0
 */
public record AuthMaterial(Map<String, String> headers, Map<String, String> queryParams,
    Map<String, Object> materialExtensions) {

    /**
     * Returns an empty authentication overlay.
     */
    public static AuthMaterial none() {
        return new AuthMaterial(Map.of(), Map.of(), Map.of());
    }

    /**
     * Normalizes null maps to empty immutable maps.
     */
    public AuthMaterial {
        headers = headers == null || headers.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
        queryParams = queryParams == null || queryParams.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(queryParams));
        materialExtensions = materialExtensions == null || materialExtensions.isEmpty()
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(materialExtensions));
    }

    /**
     * Whether no HTTP authentication overlay is present.
     */
    public boolean isEmpty() {
        return headers.isEmpty() && queryParams.isEmpty();
    }
}
