/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

/**
 * A remote catalog source load failure.
 *
 * <p>{@link RemoteCatalogSource} implementations must translate every expected
 * load failure (network, protocol, deserialization, backend unavailability)
 * into this exception. At the refresher boundary any runtime exception escaping
 * {@code load()} is treated as degradation too — the previous catalog is kept
 * and the next cycle retries — because the pull runs as a scheduled callback
 * and an escaping exception would silently terminate the TTL loop.</p>
 *
 * @since 0.1.4
 */
public class RemoteCatalogLoadException extends RuntimeException {
    /**
     * Builds the degradation signal for one failed catalog load cycle.
     *
     * @param message the failure detail naming the source and the reason
     */
    public RemoteCatalogLoadException(String message) {
        this(message, null);
    }

    /**
     * Builds the degradation signal, chaining the original load failure for
     * diagnosis.
     *
     * @param message the failure detail naming the source and the reason
     * @param cause the original load failure to chain
     */
    public RemoteCatalogLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
