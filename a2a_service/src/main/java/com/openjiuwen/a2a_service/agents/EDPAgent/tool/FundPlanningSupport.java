package com.openjiuwen.a2a_service.agents.EDPAgent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FundPlanningSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper LENIENT_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .build();

    private FundPlanningSupport() {
    }

    public static String extractTail(Object text) {
        String raw = String.valueOf(text == null ? "" : text);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (Character.isDigit(ch)) {
                digits.append(ch);
            }
        }
        if (digits.length() == 0) {
            return "0000";
        }
        String normalized = digits.toString();
        if (normalized.length() >= 4) {
            return normalized.substring(normalized.length() - 4);
        }
        return String.format("%4s", normalized).replace(' ', '0');
    }

    public static String buildBalanceQuery(String accountId) {
        return "查询尾号为" + extractTail(accountId) + "的卡的余额";
    }

    public static String buildDefaultBalanceQuery() {
        return "查余额";
    }

    public static String buildTransferQuery(String fromAccount, String toAccount, Object amount) {
        return "从尾号" + extractTail(fromAccount) + "的卡转账" + String.valueOf(amount).trim()
                + "元到尾号为" + extractTail(toAccount) + "的卡";
    }

    public static String buildProductBuyQuery(String productName, String productCode, Object amount) {
        String name = productName != null && !productName.isBlank() ? productName : "理财产品";
        String code = productCode != null ? productCode : "";
        return "购买理财产品：产品名称：" + name + "，产品代码：" + code + "，金额：" + String.valueOf(amount).trim() + "元";
    }

    public static BigDecimal toMoneyDecimal(Object value) {
        try {
            return new BigDecimal(String.valueOf(value).trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static String formatMoneyDecimal(Object value) {
        return toMoneyDecimal(value).toPlainString();
    }

    public static String computeMinTransferAmount(Object targetAmount, Object wealthBalance) {
        BigDecimal target = toMoneyDecimal(targetAmount);
        BigDecimal wealth = toMoneyDecimal(wealthBalance);
        if (target.compareTo(wealth) > 0) {
            return target.subtract(wealth).setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        return target.toPlainString();
    }

    public static double parseBalanceThousands(String balanceStr) {
        String normalized = String.valueOf(balanceStr == null ? "" : balanceStr).replace(",", "").trim();
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    public static Map<String, Object> normalizeBalanceResult(Map<String, Object> data, String accountId) {
        String cardNo = "";
        String balance = "";
        String currency = "";

        Object bankListObj = data.get("bankCardBalanceList");
        if (bankListObj instanceof List<?> bankList && !bankList.isEmpty() && bankList.get(0) instanceof Map<?, ?> first) {
            Object bankCardNumber = first.get("bankCardNumber");
            cardNo = bankCardNumber != null ? String.valueOf(bankCardNumber) : "";
            Object currencyListObj = first.get("currencyBalanceList");
            if (currencyListObj instanceof List<?> currencyList) {
                for (Object entryObj : currencyList) {
                    if (!(entryObj instanceof Map<?, ?> entry)) {
                        continue;
                    }
                    Object currencyCode = entry.get("currencyCode");
                    String code = currencyCode != null ? String.valueOf(currencyCode) : "";
                    if ("001".equals(code)) {
                        Object balanceValue = entry.get("balance");
                        balance = balanceValue != null ? String.valueOf(balanceValue) : "";
                        currency = "001";
                        break;
                    }
                }
                if (balance.isEmpty() && !currencyList.isEmpty() && currencyList.get(0) instanceof Map<?, ?> firstCurrency) {
                    Object balanceValue = firstCurrency.get("balance");
                    Object currencyCode = firstCurrency.get("currencyCode");
                    balance = balanceValue != null ? String.valueOf(balanceValue) : "";
                    currency = currencyCode != null ? String.valueOf(currencyCode) : "001";
                }
            }
        }

        if (balance.isEmpty() && data.get("responseData") instanceof List<?> responseData) {
            for (Object itemObj : responseData) {
                if (!(itemObj instanceof Map<?, ?> item)) {
                    continue;
                }
                Object pageDataObj = item.get("pageData");
                if (!(pageDataObj instanceof Map<?, ?> pageData)) {
                    continue;
                }
                Object bankBalanceDataObj = pageData.get("bankBalanceData");
                if (!(bankBalanceDataObj instanceof List<?> bankBalanceData)) {
                    continue;
                }
                for (Object entryObj : bankBalanceData) {
                    if (!(entryObj instanceof Map<?, ?> entry)) {
                        continue;
                    }
                    Object balanceListObj = entry.get("balanceList");
                    if (!(balanceListObj instanceof List<?> balanceList)) {
                        continue;
                    }
                    for (Object balanceEntryObj : balanceList) {
                        if (!(balanceEntryObj instanceof Map<?, ?> balanceEntry)) {
                            continue;
                        }
                        Object balanceObj = balanceEntry.get("balance");
                        Object titleObj = balanceEntry.get("balanceTitle");
                        String titleName = titleObj instanceof Map<?, ?> title && title.get("titleValue") != null
                                ? String.valueOf(title.get("titleValue")) : "";
                        String value = balanceObj instanceof Map<?, ?> title && title.get("titleValue") != null
                                ? String.valueOf(title.get("titleValue")) : "";
                        if (!value.isEmpty() && (titleName.contains("人民币余额") || balance.isEmpty())) {
                            balance = value;
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("account_id", accountId);
        result.put("bank_card_number", cardNo);
        result.put("balance", balance.isEmpty() ? "0.00" : balance);
        result.put("currency", currency.isEmpty() ? "001" : currency);
        result.put("balance_numeric", parseBalanceThousands(balance.isEmpty() ? "0" : balance));
        return result;
    }

    public static Map<String, Object> normalizeTransferResult(
            Map<String, Object> data,
            String fromAccount,
            String toAccount,
            Object amount
    ) {
        String status = String.valueOf(data.getOrDefault("transferStatus", "success")).toLowerCase();
        boolean ok = "success".equals(status) || "1".equals(status) || "true".equals(status);
        Object transferAmountRaw = data.getOrDefault("transferAmount", amount);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", ok ? "success" : "failed");
        result.put("from_account", fromAccount);
        result.put("to_account", toAccount);
        result.put("amount", Double.parseDouble(formatMoneyDecimal(transferAmountRaw)));
        result.put("currency", "CNY");
        result.put("transaction_id", String.valueOf(data.getOrDefault("transactionId", "")));
        result.put("payer_card", String.valueOf(data.getOrDefault("payerCardNumber", "")));
        result.put("payee_card", String.valueOf(data.getOrDefault("payeeCardNumber", "")));
        result.put("transfer_amount_str", formatMoneyDecimal(transferAmountRaw));
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizePurchaseResult(Map<String, Object> data, String productId, double amount) {
        Map<String, Object> inner = new LinkedHashMap<String, Object>();
        if (data.get("productBuyResponse") instanceof Map<?, ?>) {
            inner.putAll(data);
        } else if (data.get("data") instanceof Map<?, ?> dataNode && dataNode.get("text") instanceof String text) {
            try {
                inner.putAll((Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(text, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        }));
            } catch (Exception ignored) {
            }
        } else {
            inner.putAll(data);
        }

        Object responseObj = inner.get("productBuyResponse");
        Map<String, Object> response = responseObj instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();

        String statusRaw = String.valueOf(response.getOrDefault("buyStatus", ""));
        boolean ok = "1".equals(statusRaw) || "true".equalsIgnoreCase(statusRaw)
                || "success".equalsIgnoreCase(statusRaw) || "购买成功".equals(statusRaw);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", ok ? "success" : "failed");
        result.put("product_id", String.valueOf(response.getOrDefault("productCode", productId)));
        result.put("product_name", String.valueOf(response.getOrDefault("productName", "")));
        result.put("amount", amount);
        result.put("buy_status", statusRaw);
        result.put("fail_cause", String.valueOf(response.getOrDefault("failCause", "")));
        result.put("transaction_id", String.valueOf(response.getOrDefault("transactionId", "")));
        return result;
    }

    public static String parseAccountTail(String description) {
        String raw = String.valueOf(description == null ? "" : description);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("尾号(?:为)?(\\d{4})").matcher(raw);
        return matcher.find() ? matcher.group(1) : "";
    }

    public static TransferArgs parseTransferDescription(String description) {
        String raw = String.valueOf(description == null ? "" : description);
        java.util.regex.Matcher fromMatcher = java.util.regex.Pattern.compile("(?:从|付款)尾号(?:为)?(\\d{4})").matcher(raw);
        java.util.regex.Matcher toMatcher = java.util.regex.Pattern.compile("到尾号(?:为)?(\\d{4})").matcher(raw);
        java.util.regex.Matcher amountMatcher = java.util.regex.Pattern.compile("转账([\\d.]+)元").matcher(raw);
        String fromAccount = fromMatcher.find() ? fromMatcher.group(1) : "";
        String toAccount = toMatcher.find() ? toMatcher.group(1) : "";
        double amount = amountMatcher.find() ? parseDouble(amountMatcher.group(1)) : 0.0d;
        return new TransferArgs(fromAccount, toAccount, amount);
    }

    public static PurchaseArgs parsePurchaseDescription(String description) {
        String raw = String.valueOf(description == null ? "" : description);
        java.util.regex.Matcher codeMatcher = java.util.regex.Pattern.compile("产品代码[：:]([^，,]+)").matcher(raw);
        java.util.regex.Matcher amountMatcher = java.util.regex.Pattern.compile("金额[：:]([\\d.]+)元?").matcher(raw);
        String productId = codeMatcher.find() ? codeMatcher.group(1).trim() : "";
        double amount = amountMatcher.find() ? parseDouble(amountMatcher.group(1)) : 0.0d;
        return new PurchaseArgs(productId, amount);
    }

    public static Map<String, Object> normalizeProductRecommendResult(Object businessData) {
        if (!(businessData instanceof Map<?, ?> raw)) {
            return emptyProductRecommendResult();
        }

        Map<String, Object> data = castMap(raw);
        List<Map<String, Object>> products = parseProductList(data.get("productList"));
        String bankCardNumber = String.valueOf(data.getOrDefault("bankCardNumber", ""));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("products", products);
        result.put("bankCardNumber", bankCardNumber);
        result.put("total", products.size());
        return result;
    }

    public static Map<String, Object> emptyProductRecommendResult() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("products", List.of());
        result.put("bankCardNumber", "");
        result.put("total", 0);
        return result;
    }

    private static List<Map<String, Object>> parseProductList(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(castMap(map));
                }
            }
            return result;
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            return List.of();
        }

        List<Map<String, Object>> parsed = parseProductList(text, MAPPER);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        parsed = parseProductList(text, LENIENT_MAPPER);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        return List.of();
    }

    private static List<Map<String, Object>> parseProductList(String raw, ObjectMapper mapper) {
        try {
            List<Map<String, Object>> parsed = mapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
            return parsed != null ? parsed : List.of();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    public record TransferArgs(String fromAccount, String toAccount, double amount) {
    }

    public record PurchaseArgs(String productId, double amount) {
    }
}
