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
     * Creates an exception with a detail message.
     *
     * @param message the detail message
     */
    public RemoteCatalogLoadException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a detail message and a cause.
     *
     * @param message the detail message
     * @param cause the underlying failure
     */
    public RemoteCatalogLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
