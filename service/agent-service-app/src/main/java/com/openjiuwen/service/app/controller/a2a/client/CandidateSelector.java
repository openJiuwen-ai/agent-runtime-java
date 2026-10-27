/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.app.a2a.catalog.RemoteInstanceCandidate;

import java.util.List;

/**
 * Strategy for picking one instance candidate out of a catalog entry's
 * candidate list. Implementations must be stateless and thread-safe: a single
 * instance is shared by all concurrent remote calls.
 *
 * @since 0.1.4
 */
public interface CandidateSelector {
    /**
     * Picks one candidate from the given list.
     *
     * @param candidates the live candidates of one catalog entry; must not be
     *        null or empty (the caller checks emptiness beforehand and turns it
     *        into a structured failure)
     * @return the selected candidate
     * @throws IllegalArgumentException when the candidate list is null or empty
     */
    RemoteInstanceCandidate select(List<RemoteInstanceCandidate> candidates);
}
