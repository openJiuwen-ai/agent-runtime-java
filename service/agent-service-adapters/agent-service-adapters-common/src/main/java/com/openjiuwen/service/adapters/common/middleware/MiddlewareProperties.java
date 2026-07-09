/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware;

import com.openjiuwen.service.adapters.common.external.ExternalAuditPolicy;
import com.openjiuwen.service.adapters.common.external.ExternalCallPolicy;
import com.openjiuwen.service.adapters.common.external.ExternalCircuitBreakerPolicy;
import com.openjiuwen.service.adapters.common.external.ExternalRetryPolicy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code openjiuwen.service.middleware.*} for checkpointer, Redis
 * endpoints, and P2 placeholders.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "openjiuwen.service.middleware")
public class MiddlewareProperties {
    private Checkpointer checkpointer = new Checkpointer();

    private CapabilityPlaceholder sessionStore = new CapabilityPlaceholder();

    private CapabilityPlaceholder objectStorage = new CapabilityPlaceholder();

    private CapabilityPlaceholder vectorStore = new CapabilityPlaceholder();

    private Memory memory = new Memory();

    private Map<String, RedisEndpoint> redis = new HashMap<>();

    public Checkpointer getCheckpointer() {
        return checkpointer;
    }

    public void setCheckpointer(Checkpointer checkpointer) {
        this.checkpointer = checkpointer != null ? checkpointer : new Checkpointer();
    }

    public CapabilityPlaceholder getSessionStore() {
        return sessionStore;
    }

    public void setSessionStore(CapabilityPlaceholder sessionStore) {
        this.sessionStore = sessionStore != null ? sessionStore : new CapabilityPlaceholder();
    }

    public CapabilityPlaceholder getObjectStorage() {
        return objectStorage;
    }

    public void setObjectStorage(CapabilityPlaceholder objectStorage) {
        this.objectStorage = objectStorage != null ? objectStorage : new CapabilityPlaceholder();
    }

    public CapabilityPlaceholder getVectorStore() {
        return vectorStore;
    }

    public void setVectorStore(CapabilityPlaceholder vectorStore) {
        this.vectorStore = vectorStore != null ? vectorStore : new CapabilityPlaceholder();
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory != null ? memory : new Memory();
    }

    public Map<String, RedisEndpoint> getRedis() {
        return redis;
    }

    public void setRedis(Map<String, RedisEndpoint> redis) {
        this.redis = redis != null ? redis : new HashMap<>();
    }

    /** Checkpointer configuration. */
    public static class Checkpointer {
        /** Default state cache TTL: seven days. */
        public static final long DEFAULT_TTL_SECONDS = 604800L;

        private String type = "in_memory";

        private String redisRef = "default";

        private long ttlSeconds = DEFAULT_TTL_SECONDS;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getRedisRef() {
            return redisRef;
        }

        public void setRedisRef(String redisRef) {
            this.redisRef = redisRef;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            if (ttlSeconds <= 0) {
                throw new IllegalArgumentException(
                        "openjiuwen.service.middleware.checkpointer.ttl-seconds must be " + "greater than 0");
            }
            this.ttlSeconds = ttlSeconds;
        }
    }

    /** Placeholder for future middleware capabilities. */
    public static class CapabilityPlaceholder {
        private String type = "none";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    /** Redis endpoint connection settings. */
    public static class RedisEndpoint {
        private String type = "standalone";

        private String host = "localhost";

        private int port = 6379;

        private List<String> nodes = new ArrayList<>();

        private int database = 0;

        private int timeoutMs = 3000;

        private String encryptedPassword = "";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type != null ? type : "standalone";
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public List<String> getNodes() {
            return nodes;
        }

        public void setNodes(List<String> nodes) {
            this.nodes = nodes != null ? new ArrayList<>(nodes) : new ArrayList<>();
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getEncryptedPassword() {
            return encryptedPassword;
        }

        public void setEncryptedPassword(String encryptedPassword) {
            this.encryptedPassword = encryptedPassword != null ? encryptedPassword : "";
        }
    }

    /**
     * Long-term memory (mem0) configuration.
     *
     * <p>Implements {@link ExternalCallPolicy} so the governance timeout/retry/circuit/audit
     * settings map directly into the runtime {@code ExternalCallExecutor}.
     * {@code enabled=true} exposes a governed runtime {@code MemoryStore} bean; when and how
     * Agents call search/add/get/delete is the consumer's responsibility.
     */
    public static class Memory implements ExternalCallPolicy {
        private boolean enabled = false;

        private String provider = "mem0";

        private String endpoint = "https://api.mem0.ai";

        private String encryptedApiKey = "";

        private String userId = "";

        private boolean requestScopedSession = false;

        private boolean rerank = false;

        private int timeoutMs = 3000;

        private ExternalRetryPolicy retry = new ExternalRetryPolicy();

        private ExternalCircuitBreakerPolicy circuitBreaker = new ExternalCircuitBreakerPolicy();

        private ExternalAuditPolicy audit = new ExternalAuditPolicy();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider != null && !provider.isBlank() ? provider : "mem0";
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint != null && !endpoint.isBlank() ? endpoint : "https://api.mem0.ai";
        }

        public String getEncryptedApiKey() {
            return encryptedApiKey;
        }

        public void setEncryptedApiKey(String encryptedApiKey) {
            this.encryptedApiKey = encryptedApiKey != null ? encryptedApiKey : "";
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId != null ? userId : "";
        }

        public boolean isRequestScopedSession() {
            return requestScopedSession;
        }

        public void setRequestScopedSession(boolean requestScopedSession) {
            this.requestScopedSession = requestScopedSession;
        }

        public boolean isRerank() {
            return rerank;
        }

        public void setRerank(boolean rerank) {
            this.rerank = rerank;
        }

        @Override
        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("memory.timeout-ms must be greater than zero");
            }
            this.timeoutMs = timeoutMs;
        }

        @Override
        public ExternalRetryPolicy getRetry() {
            return retry;
        }

        public void setRetry(ExternalRetryPolicy retry) {
            this.retry = retry != null ? retry : new ExternalRetryPolicy();
        }

        @Override
        public ExternalCircuitBreakerPolicy getCircuitBreaker() {
            return circuitBreaker;
        }

        public void setCircuitBreaker(ExternalCircuitBreakerPolicy circuitBreaker) {
            this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new ExternalCircuitBreakerPolicy();
        }

        @Override
        public ExternalAuditPolicy getAudit() {
            return audit;
        }

        public void setAudit(ExternalAuditPolicy audit) {
            this.audit = audit != null ? audit : new ExternalAuditPolicy();
        }
    }
}
