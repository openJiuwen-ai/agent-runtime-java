/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.spi;

import java.util.List;

/**
 * Runtime Redis command facade shared by service components that need Redis-backed middleware.
 *
 * <p>The method names intentionally match the command surface consumed by agent-core's reflection-based Redis store.
 * Implementations should be thread-safe when registered as a Spring singleton.
 *
 * @since 0.1.0
 */
public interface RuntimeRedisClient extends AutoCloseable {
    /**
     * Gets a value by text key.
     *
     * @param key redis key
     * @return the value, commonly {@link String} or {@code byte[]}, or {@code null}
     */
    Object get(String key);

    /**
     * Gets a binary value by binary key.
     *
     * @param key redis key bytes
     * @return value bytes, or {@code null}
     */
    byte[] get(byte[] key);

    /**
     * Sets a text value by text key.
     *
     * @param key redis key
     * @param value redis value
     * @return redis status
     */
    String set(String key, String value);

    /**
     * Sets a binary value by text key.
     *
     * @param key redis key
     * @param value redis value bytes
     * @return redis status
     */
    String set(String key, byte[] value);

    /**
     * Sets a binary value by binary key.
     *
     * @param key redis key bytes
     * @param value redis value bytes
     * @return redis status
     */
    String set(byte[] key, byte[] value);

    /**
     * Sets a text value with TTL.
     *
     * @param key redis key
     * @param seconds TTL seconds
     * @param value redis value
     * @return redis status
     */
    String setex(String key, long seconds, String value);

    /**
     * Sets a binary value with TTL.
     *
     * @param key redis key bytes
     * @param seconds TTL seconds
     * @param value redis value bytes
     * @return redis status
     */
    String setex(byte[] key, long seconds, byte[] value);

    /**
     * Sets a text value only when the key does not exist.
     *
     * @param key redis key
     * @param value redis value
     * @return 1 when set, otherwise 0
     */
    long setnx(String key, String value);

    /**
     * Sets a binary value only when the key does not exist.
     *
     * @param key redis key bytes
     * @param value redis value bytes
     * @return 1 when set, otherwise 0
     */
    long setnx(byte[] key, byte[] value);

    /**
     * Deletes text keys.
     *
     * @param keys redis keys
     * @return deleted count
     */
    long del(String... keys);

    /**
     * Deletes binary keys.
     *
     * @param keys redis key bytes
     * @return deleted count
     */
    long del(byte[]... keys);

    /**
     * Checks a text key.
     *
     * @param key redis key
     * @return true when the key exists
     */
    boolean exists(String key);

    /**
     * Checks a binary key.
     *
     * @param key redis key bytes
     * @return true when the key exists
     */
    boolean exists(byte[] key);

    /**
     * Refreshes a text key TTL.
     *
     * @param key redis key
     * @param seconds TTL seconds
     * @return 1 when TTL was set, otherwise 0
     */
    long expire(String key, long seconds);

    /**
     * Refreshes a binary key TTL.
     *
     * @param key redis key bytes
     * @param seconds TTL seconds
     * @return 1 when TTL was set, otherwise 0
     */
    long expire(byte[] key, long seconds);

    /**
     * Gets multiple text keys.
     *
     * @param keys redis keys
     * @return ordered values
     */
    List<Object> mget(String... keys);

    /**
     * Scans keys matching a Redis glob pattern.
     *
     * @param pattern redis glob pattern
     * @return matching keys
     */
    List<String> scanIter(String pattern);

    @Override
    default void close() {
        // Implementations that own resources should override.
    }
}
