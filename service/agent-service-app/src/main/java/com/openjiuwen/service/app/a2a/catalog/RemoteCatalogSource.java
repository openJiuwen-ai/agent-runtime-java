/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

/**
 * Directory data-source abstraction: the single source of truth for the remote
 * catalog consumed by the runtime.
 *
 * <p>The runtime only consumes the merged, unified catalog view produced by
 * this extension point; it never observes the static/dynamic split.
 * Implementations live in the extension repository (the composite catalog
 * source combining the static source, the dynamic unified-registry source, and
 * entry-level arbitration); the dependency direction (extension to runtime) is
 * the only permitted one, and {@code agent-runtime} keeps zero compile-time
 * dependencies on the unified-API client and extension artifacts.</p>
 *
 * @since 0.1.4
 */
public interface RemoteCatalogSource extends AutoCloseable {
    /**
     * Loads a complete catalog snapshot.
     *
     * <p>Arbitration degradation is expressed inside the snapshot content (never
     * as an exception). Expected load failures (network, protocol,
     * deserialization, backend unavailability) must be translated into
     * {@link RemoteCatalogLoadException}; the refresher then keeps the previous
     * catalog and retries on the next cycle. Implementations must not swallow
     * failures and return a half-loaded snapshot.</p>
     *
     * @return the merged catalog snapshot with a monotonic, content-stable version
     * @throws RemoteCatalogLoadException when the source fails to load the catalog
     */
    RemoteCatalogSnapshot load();

    /**
     * Releases implementation-owned schedulers and connections.
     */
    @Override
    void close();
}
