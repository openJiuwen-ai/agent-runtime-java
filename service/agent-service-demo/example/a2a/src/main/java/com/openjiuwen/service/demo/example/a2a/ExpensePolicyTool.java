/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic company policy lookup used by Agent D's expense workflow.
 *
 * @since 0.1.0
 */
public final class ExpensePolicyTool extends LocalFunction {
    private static final Gson GSON = new Gson();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private static final Map<String, Double> LIMITS = Map.of("hotel", 600.0D, "meal", 300.0D, "transport", 1000.0D,
            "other", 1000.0D);

    public ExpensePolicyTool() {
        super(card(), ExpensePolicyTool::review);
    }

    static Map<String, Object> review(Map<String, Object> inputs) {
        Map<String, Object> claim = parseClaim(String.valueOf(inputs.getOrDefault("query", "{}")));
        String claimId = stringValue(claim, "claim_id", "UNKNOWN-CLAIM");
        String category = stringValue(claim, "category", "other").toLowerCase(Locale.ROOT);
        if (!LIMITS.containsKey(category)) {
            category = "other";
        }
        double unitPrice = numberValue(claim, "unit_price", 0.0D);
        double quantity = numberValue(claim, "quantity", 1.0D);
        double total = numberValue(claim, "total", unitPrice * quantity);
        String currency = stringValue(claim, "currency", "CNY");
        double limit = LIMITS.get(category);
        boolean requiresApproval = unitPrice > limit;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("claim_id", claimId);
        result.put("category", category);
        result.put("unit_price", unitPrice);
        result.put("quantity", quantity);
        result.put("total", total);
        result.put("currency", currency);
        result.put("limit", limit);
        result.put("requires_approval", requiresApproval);
        result.put("policy_status", requiresApproval ? "OVER_LIMIT" : "COMPLIANT");
        result.put("summary",
                String.format(Locale.ROOT, "claim %s: %s unit price %.2f %s, policy limit %.2f %s, total %.2f %s",
                        claimId, category, unitPrice, currency, limit, currency, total, currency));
        return result;
    }

    private static ToolCard card() {
        return ToolCard.builder().id("check_expense_policy").name("check_expense_policy")
                .description("Validate a canonical expense claim against deterministic company policy limits")
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("query", Map.of("type", "string", "description", "Canonical JSON expense claim")),
                        "required", List.of("query")))
                .build();
    }

    private static Map<String, Object> parseClaim(String query) {
        try {
            Map<String, Object> parsed = GSON.fromJson(query, MAP_TYPE);
            return parsed != null ? parsed : Map.of();
        } catch (JsonSyntaxException ignored) {
            return Map.of();
        }
    }

    private static String stringValue(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static double numberValue(Map<String, Object> values, String key, double fallback) {
        Object value = values.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
