package com.openjiuwen.a2a_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A2A Service 配置。
 */
@ConfigurationProperties(prefix = "a2a-service")
public class Settings {

    // ── App ─────────────────────────────────────────────────────────────────
    private String appName = "A2A Service";

    // ── Redis（会话状态）────────────────────────────────────────────────────
    private String redisHost = "localhost";
    private int redisPort = 6379;
    private int redisDb = 0;
    private String redisPassword;
    private int redisSessionTtl = 1800;

    // ── 入口限流 ───────────────────────────────────────────────────────────
    private int rateLimitMaxRequests = 1;
    private int rateLimitWindowSeconds = 10;
    private int globalRateLimitMaxRequests = 10;
    private int globalRateLimitWindowSeconds = 10;

    // ── VersatileAdapter（内部 A2A 服务地址）────────────────────────────────
    private String versatileAdapterUrl = "http://localhost:8091";
    private String vaWorkflowResultNode = "GXZQAResponseNode";

    // ── Server ─────────────────────────────────────────────────────────────
    private String host = "0.0.0.0";
    private int port = 8090;

    /**
     * 拼接 Redis 连接 URL（密码含特殊字符时自动转义）。
     */
    public String getRedisUrl() {
        if (redisPassword != null && !redisPassword.isEmpty()) {
            String encodedPwd = java.net.URLEncoder.encode(redisPassword, java.nio.charset.StandardCharsets.UTF_8);
            return String.format("redis://:%s@%s:%d/%d", encodedPwd, redisHost, redisPort, redisDb);
        }
        return String.format("redis://%s:%d/%d", redisHost, redisPort, redisDb);
    }

    // Getters and Setters
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getRedisHost() { return redisHost; }
    public void setRedisHost(String redisHost) { this.redisHost = redisHost; }

    public int getRedisPort() { return redisPort; }
    public void setRedisPort(int redisPort) { this.redisPort = redisPort; }

    public int getRedisDb() { return redisDb; }
    public void setRedisDb(int redisDb) { this.redisDb = redisDb; }

    public String getRedisPassword() { return redisPassword; }
    public void setRedisPassword(String redisPassword) { this.redisPassword = redisPassword; }

    public int getRedisSessionTtl() { return redisSessionTtl; }
    public void setRedisSessionTtl(int redisSessionTtl) { this.redisSessionTtl = redisSessionTtl; }

    public int getRateLimitMaxRequests() { return rateLimitMaxRequests; }
    public void setRateLimitMaxRequests(int rateLimitMaxRequests) { this.rateLimitMaxRequests = rateLimitMaxRequests; }

    public int getRateLimitWindowSeconds() { return rateLimitWindowSeconds; }
    public void setRateLimitWindowSeconds(int rateLimitWindowSeconds) { this.rateLimitWindowSeconds = rateLimitWindowSeconds; }

    public int getGlobalRateLimitMaxRequests() { return globalRateLimitMaxRequests; }
    public void setGlobalRateLimitMaxRequests(int globalRateLimitMaxRequests) { this.globalRateLimitMaxRequests = globalRateLimitMaxRequests; }

    public int getGlobalRateLimitWindowSeconds() { return globalRateLimitWindowSeconds; }
    public void setGlobalRateLimitWindowSeconds(int globalRateLimitWindowSeconds) { this.globalRateLimitWindowSeconds = globalRateLimitWindowSeconds; }

    public String getVersatileAdapterUrl() { return versatileAdapterUrl; }
    public void setVersatileAdapterUrl(String versatileAdapterUrl) { this.versatileAdapterUrl = versatileAdapterUrl; }

    public String getVaWorkflowResultNode() { return vaWorkflowResultNode; }
    public void setVaWorkflowResultNode(String vaWorkflowResultNode) { this.vaWorkflowResultNode = vaWorkflowResultNode; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
}
