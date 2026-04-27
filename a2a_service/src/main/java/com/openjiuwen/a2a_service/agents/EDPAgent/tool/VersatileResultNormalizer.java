package com.openjiuwen.a2a_service.agents.EDPAgent.tool;

import java.util.LinkedHashMap;
import java.util.Map;

public final class VersatileResultNormalizer {

    public static final String FUND_PLANNING_COMMAND =
            "python model_driven_fund_planning_skill/scripts/run_fund_planning.py";
    public static final String PRODUCT_RECOMMEND_COMMAND =
            "python rebuild_product_recommend_skill/scripts/run_product_recommend_skill.py";

    private VersatileResultNormalizer() {
    }

    public static boolean supports(String command) {
        return FUND_PLANNING_COMMAND.equals(command)
                || PRODUCT_RECOMMEND_COMMAND.equals(command)
                || MCPRecommendationSupport.INTERACT_FINANCE_REC_COMMAND.equals(command);
    }

    public static Object normalize(String command, Map<String, Object> toolArgs, Map<String, Object> businessData) {
        return normalize(command, toolArgs, businessData, Map.of());
    }

    public static Object normalize(
            String command,
            Map<String, Object> toolArgs,
            Map<String, Object> businessData,
            Map<String, Object> mcpProductsData
    ) {
        if (MCPRecommendationSupport.INTERACT_FINANCE_REC_COMMAND.equals(command)) {
            Map<String, Object> skillInput = new LinkedHashMap<String, Object>();
            skillInput.put("query_intent", stringValue(toolArgs, "query_intent"));
            skillInput.put("query_description", stringValue(toolArgs, "query_description"));
            skillInput.put("business_data", businessData);
            if (mcpProductsData != null && !mcpProductsData.isEmpty()) {
                skillInput.put("mcp_products_data", mcpProductsData);
            }
            mergeJsonContext(skillInput, stringValue(toolArgs, "skill_context"));
            if (!skillInput.containsKey("is_first_recommend")) {
                skillInput.put("is_first_recommend", isFirstRecommend(skillInput));
            }
            return MCPRecommendationSupport.normalizeInteractFinanceRecResult(skillInput);
        }
        if (PRODUCT_RECOMMEND_COMMAND.equals(command)) {
            return FundPlanningSupport.normalizeProductRecommendResult(businessData);
        }
        if (!FUND_PLANNING_COMMAND.equals(command)) {
            return businessData;
        }

        String queryIntent = stringValue(toolArgs, "query_intent");
        String queryDescription = stringValue(toolArgs, "query_description");

        if ("查询账户余额".equals(queryIntent)) {
            return FundPlanningSupport.normalizeBalanceResult(
                    businessData,
                    FundPlanningSupport.parseAccountTail(queryDescription)
            );
        }
        if ("快速转账".equals(queryIntent)) {
            FundPlanningSupport.TransferArgs args = FundPlanningSupport.parseTransferDescription(queryDescription);
            return FundPlanningSupport.normalizeTransferResult(
                    businessData,
                    args.fromAccount(),
                    args.toAccount(),
                    args.amount()
            );
        }
        if ("理财选品购买".equals(queryIntent)) {
            FundPlanningSupport.PurchaseArgs args = FundPlanningSupport.parsePurchaseDescription(queryDescription);
            return FundPlanningSupport.normalizePurchaseResult(
                    businessData,
                    args.productId(),
                    args.amount()
            );
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.putAll(businessData);
        return result;
    }

    private static void mergeJsonContext(Map<String, Object> target, String rawContext) {
        if (rawContext == null || rawContext.isBlank()) {
            return;
        }
        try {
            Map<String, Object> context = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(rawContext, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                if (!target.containsKey(entry.getKey())) {
                    target.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isFirstRecommend(Map<String, Object> skillInput) {
        return isEmptyList(skillInput.get("history_product_codes"))
                && intValue(skillInput.get("current_sort_type")) == 0
                && isEmptyMap(skillInput.get("history_recommend_params"));
    }

    private static boolean isEmptyList(Object value) {
        return !(value instanceof java.util.List<?> list) || list.isEmpty();
    }

    private static boolean isEmptyMap(Object value) {
        return !(value instanceof Map<?, ?> map) || map.isEmpty();
    }

    private static int intValue(Object value) {
        try {
            return value != null ? Integer.parseInt(String.valueOf(value)) : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String stringValue(Map<String, Object> values, String key) {
        if (values == null) {
            return "";
        }
        Object value = values.get(key);
        return value != null ? String.valueOf(value) : "";
    }
}
