/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Unit tests for the deterministic Agent D expense policy.
 */
class ExpensePolicyToolTest {
    @Test
    void hotelNightlyRateAboveLimitRequiresApproval() {
        Map<String, Object> result = ExpensePolicyTool
                .review(Map.of("query", "{\"claim_id\":\"WF-HIGH\",\"category\":\"hotel\",\"unit_price\":1000,"
                        + "\"quantity\":3,\"total\":3000,\"currency\":\"CNY\"}"));

        assertThat(result).containsEntry("claim_id", "WF-HIGH").containsEntry("policy_status", "OVER_LIMIT")
                .containsEntry("requires_approval", true).containsEntry("limit", 600.0D);
    }

    @Test
    void compliantTransportClaimCanBeAutoApproved() {
        Map<String, Object> result = ExpensePolicyTool
                .review(Map.of("query", "{\"claim_id\":\"WF-AUTO\",\"category\":\"transport\",\"unit_price\":100,"
                        + "\"quantity\":1,\"total\":100,\"currency\":\"CNY\"}"));

        assertThat(result).containsEntry("policy_status", "COMPLIANT").containsEntry("requires_approval", false)
                .containsEntry("limit", 1000.0D);
    }
}
