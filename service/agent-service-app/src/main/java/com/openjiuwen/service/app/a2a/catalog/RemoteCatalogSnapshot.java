/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

import java.util.Comparator;
import java.util.List;

/**
 * Immutable versioned snapshot of the merged remote-agent catalog produced by
 * a {@link RemoteCatalogSource}.
 *
 * <p>The version is assigned by the source: it stays stable across consecutive
 * loads with unchanged content and increases when the content changes; it never
 * decreases. This lets the runtime skip re-applying unchanged snapshots. Entries
 * are kept in a stable {@code agentId} order to make diffs and downstream
 * publications deterministic. An empty-candidate entry is a legal state (all
 * instances of the target are offline, the upgrade window): it stays in the
 * catalog but is not callable.</p>
 *
 * @param version monotonically increasing snapshot version
 * @param entries catalog entries sorted by agentId
 * @since 0.1.4
 */
public record RemoteCatalogSnapshot(long version, List<RemoteCatalogEntry> entries) {
    /**
     * Creates an immutable snapshot with entries sorted by agentId.
     */
    public RemoteCatalogSnapshot {
        entries = entries.stream()
                .sorted(Comparator.comparing(entry -> entry.agentId() == null ? "" : entry.agentId()))
                .toList();
    }
}
