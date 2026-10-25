/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

/**
 * A remote catalog source load failure.
 *
 * <p>{@link RemoteCatalogSource} implementations must translate every expected
 * load failure (network, protocol, deserialization, backend unavailability)
 * into this exception; the refresher treats it as degradation, keeps the
 * previous catalog, and retries on the next cycle. Any other runtime exception
 * escaping {@code load()} is an implementation bug and surfaces fail-fast.</p>
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
