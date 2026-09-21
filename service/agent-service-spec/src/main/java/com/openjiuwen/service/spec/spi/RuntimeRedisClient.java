/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.spi;

import java.util.List;
import java.util.Map;

/**
 * Runtime Redis command facade shared by service components that need Redis-backed middleware.
 *
 * <p>The method names intentionally match the command surface consumed by agent-core's reflection-based Redis store.
 * Implementations should be thread-safe when registered as a Spring singleton.
 *
 * <p>Method batches: the first seventeen methods are the frozen contract consumed by agent-core's
 * reflection-based Redis store by name, so their names and signatures must not change. The structured
 * command batch (hset, hget, hdel, hgetAll, sadd, sismember, srem, hincrBy) is added only for real
 * callers; hincrBy must stay atomic under concurrent instances. eval carries compare-and-set style
 * atomic semantics that no single command expresses. No ping command is exposed because pool
 * borrow-time validation and broken-connection eviction already make recovery transparent.
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

    /**
     * Sets a hash field value.
     *
     * @param key redis key
     * @param field hash field
     * @param value field value
     * @return 1 when a new field was created, otherwise 0
     */
    long hset(String key, String field, String value);

    /**
     * Gets a hash field value.
     *
     * @param key redis key
     * @param field hash field
     * @return the field value, or {@code null} when absent
     */
    String hget(String key, String field);

    /**
     * Deletes hash fields.
     *
     * @param key redis key
     * @param fields hash fields
     * @return deleted field count
     */
    long hdel(String key, String... fields);

    /**
     * Gets all fields of a hash.
     *
     * @param key redis key
     * @return field to value map, empty when the key is absent
     */
    Map<String, String> hgetAll(String key);

    /**
     * Adds set members.
     *
     * @param key redis key
     * @param members set members
     * @return newly added member count
     */
    long sadd(String key, String... members);

    /**
     * Checks set membership.
     *
     * @param key redis key
     * @param member set member
     * @return true when the member is present
     */
    boolean sismember(String key, String member);

    /**
     * Removes set members.
     *
     * @param key redis key
     * @param members set members
     * @return removed member count
     */
    long srem(String key, String... members);

    /**
     * Increments a hash field atomically; concurrent increments from any instance must all be reflected.
     *
     * @param key redis key
     * @param field hash field
     * @param delta increment delta
     * @return the field value after the increment
     */
    long hincrBy(String key, String field, long delta);

    /**
     * Evaluates a Lua script for compare-and-set style atomic semantics that single commands
     * cannot express, such as conditional hash writes with a multi-state outcome.
     *
     * <p>Scripts must reference exactly one key so cluster deployments route by slot; callers
     * coerce the returned object themselves (commonly {@link Number}). Implementations that
     * cannot execute scripts must fail loudly instead of degrading to a non-atomic equivalent.
     * EVALSHA is deliberately not modelled to avoid the script-not-loaded failure mode.
     *
     * @param script Lua script text
     * @param keys keys referenced by the script
     * @param args additional script arguments
     * @return the script result, whose type depends on the script
     */
    Object eval(String script, List<String> keys, String... args);

    @Override
    default void close() {
        // Implementations that own resources should override.
    }
}
