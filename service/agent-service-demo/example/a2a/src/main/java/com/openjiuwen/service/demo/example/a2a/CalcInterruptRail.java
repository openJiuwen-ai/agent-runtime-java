/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confirmation-aware calculator tool for Agent B.
 *
 * @since 0.1.0
 */
public class CalcInterruptRail extends BaseInterruptRail {
    private static final Gson GSON = new Gson();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private static final String TOOL_NAME = "calc";

    private static final Pattern BINARY_EXPRESSION = Pattern.compile(
            "^\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*([+\\-*/])\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*$");

    private static final Set<String> AFFIRMATIVE_RESPONSES = Set.of("ok", "yes", "y", "confirm", "confirmed", "approve",
            "approved", "continue", "proceed");

    private static final Set<String> NEGATIVE_RESPONSES = Set.of("no", "n", "cancel", "cancelled", "reject", "rejected",
            "stop");

    public CalcInterruptRail() {
        super(List.of(TOOL_NAME));
        ToolCard card = ToolCard.builder().id(TOOL_NAME).name(TOOL_NAME)
                .description("Ask for confirmation, then evaluate a binary arithmetic expression using +, -, *, or /.")
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("expression",
                                Map.of("type", "string", "description", "The math expression to evaluate, e.g. '1+1'")),
                        "required", List.of("expression")))
                .build();
        getTools().add(card);
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        String expression = extractExpression(toolCall);
        if (resumeInput == null) {
            return requestConfirmation(expression);
        }

        String confirmation = normalizeConfirmation(resumeInput);
        if (AFFIRMATIVE_RESPONSES.contains(confirmation)) {
            return reject(calculate(expression));
        }
        if (NEGATIVE_RESPONSES.contains(confirmation)) {
            return reject("Calculation cancelled: " + displayExpression(expression));
        }
        return requestConfirmation(expression);
    }

    private InterruptDecision requestConfirmation(String expression) {
        var request = InterruptRequest.builder().message(
                "Agent B is ready to calculate " + displayExpression(expression) + ". Continue? Reply yes or no.")
                .context(Map.of("_interrupt_kind", "ask_user")).build();
        return interrupt(request);
    }

    private static String calculate(String expression) {
        Matcher matcher = BINARY_EXPRESSION.matcher(expression);
        if (!matcher.matches()) {
            return "Calculation failed: provide one binary expression using +, -, *, or /.";
        }

        BigDecimal left = new BigDecimal(matcher.group(1));
        BigDecimal right = new BigDecimal(matcher.group(3));
        String operator = matcher.group(2);
        BigDecimal result;
        switch (operator) {
            case "+" :
                result = left.add(right);
                break;
            case "-" :
                result = left.subtract(right);
                break;
            case "*" :
                result = left.multiply(right);
                break;
            case "/" :
                if (right.compareTo(BigDecimal.ZERO) == 0) {
                    return "Calculation failed: division by zero.";
                }
                result = left.divide(right, MathContext.DECIMAL128);
                break;
            default :
                return "Calculation failed: unsupported operator.";
        }

        String normalizedExpression = format(left) + operator + format(right);
        return "Calculation completed: " + normalizedExpression + " = " + format(result);
    }

    private static String extractExpression(ToolCall toolCall) {
        try {
            Map<String, Object> args = GSON.fromJson(toolCall.getArguments(), MAP_TYPE);
            Object expression = args.get("expression");
            if (expression instanceof String value && !value.isBlank()) {
                return value.trim();
            }
        } catch (JsonSyntaxException | NullPointerException ignored) {
            // Invalid tool arguments are reported as a deterministic tool result after confirmation.
        }
        return "";
    }

    private static String normalizeConfirmation(Object resumeInput) {
        return String.valueOf(resumeInput).trim().toLowerCase(Locale.ROOT).replaceFirst("[.!]$", "");
    }

    private static String displayExpression(String expression) {
        return expression.isBlank() ? "the requested expression" : expression;
    }

    private static String format(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.compareTo(BigDecimal.ZERO) == 0 ? "0" : normalized.toPlainString();
    }
}
