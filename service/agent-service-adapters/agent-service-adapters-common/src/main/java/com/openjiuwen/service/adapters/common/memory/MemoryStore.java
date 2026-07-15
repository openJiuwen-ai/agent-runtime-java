/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.memory;

import java.util.List;
import java.util.Optional;

/**
 * Runtime-side long-term memory service abstraction.
 *
 * <p>This SPI is a data service facade for runtime/business code. It does not decide when
 * an Agent should read or write memory, and it intentionally does not expose Agent tool
 * semantics such as {@code handleToolCall}.
 *
 * @since 0.1.0
 */
public interface MemoryStore {
    /**
     * Provider identifier, for example {@code mem0}.
     *
     * @return provider identifier
     */
    String getProvider();

    /**
     * Whether the store has enough configuration to serve requests.
     *
     * @return true when available
     */
    boolean isAvailable();

    /**
     * Adds durable memory records.
     *
     * @param request add request
     * @return write result returned by the backing service
     */
    MemoryWriteResult add(MemoryAddRequest request);

    /**
     * Searches durable memory records.
     *
     * @param request search request
     * @return matched memory records
     */
    List<MemoryRecord> search(MemorySearchRequest request);

    /**
     * Fetches one durable memory record by id.
     *
     * @param request get request
     * @return record when found
     */
    Optional<MemoryRecord> get(MemoryGetRequest request);

    /**
     * Deletes one durable memory record by id.
     *
     * @param request delete request
     */
    void delete(MemoryDeleteRequest request);
}
