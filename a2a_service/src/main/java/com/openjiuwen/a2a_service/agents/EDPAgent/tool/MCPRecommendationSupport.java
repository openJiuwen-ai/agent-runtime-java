package com.openjiuwen.a2a_service.agents.EDPAgent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MCPRecommendationSupport {

    public static final String INTERACT_FINANCE_REC_COMMAND =
            "python rebuild_interact_finance_rec_skill/scripts/run_interact_finance_rec_skill.py";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SORT_TYPE_MAX = 7;
    private static final String MCP_TOOL_NAME = "get-finance-productslist";

    private MCPRecommendationSupport() {
    }

    public static Map<String, Object> buildSkillInput(Map<String, Object> toolArgs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("mcp_params", parseMap(stringValue(toolArgs, "mcp_params")));
        result.put("mcp_required_params", parseMap(stringValue(toolArgs, "mcp_required_params")));
        result.put("history_product_codes", parseList(stringValue(toolArgs, "history_product_codes")));
        result.put("current_sort_type", intValue(toolArgs.get("current_sort_type")));
        result.put("history_recommend_params", parseMap(stringValue(toolArgs, "history_recommend_params")));

        Map<String, Object> skillContext = parseMap(stringValue(toolArgs, "skill_context"));
        for (Map.Entry<String, Object> entry : skillContext.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> runMcpRecommend(Map<String, Object> skillInput) {
        Map<String, Object> mcpParams = asMap(skillInput.get("mcp_params"));
        Map<String, Object> requiredParams = asMap(skillInput.get("mcp_required_params"));
        List<Object> historyCodesRaw = asList(skillInput.get("history_product_codes"));
        List<String> historyCodes = stringifyList(historyCodesRaw);
        int currentSortType = intValue(skillInput.get("current_sort_type"));
        Map<String, Object> historyParams = asMap(skillInput.get("history_recommend_params"));

        Map<String, Object> mergedParams = mergeRecommendParams(historyParams, mcpParams);
        List<Map<String, Object>> products = new ArrayList<Map<String, Object>>();
        String mcpError = null;

        Map<String, Object> callParams = buildMcpCallParams(mergedParams, requiredParams);
        try {
            List<Map<String, Object>> mcpProducts = callMcp(callParams, requiredParams);
            if (mcpProducts != null) {
                products = mcpProducts;
            } else {
                mcpError = "MCP returned None (timeout or connection error)";
            }
        } catch (Exception e) {
            mcpError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        products = deduplicateProducts(products, historyCodes);
        int nextSortType = rotateSortType(currentSortType);
        Map<String, Object> updatedParams = new LinkedHashMap<String, Object>(mergedParams);
        updatedParams.put("sortType", String.valueOf(nextSortType));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("products", products);
        result.put("total", products.size());
        result.put("next_sort_type", nextSortType);
        result.put("updated_recommend_params", updatedParams);
        result.put("history_product_codes", collectProductCodes(products, historyCodes));
        result.put("mcp_error", mcpError);
        return result;
    }

    public static Map<String, Object> errorResult(Map<String, Object> skillInput) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("products", List.of());
        result.put("total", 0);
        result.put("next_sort_type", 0);
        result.put("updated_recommend_params", Map.of());
        result.put("history_product_codes", skillInput != null
                ? asList(skillInput.get("history_product_codes")) : List.of());
        result.put("mcp_error", "sandbox execution failed");
        return result;
    }

    public static Map<String, Object> normalizeInteractFinanceRecResult(Map<String, Object> skillInput) {
        Map<String, Object> businessData = asMap(skillInput.get("business_data"));
        Map<String, Object> mcpProductsData = asMapOrNull(skillInput.get("mcp_products_data"));
        List<String> historyCodes = stringifyList(asList(skillInput.get("history_product_codes")));
        int currentSortType = intValue(skillInput.get("current_sort_type"));

        List<Map<String, Object>> products;
        if (mcpProductsData != null) {
            products = asListOfMaps(mcpProductsData.get("products"));
        } else {
            products = ProductFilterResultNormalizer.parseProductListStr(businessData.get("productList"));
        }
        products = deduplicateProducts(products, historyCodes);

        String bankCardNumber = String.valueOf(businessData.getOrDefault("bankCardNumber", ""));
        int nextSortType = rotateSortType(currentSortType);

        Map<String, Object> updatedRecommendParams = new LinkedHashMap<String, Object>();
        if (mcpProductsData != null && mcpProductsData.get("updated_recommend_params") instanceof Map<?, ?> map) {
            updatedRecommendParams.putAll(asMap(map));
        } else {
            updatedRecommendParams.putAll(asMap(skillInput.get("history_recommend_params")));
            updatedRecommendParams.put("sortType", String.valueOf(nextSortType));
        }

        List<Object> mcpHistoryCodes = mcpProductsData != null ? asList(mcpProductsData.get("history_product_codes")) : List.of();
        List<String> updatedHistoryCodes = mcpHistoryCodes.isEmpty()
                ? collectProductCodes(products, historyCodes)
                : stringifyList(mcpHistoryCodes);

        boolean firstRecommend = Boolean.TRUE.equals(skillInput.get("is_first_recommend"))
                || "true".equalsIgnoreCase(String.valueOf(skillInput.get("is_first_recommend")));
        Object mcpError = mcpProductsData != null ? mcpProductsData.get("mcp_error") : null;

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("products", products);
        result.put("bankCardNumber", bankCardNumber);
        result.put("total", products.size());
        result.put("next_sort_type", nextSortType);
        result.put("updated_recommend_params", updatedRecommendParams);
        result.put("history_product_codes", updatedHistoryCodes);

        if (products.isEmpty()) {
            if (mcpError != null && !String.valueOf(mcpError).isBlank()) {
                result.put("error", "mcp_timeout");
                result.put("message", "暂时无法获取理财产品信息，请稍后再试。");
            } else if (firstRecommend) {
                result.put("error", "no_products");
                result.put("message", "没有符合您要求的理财产品，请重新描述需求");
            }
        } else if (firstRecommend && products.size() > 2) {
            result.put("original_total", products.size());
            result.put("products", new ArrayList<Map<String, Object>>(products.subList(0, 2)));
            result.put("total", 2);
        }
        return result;
    }

    private static List<Map<String, Object>> callMcp(Map<String, Object> params, Map<String, Object> requiredParams)
            throws Exception {
        String url = selectMcpUrl(requiredParams);
        if (url.isBlank()) {
            return null;
        }
        Map<String, Object> requestBody = new LinkedHashMap<String, Object>();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("id", "java-mcp-call");
        requestBody.put("method", "tools/call");
        requestBody.put("params", Map.of(
                "name", MCP_TOOL_NAME,
                "arguments", params
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("appAccessCheckToken", env("MCP_ACCESS_TOKEN"))
                .header("app_name", env("MCP_APP_NAME"))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestBody)))
                .build();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("MCP HTTP error " + response.statusCode());
        }
        Map<String, Object> payload = MAPPER.readValue(response.body(), new TypeReference<Map<String, Object>>() { });
        Object result = payload.containsKey("result") ? payload.get("result") : payload;
        return parseMcpProducts(result);
    }

    private static List<Map<String, Object>> parseMcpProducts(Object payload) {
        Map<String, Object> map = asMap(payload);
        Object content = map.get("content");
        if (content instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object text = first.get("text");
            if (text != null) {
                try {
                    return parseMcpProducts(MAPPER.readValue(String.valueOf(text), new TypeReference<Map<String, Object>>() { }));
                } catch (Exception ignored) {
                    return List.of();
                }
            }
        }
        Object opData = map.get("opData");
        if (opData instanceof Map<?, ?> opMap) {
            return asListOfMaps(opMap.get("prodList"));
        }
        if (opData instanceof List<?>) {
            return asListOfMaps(opData);
        }
        return asListOfMaps(map.get("prodList"));
    }

    private static String selectMcpUrl(Map<String, Object> requiredParams) {
        String grayFlag = String.valueOf(requiredParams.getOrDefault("wap_grayFlag", ""));
        return grayFlag.startsWith("JD") ? env("MCP_JD_URL") : env("MCP_XSQ_URL");
    }

    private static int timeoutSeconds() {
        String raw = env("MCP_TIMEOUT");
        if (raw.isBlank()) {
            return 25;
        }
        return intValue(raw);
    }

    private static Map<String, Object> mergeRecommendParams(Map<String, Object> history, Map<String, Object> current) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(history);
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            Object value = entry.getValue();
            if (value != null && !String.valueOf(value).isBlank()) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private static Map<String, Object> buildMcpCallParams(Map<String, Object> merged, Map<String, Object> required) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(merged);
        for (Map.Entry<String, Object> entry : required.entrySet()) {
            Object value = entry.getValue();
            if (value != null && !String.valueOf(value).isBlank()) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private static List<Map<String, Object>> deduplicateProducts(List<Map<String, Object>> products, List<String> historyCodes) {
        if (historyCodes.isEmpty()) {
            return products;
        }
        Set<String> history = new LinkedHashSet<String>(historyCodes);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> product : products) {
            String code = String.valueOf(product.getOrDefault("productCode", product.getOrDefault("product_code", "")));
            if (!history.contains(code)) {
                result.add(product);
            }
        }
        return result;
    }

    private static int rotateSortType(int currentSortType) {
        return (currentSortType + 1) % SORT_TYPE_MAX;
    }

    private static List<String> collectProductCodes(List<Map<String, Object>> products, List<String> historyCodes) {
        List<String> result = new ArrayList<String>(historyCodes);
        for (Map<String, Object> product : products) {
            Object code = product.getOrDefault("productCode", product.get("product_code"));
            if (code != null && !String.valueOf(code).isBlank()) {
                result.add(String.valueOf(code));
            }
        }
        return result;
    }

    private static Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() { });
        } catch (Exception ignored) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private static List<Object> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<Object>();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<List<Object>>() { });
        } catch (Exception ignored) {
            return new ArrayList<Object>();
        }
    }

    private static Map<String, Object> asMapOrNull(Object value) {
        if (value instanceof Map<?, ?>) {
            return asMap(value);
        }
        return null;
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<String, Object>();
    }

    private static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<Object>(list);
        }
        return new ArrayList<Object>();
    }

    private static List<Map<String, Object>> asListOfMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(asMap(map));
                }
            }
        }
        return result;
    }

    private static List<String> stringifyList(List<?> values) {
        List<String> result = new ArrayList<String>();
        for (Object value : values) {
            if (value != null) {
                result.add(String.valueOf(value));
            }
        }
        return result;
    }

    private static int intValue(Object value) {
        try {
            return value != null ? Integer.parseInt(String.valueOf(value)) : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String stringValue(Map<String, Object> values, String key) {
        Object value = values != null ? values.get(key) : null;
        return value != null ? String.valueOf(value) : "";
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value != null ? value : "";
    }
}
