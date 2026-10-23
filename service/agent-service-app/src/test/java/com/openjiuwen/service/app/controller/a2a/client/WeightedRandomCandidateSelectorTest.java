/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.app.a2a.catalog.RemoteInstanceCandidate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Tests of the weighted random candidate selection (FEAT-062 L2 {@literal §}4):
 * proportional choice for positive weights, the equal weight of one for
 * non-positive weights, and the input validation contract.
 *
 * @since 0.1.4
 */
class WeightedRandomCandidateSelectorTest {

    private final WeightedRandomCandidateSelector selector = new WeightedRandomCandidateSelector();

    @Test
    void rejectsNullAndEmptyCandidateLists() {
        assertThatThrownBy(() -> selector.select(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("candidates must not be null");
        assertThatThrownBy(() -> selector.select(List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidates must not be empty");
    }

    @Test
    void singleCandidateIsAlwaysSelected() {
        RemoteInstanceCandidate only = new RemoteInstanceCandidate("i-1", "http://i-1/a2a", 42);
        for (int i = 0; i < 100; i++) {
            assertThat(selector.select(List.of(only))).isSameAs(only);
        }
    }

    @Test
    void zeroWeightsDegradeToUniformChoice() {
        List<RemoteInstanceCandidate> candidates = List.of(new RemoteInstanceCandidate("i-1", "http://i-1/a2a", 0),
                new RemoteInstanceCandidate("i-2", "http://i-2/a2a", 0));
        Map<String, Long> counts = draw(candidates, 4000);

        assertThat(counts).containsOnlyKeys("i-1", "i-2");
        assertThat(counts.get("i-1")).isBetween(1600L, 2400L);
        assertThat(counts.get("i-2")).isBetween(1600L, 2400L);
    }

    @Test
    void positiveWeightsAreHonoredProportionally() {
        List<RemoteInstanceCandidate> candidates = List.of(new RemoteInstanceCandidate("heavy", "http://h/a2a", 300),
                new RemoteInstanceCandidate("light", "http://l/a2a", 100));
        Map<String, Long> counts = draw(candidates, 4000);

        assertThat(counts.get("heavy")).isBetween(2600L, 3400L);
        assertThat(counts.get("light")).isBetween(600L, 1400L);
        assertThat(counts.get("heavy")).isGreaterThan(counts.get("light"));
    }

    @Test
    void nonPositiveWeightStillParticipatesWithTheEqualWeightOfOne() {
        List<RemoteInstanceCandidate> candidates = List.of(new RemoteInstanceCandidate("heavy", "http://h/a2a", 100),
                new RemoteInstanceCandidate("zero", "http://z/a2a", 0),
                new RemoteInstanceCandidate("negative", "http://n/a2a", -5));
        Map<String, Long> counts = draw(candidates, 8000);

        assertThat(counts.get("heavy")).isGreaterThan(counts.get("zero"));
        assertThat(counts.get("zero")).isGreaterThan(0L);
        assertThat(counts.get("negative")).isGreaterThan(0L);
    }

    private Map<String, Long> draw(List<RemoteInstanceCandidate> candidates, int draws) {
        return IntStream.range(0, draws).mapToObj(ignored -> selector.select(candidates))
                .collect(Collectors.groupingBy(RemoteInstanceCandidate::instanceId, Collectors.counting()));
    }
}
