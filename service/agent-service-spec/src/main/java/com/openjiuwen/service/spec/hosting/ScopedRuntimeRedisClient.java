/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.hosting;

import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Fixed namespace view over a borrowed Redis client. Values and external IDs
 * remain unchanged; scan results are logical keys usable by this same view.
 *
 * @since 0.1.2
 */
public final class ScopedRuntimeRedisClient implements RuntimeRedisClient {
    private final RuntimeRedisClient delegate;

    private final String prefix;

    private final byte[] binaryPrefix;

    /**
     * Creates an immutable namespace view without taking connection ownership.
     *
     * @param delegate shared client, which must not already be scoped
     * @param prefix nonempty literal prefix without Redis glob characters
     */
    public ScopedRuntimeRedisClient(RuntimeRedisClient delegate, String prefix) {
        this.delegate = Objects.requireNonNull(delegate, "Redis delegate is required");
        if (delegate instanceof ScopedRuntimeRedisClient) {
            throw new IllegalArgumentException("Nested hosted Redis scopes are not supported");
        }
        if (prefix == null || prefix.isBlank() || prefix.chars().anyMatch(c -> "*?[]\\".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("Redis scope requires a nonempty literal prefix");
        }
        this.prefix = prefix;
        this.binaryPrefix = prefix.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Constructs the versioned storage prefix, not a conversation identifier.
     *
     * @param applicationName stable application identity
     * @param agentId registered agent identifier
     * @return physical namespace prefix
     */
    public static String namespace(String applicationName, String agentId) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalArgumentException("Hosted runtime application name is required");
        }
        if (!HostedAgentDefinitions.isValidAgentId(agentId)) {
            throw new IllegalArgumentException("Invalid hosted agent ID");
        }
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "runtime:hosted:v1:" + encoder.encodeToString(applicationName.getBytes(StandardCharsets.UTF_8))
                + ":" + encoder.encodeToString(agentId.getBytes(StandardCharsets.UTF_8)) + ":";
    }

    @Override
    public Object get(String key) {
        return delegate.get(physical(key));
    }

    @Override
    public byte[] get(byte[] key) {
        return delegate.get(physical(key));
    }

    @Override
    public String set(String key, String value) {
        return delegate.set(physical(key), value);
    }

    @Override
    public String set(String key, byte[] value) {
        return delegate.set(physical(key), value);
    }

    @Override
    public String set(byte[] key, byte[] value) {
        return delegate.set(physical(key), value);
    }

    @Override
    public String setex(String key, long seconds, String value) {
        return delegate.setex(physical(key), seconds, value);
    }

    @Override
    public String setex(byte[] key, long seconds, byte[] value) {
        return delegate.setex(physical(key), seconds, value);
    }

    @Override
    public long setnx(String key, String value) {
        return delegate.setnx(physical(key), value);
    }

    @Override
    public long setnx(byte[] key, byte[] value) {
        return delegate.setnx(physical(key), value);
    }

    @Override
    public long del(String... keys) {
        return delegate.del(physical(keys));
    }

    @Override
    public long del(byte[]... keys) {
        byte[][] mapped = new byte[keys.length][];
        for (int i = 0; i < keys.length; i++) {
            mapped[i] = physical(keys[i]);
        }
        return delegate.del(mapped);
    }

    @Override
    public boolean exists(String key) {
        return delegate.exists(physical(key));
    }

    @Override
    public boolean exists(byte[] key) {
        return delegate.exists(physical(key));
    }

    @Override
    public long expire(String key, long seconds) {
        return delegate.expire(physical(key), seconds);
    }

    @Override
    public long expire(byte[] key, long seconds) {
        return delegate.expire(physical(key), seconds);
    }

    @Override
    public List<Object> mget(String... keys) {
        return delegate.mget(physical(keys));
    }

    @Override
    public List<String> scanIter(String pattern) {
        List<String> logicalKeys = new ArrayList<>();
        for (String key : delegate.scanIter(physical(pattern))) {
            if (key == null || !key.startsWith(prefix)) {
                throw new IllegalStateException("Redis scan returned a key outside its hosted scope");
            }
            logicalKeys.add(key.substring(prefix.length()));
        }
        return logicalKeys;
    }

    @Override
    public void close() {
        // The process owns the shared delegate; closing a view must not close it.
    }

    private String physical(String key) {
        return prefix + Objects.requireNonNull(key, "Redis key is required");
    }

    private byte[] physical(byte[] key) {
        Objects.requireNonNull(key, "Redis key is required");
        byte[] mapped = Arrays.copyOf(binaryPrefix, binaryPrefix.length + key.length);
        System.arraycopy(key, 0, mapped, binaryPrefix.length, key.length);
        return mapped;
    }

    private String[] physical(String[] keys) {
        String[] mapped = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            mapped[i] = physical(keys[i]);
        }
        return mapped;
    }
}
