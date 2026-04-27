package com.openjiuwen.a2a_service.agents.EDPAgent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProductFilterResultNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProductFilterResultNormalizer() {
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseProductListStr(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<String, Object>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    result.add(normalized);
                }
            }
            return result;
        }
        if (!(raw instanceof String value) || value.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(value, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception ignored) {
        }
        try {
            String jsonLike = value.replace('\'', '"');
            return MAPPER.readValue(jsonLike, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public static Map<String, Object> normalizeProductFilterResult(Map<String, Object> businessData) {
        if (businessData == null) {
            return Map.of("products", List.of(), "bankCardNumber", "", "total", 0);
        }
        List<Map<String, Object>> products = parseProductListStr(businessData.get("productList"));
        String bankCardNumber = String.valueOf(businessData.getOrDefault("bankCardNumber", ""));
        return Map.of(
                "products", products,
                "bankCardNumber", bankCardNumber,
                "total", products.size()
        );
    }
}
