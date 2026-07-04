/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
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

    public Map<String, RedisEndpoint> getRedis() {
        return redis;
    }

    public void setRedis(Map<String, RedisEndpoint> redis) {
        this.redis = redis != null ? redis : new HashMap<>();
    }

    /** Checkpointer configuration. */
    public static class Checkpointer {
        private String type = "in_memory";
        private String redisRef = "default";

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
        private String host = "localhost";
        private int port = 6379;
        private int database = 0;
        private int timeoutMs = 3000;
        private String encryptedPassword = "";

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
}
