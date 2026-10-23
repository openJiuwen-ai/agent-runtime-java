/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.app.a2a.catalog.RemoteInstanceCandidate;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Weighted random {@link CandidateSelector}: candidates with a positive weight
 * are chosen proportionally to their weight; candidates with a weight of zero
 * or below participate with an equal weight of one, which degrades weighted
 * choice to uniform random.
 *
 * <p>Stateless and thread-safe; sampling uses {@link ThreadLocalRandom} and no
 * draw influences the next one (each call samples from the full candidate
 * list, no exclusion between rounds — round rotation is the caller's
 * concern).</p>
 *
 * @since 0.1.4
 */
public class WeightedRandomCandidateSelector implements CandidateSelector {

    /** Equal weight applied to candidates with a non-positive weight. */
    private static final int EQUAL_WEIGHT = 1;

    @Override
    public RemoteInstanceCandidate select(List<RemoteInstanceCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        int totalWeight = 0;
        for (RemoteInstanceCandidate candidate : candidates) {
            totalWeight += effectiveWeight(candidate);
        }
        int offset = ThreadLocalRandom.current().nextInt(totalWeight);
        for (RemoteInstanceCandidate candidate : candidates) {
            offset -= effectiveWeight(candidate);
            if (offset < 0) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private int effectiveWeight(RemoteInstanceCandidate candidate) {
        return candidate.weight() > 0 ? candidate.weight() : EQUAL_WEIGHT;
    }
}
