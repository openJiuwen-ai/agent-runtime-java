package com.openjiuwen.a2a_service.agents.EDPAgent.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class EdpAgentSettings {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DEFAULT_CONFIG_PATH = Path.of("config", "edp-agent.json");

    private final String fastApiHost;
    private final int fastApiPort;
    private final String planningAgentModelName;
    private final String planningAgentModelApiKey;
    private final String planningAgentModelBaseUrl;
    private final double planningAgentModelTimeout;
    private final boolean skillLlmTlsVerify;
    private final String planningAgentModelToken;
    private final String planningAgentModelTokenHeader;
    private final String planningAgentModelUserId;
    private final String planningAgentModelUserIdHeader;
    private final Map<String, String> planningAgentModelExtraHeaders;
    private final String versatileAdapterUrl;
    private final double versatileTimeout;

    public EdpAgentSettings() {
        this(
                "0.0.0.0",
                8090,
                "",
                "",
                "",
                120.0d,
                false,
                "",
                "",
                "",
                "",
                new LinkedHashMap<String, String>(),
                "http://localhost:8091",
                600.0d
        );
    }

    public EdpAgentSettings(
            String fastApiHost,
            int fastApiPort,
            String planningAgentModelName,
            String planningAgentModelApiKey,
            String planningAgentModelBaseUrl,
            double planningAgentModelTimeout,
            boolean skillLlmTlsVerify,
            String planningAgentModelToken,
            String planningAgentModelTokenHeader,
            String planningAgentModelUserId,
            String planningAgentModelUserIdHeader,
            Map<String, String> planningAgentModelExtraHeaders,
            String versatileAdapterUrl,
            double versatileTimeout
    ) {
        this.fastApiHost = fastApiHost;
        this.fastApiPort = fastApiPort;
        this.planningAgentModelName = planningAgentModelName;
        this.planningAgentModelApiKey = planningAgentModelApiKey;
        this.planningAgentModelBaseUrl = planningAgentModelBaseUrl;
        this.planningAgentModelTimeout = planningAgentModelTimeout;
        this.skillLlmTlsVerify = skillLlmTlsVerify;
        this.planningAgentModelToken = planningAgentModelToken;
        this.planningAgentModelTokenHeader = planningAgentModelTokenHeader;
        this.planningAgentModelUserId = planningAgentModelUserId;
        this.planningAgentModelUserIdHeader = planningAgentModelUserIdHeader;
        this.planningAgentModelExtraHeaders = planningAgentModelExtraHeaders != null
                ? new LinkedHashMap<String, String>(planningAgentModelExtraHeaders)
                : new LinkedHashMap<String, String>();
        this.versatileAdapterUrl = versatileAdapterUrl;
        this.versatileTimeout = versatileTimeout;
    }

    public static EdpAgentSettings load() {
        return load(DEFAULT_CONFIG_PATH, System.getenv());
    }

    public static EdpAgentSettings load(Path configPath, Map<String, String> env) {
        EdpAgentSettings base = new EdpAgentSettings();
        Map<String, Object> json = readJson(configPath);
        EdpAgentSettings fromJson = mergeJson(base, json);
        return mergeEnv(fromJson, env);
    }

    public static EdpAgentSettings fromMap(Map<String, String> env) {
        return mergeEnv(new EdpAgentSettings(), env);
    }

    private static Map<String, Object> readJson(Path configPath) {
        if (configPath == null || !Files.exists(configPath)) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(Files.readString(configPath), new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read config json: " + configPath, e);
        }
    }

    private static EdpAgentSettings mergeJson(EdpAgentSettings base, Map<String, Object> root) {
        Map<String, Object> server = mapValue(root.get("server"));
        Map<String, Object> llm = mapValue(root.get("llm"));
        Map<String, Object> workflow = mapValue(root.get("workflow"));

        return new EdpAgentSettings(
                stringOrDefault(firstNonNull(server.get("host"), root.get("FASTAPI_HOST")), base.getFastApiHost()),
                intOrDefault(firstNonNull(server.get("port"), root.get("FASTAPI_PORT")), base.getFastApiPort()),
                stringOrDefault(firstNonNull(llm.get("modelName"), root.get("PLANNING_AGENT_MODEL_NAME")), base.getPlanningAgentModelName()),
                stringOrDefault(firstNonNull(llm.get("apiKey"), root.get("PLANNING_AGENT_MODEL_API_KEY")), base.getPlanningAgentModelApiKey()),
                stringOrDefault(firstNonNull(llm.get("baseUrl"), root.get("PLANNING_AGENT_MODEL_BASE_URL")), base.getPlanningAgentModelBaseUrl()),
                doubleOrDefault(firstNonNull(llm.get("timeout"), root.get("PLANNING_AGENT_MODEL_TIMEOUT")), base.getPlanningAgentModelTimeout()),
                booleanOrDefault(firstNonNull(llm.get("verifyTls"), root.get("SKILL_LLM_TLS_VERIFY")), base.isSkillLlmTlsVerify()),
                stringOrDefault(firstNonNull(llm.get("token"), root.get("PLANNING_AGENT_MODEL_TOKEN")), base.getPlanningAgentModelToken()),
                stringOrDefault(firstNonNull(llm.get("tokenHeader"), root.get("PLANNING_AGENT_MODEL_TOKEN_HEADER")), base.getPlanningAgentModelTokenHeader()),
                stringOrDefault(firstNonNull(llm.get("userId"), root.get("PLANNING_AGENT_MODEL_USER_ID")), base.getPlanningAgentModelUserId()),
                stringOrDefault(firstNonNull(llm.get("userIdHeader"), root.get("PLANNING_AGENT_MODEL_USER_ID_HEADER")), base.getPlanningAgentModelUserIdHeader()),
                mapStringValue(firstNonNull(llm.get("extraHeaders"), root.get("PLANNING_AGENT_MODEL_EXTRA_HEADERS")), base.getPlanningAgentModelExtraHeaders()),
                stringOrDefault(firstNonNull(workflow.get("adapterUrl"), root.get("VERSATILE_ADAPTER_URL")), base.getVersatileAdapterUrl()),
                doubleOrDefault(firstNonNull(workflow.get("timeoutSeconds"), root.get("VERSATILE_TIMEOUT")), base.getVersatileTimeout())
        );
    }

    private static EdpAgentSettings mergeEnv(EdpAgentSettings base, Map<String, String> env) {
        Map<String, String> values = env != null ? env : Map.of();
        Map<String, String> extraHeaders = new LinkedHashMap<String, String>(base.getPlanningAgentModelExtraHeaders());
        String extraHeadersRaw = value(values, "PLANNING_AGENT_MODEL_EXTRA_HEADERS");
        if (!extraHeadersRaw.isBlank()) {
            extraHeaders = parseStringMap(extraHeadersRaw);
        }

        return new EdpAgentSettings(
                stringOrDefault(value(values, "FASTAPI_HOST"), base.getFastApiHost()),
                intOrDefault(value(values, "FASTAPI_PORT"), base.getFastApiPort()),
                stringOrDefault(value(values, "PLANNING_AGENT_MODEL_NAME"), base.getPlanningAgentModelName()),
                stringOrDefault(value(values, "PLANNING_AGENT_MODEL_API_KEY"), base.getPlanningAgentModelApiKey()),
                stringOrDefault(value(values, "PLANNING_AGENT_MODEL_BASE_URL"), base.getPlanningAgentModelBaseUrl()),
                doubleOrDefault(value(values, "PLANNING_AGENT_MODEL_TIMEOUT"), base.getPlanningAgentModelTimeout()),
                booleanOrDefault(value(values, "SKILL_LLM_TLS_VERIFY"), base.isSkillLlmTlsVerify()),
                stringOrDefault(value(values, "PLANNING_AGENT_MODEL_TOKEN"), base.getPlanningAgentModelToken()),
                stringOrDefault(value(values, "PLANNING_AGENT_MODEL_TOKEN_HEADER"), base.getPlanningAgentModelTokenHeader()),
                stringOrDefault(value(values, "PLANNING_AGENT_MODEL_USER_ID"), base.getPlanningAgentModelUserId()),
                stringOrDefault(value(values, "PLANNING_AGENT_MODEL_USER_ID_HEADER"), base.getPlanningAgentModelUserIdHeader()),
                extraHeaders,
                stringOrDefault(value(values, "VERSATILE_ADAPTER_URL"), base.getVersatileAdapterUrl()),
                doubleOrDefault(value(values, "VERSATILE_TIMEOUT"), base.getVersatileTimeout())
        );
    }

    private static String value(Map<String, String> env, String key) {
        if (env == null) {
            return "";
        }
        String value = env.get(key);
        return value != null ? value : "";
    }

    private static Object firstNonNull(Object left, Object right) {
        return left != null ? left : right;
    }

    private static String stringOrDefault(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? defaultValue : text;
    }

    private static int intOrDefault(Object value, int defaultValue) {
        try {
            return value != null ? Integer.parseInt(String.valueOf(value)) : defaultValue;
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static double doubleOrDefault(Object value, double defaultValue) {
        try {
            return value != null ? Double.parseDouble(String.valueOf(value)) : defaultValue;
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static boolean booleanOrDefault(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String raw = String.valueOf(value);
        if (raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private static Map<String, String> mapStringValue(Object value, Map<String, String> defaultValue) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, String> result = new LinkedHashMap<String, String>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            return parseStringMap(text);
        }
        return defaultValue != null ? new LinkedHashMap<String, String>(defaultValue) : new LinkedHashMap<String, String>();
    }

    private static Map<String, String> parseStringMap(String raw) {
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() { });
            Map<String, String> result = new LinkedHashMap<String, String>();
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            return result;
        } catch (Exception ignored) {
            return new LinkedHashMap<String, String>();
        }
    }

    public String getFastApiHost() {
        return fastApiHost;
    }

    public int getFastApiPort() {
        return fastApiPort;
    }

    public String getPlanningAgentModelName() {
        return planningAgentModelName;
    }

    public String getPlanningAgentModelApiKey() {
        return planningAgentModelApiKey;
    }

    public String getPlanningAgentModelBaseUrl() {
        return planningAgentModelBaseUrl;
    }

    public double getPlanningAgentModelTimeout() {
        return planningAgentModelTimeout;
    }

    public boolean isSkillLlmTlsVerify() {
        return skillLlmTlsVerify;
    }

    public String getPlanningAgentModelToken() {
        return planningAgentModelToken;
    }

    public String getPlanningAgentModelTokenHeader() {
        return planningAgentModelTokenHeader;
    }

    public String getPlanningAgentModelUserId() {
        return planningAgentModelUserId;
    }

    public String getPlanningAgentModelUserIdHeader() {
        return planningAgentModelUserIdHeader;
    }

    public Map<String, String> getPlanningAgentModelExtraHeaders() {
        return new LinkedHashMap<String, String>(planningAgentModelExtraHeaders);
    }

    public String getVersatileAdapterUrl() {
        return versatileAdapterUrl;
    }

    public double getVersatileTimeout() {
        return versatileTimeout;
    }
}
