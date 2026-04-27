package com.openjiuwen.a2a_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DPA Agent 配置。
 */
@ConfigurationProperties(prefix = "dpa")
@Data
public class DPASettings {

    // ── LLM ─────────────────────────────────────────────────────────────────
    private String llmProvider;
    private String llmApiBase;
    private String llmApiKey;
    private String llmModelName;
    private boolean llmVerifySsl = true;
    private String llmUserId;
    private String llmUserIdHeader;
    private String llmToken;
    private String llmTokenHeader;
    private double llmTimeout = 120.0d;
    private Map<String, String> llmExtraHeaders = new LinkedHashMap<String, String>();

    // ── Redis（Checkpointer）────────────────────────────────────────────────
    private String redisHost = "localhost";
    private int redisPort = 6379;
    private int redisDb = 0;
    private String redisPassword;
    private int redisCheckpointerTtlMinutes = 30;

    // ── DPA Agent ───────────────────────────────────────────────────────────
    private String dpaAgentId = "dpa_agent";
    private String dpaAgentName = "DPA Agent";
    private int dpaMaxIterations = 10;

    /**
     * 拼接 Redis 连接 URL。
     */
    public String getRedisUrl() {
        if (redisPassword != null && !redisPassword.isEmpty()) {
            String encodedPwd = java.net.URLEncoder.encode(redisPassword, java.nio.charset.StandardCharsets.UTF_8);
            return String.format("redis://:%s@%s:%d/%d", encodedPwd, redisHost, redisPort, redisDb);
        }
        return String.format("redis://%s:%d/%d", redisHost, redisPort, redisDb);
    }

    // Getters and Setters
    public String getLlmProvider() { return llmProvider; }
    public void setLlmProvider(String llmProvider) { this.llmProvider = llmProvider; }

    public String getLlmApiBase() { return llmApiBase; }
    public void setLlmApiBase(String llmApiBase) { this.llmApiBase = llmApiBase; }

    public String getLlmApiKey() { return llmApiKey; }
    public void setLlmApiKey(String llmApiKey) { this.llmApiKey = llmApiKey; }

    public String getLlmModelName() { return llmModelName; }
    public void setLlmModelName(String llmModelName) { this.llmModelName = llmModelName; }

    public boolean isLlmVerifySsl() { return llmVerifySsl; }
    public void setLlmVerifySsl(boolean llmVerifySsl) { this.llmVerifySsl = llmVerifySsl; }

    public String getLlmUserId() { return llmUserId; }
    public void setLlmUserId(String llmUserId) { this.llmUserId = llmUserId; }

    public String getLlmUserIdHeader() { return llmUserIdHeader; }
    public void setLlmUserIdHeader(String llmUserIdHeader) { this.llmUserIdHeader = llmUserIdHeader; }

    public String getLlmToken() { return llmToken; }
    public void setLlmToken(String llmToken) { this.llmToken = llmToken; }

    public String getLlmTokenHeader() { return llmTokenHeader; }
    public void setLlmTokenHeader(String llmTokenHeader) { this.llmTokenHeader = llmTokenHeader; }

    public double getLlmTimeout() { return llmTimeout; }
    public void setLlmTimeout(double llmTimeout) { this.llmTimeout = llmTimeout; }

    public Map<String, String> getLlmExtraHeaders() { return llmExtraHeaders; }
    public void setLlmExtraHeaders(Map<String, String> llmExtraHeaders) { this.llmExtraHeaders = llmExtraHeaders; }

    public String getRedisHost() { return redisHost; }
    public void setRedisHost(String redisHost) { this.redisHost = redisHost; }

    public int getRedisPort() { return redisPort; }
    public void setRedisPort(int redisPort) { this.redisPort = redisPort; }

    public int getRedisDb() { return redisDb; }
    public void setRedisDb(int redisDb) { this.redisDb = redisDb; }

    public String getRedisPassword() { return redisPassword; }
    public void setRedisPassword(String redisPassword) { this.redisPassword = redisPassword; }

    public int getRedisCheckpointerTtlMinutes() { return redisCheckpointerTtlMinutes; }
    public void setRedisCheckpointerTtlMinutes(int redisCheckpointerTtlMinutes) { this.redisCheckpointerTtlMinutes = redisCheckpointerTtlMinutes; }

    public String getDpaAgentId() { return dpaAgentId; }
    public void setDpaAgentId(String dpaAgentId) { this.dpaAgentId = dpaAgentId; }

    public String getDpaAgentName() { return dpaAgentName; }
    public void setDpaAgentName(String dpaAgentName) { this.dpaAgentName = dpaAgentName; }

    public int getDpaMaxIterations() { return dpaMaxIterations; }
    public void setDpaMaxIterations(int dpaMaxIterations) { this.dpaMaxIterations = dpaMaxIterations; }
}
