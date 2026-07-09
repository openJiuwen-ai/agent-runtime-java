/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared {@link RuntimeRedisClient} command mapping for Jedis-based clients.
 *
 * @since 0.1.0
 */
class UnifiedJedisRuntimeRedisClient implements RuntimeRedisClient {
    static final int SCAN_COUNT = 100;

    private final UnifiedJedis delegate;

    UnifiedJedisRuntimeRedisClient(UnifiedJedis delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public Object get(String key) {
        return delegate.get(toBytes(key));
    }

    @Override
    public byte[] get(byte[] key) {
        return delegate.get(key);
    }

    @Override
    public String set(String key, String value) {
        return delegate.set(key, value);
    }

    @Override
    public String set(String key, byte[] value) {
        return set(toBytes(key), value);
    }

    @Override
    public String set(byte[] key, byte[] value) {
        return delegate.set(key, value);
    }

    @Override
    public String setex(String key, long seconds, String value) {
        return setex(toBytes(key), seconds, toBytes(value));
    }

    @Override
    public String setex(byte[] key, long seconds, byte[] value) {
        return delegate.setex(key, seconds, value);
    }

    @Override
    public long setnx(String key, String value) {
        return setnx(toBytes(key), toBytes(value));
    }

    @Override
    public long setnx(byte[] key, byte[] value) {
        return delegate.setnx(key, value);
    }

    @Override
    public long del(String... keys) {
        return delegate.del(keys);
    }

    @Override
    public long del(byte[]... keys) {
        return delegate.del(keys);
    }

    @Override
    public boolean exists(String key) {
        return delegate.exists(key);
    }

    @Override
    public boolean exists(byte[] key) {
        return delegate.exists(key);
    }

    @Override
    public long expire(String key, long seconds) {
        return delegate.expire(key, seconds);
    }

    @Override
    public long expire(byte[] key, long seconds) {
        return delegate.expire(key, seconds);
    }

    @Override
    public List<Object> mget(String... keys) {
        List<byte[]> binaryKeys = new ArrayList<>(keys.length);
        for (String key : keys) {
            binaryKeys.add(toBytes(key));
        }
        List<Object> values = new ArrayList<>(keys.length);
        values.addAll(delegate.mget(binaryKeys.toArray(byte[][]::new)));
        return values;
    }

    @Override
    public List<String> scanIter(String pattern) {
        List<String> keys = new ArrayList<>();
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams params = new ScanParams().match(pattern).count(SCAN_COUNT);
        do {
            var result = delegate.scan(cursor, params);
            keys.addAll(result.getResult());
            cursor = result.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        return keys;
    }

    @Override
    public void close() {
        delegate.close();
    }

    byte[] toBytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
