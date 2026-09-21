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
 * <p>The structured batch and eval are {@code default} methods that fail loudly by throwing
 * {@link UnsupportedOperationException}: implementations may adopt them incrementally, and
 * implementations written against the frozen seventeen stay source- and binary-compatible as
 * this interface evolves. A default invocation means the caller asked for a command the
 * implementation cannot serve, so throwing beats any silent non-atomic substitute.
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
     * <p>The default implementation throws {@link UnsupportedOperationException}; implementations
     * that support hash commands override it.
     *
     * @param key redis key
     * @param field hash field
     * @param value field value
     * @return 1 when a new field was created, otherwise 0
     */
    default long hset(String key, String field, String value) {
        throw new UnsupportedOperationException("hset not supported by this RuntimeRedisClient implementation");
    }

    /**
     * Gets a hash field value.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}; implementations
     * that support hash commands override it.
     *
     * @param key redis key
     * @param field hash field
     * @return the field value, or {@code null} when absent
     */
    default String hget(String key, String field) {
        throw new UnsupportedOperationException("hget not supported by this RuntimeRedisClient implementation");
    }

    /**
     * Deletes hash fields.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}; implementations
     * that support hash commands override it.
     *
     * @param key redis key
     * @param fields hash fields
     * @return deleted field count
     */
    default long hdel(String key, String... fields) {
        throw new UnsupportedOperationException("hdel not supported by this RuntimeRedisClient implementation");
    }

    /**
     * Gets all fields of a hash.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}; implementations
     * that support hash commands override it.
     *
     * @param key redis key
     * @return field to value map, empty when the key is absent
     */
    default Map<String, String> hgetAll(String key) {
        throw new UnsupportedOperationException("hgetAll not supported by this RuntimeRedisClient implementation");
    }

    /**
     * Adds set members.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}; implementations
     * that support set commands override it.
     *
     * @param key redis key
     * @param members set members
     * @return newly added member count
     */
    default long sadd(String key, String... members) {
        throw new UnsupportedOperationException("sadd not supported by this RuntimeRedisClient implementation");
    }

    /**
     * Checks set membership.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}; implementations
     * that support set commands override it.
     *
     * @param key redis key
     * @param member set member
     * @return true when the member is present
     */
    default boolean sismember(String key, String member) {
        throw new UnsupportedOperationException("sismember not supported by this RuntimeRedisClient implementation");
    }

    /**
     * Removes set members.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}; implementations
     * that support set commands override it.
     *
     * @param key redis key
     * @param members set members
     * @return removed member count
     */
    default long srem(String key, String... members) {
        throw new UnsupportedOperationException("srem not supported by this RuntimeRedisClient implementation");
    }

    /**
     * Increments a hash field atomically; concurrent increments from any instance must all be reflected.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException} because no
     * non-atomic substitute may stand in; implementations that support hash commands override it.
     *
     * @param key redis key
     * @param field hash field
     * @param delta increment delta
     * @return the field value after the increment
     */
    default long hincrBy(String key, String field, long delta) {
        throw new UnsupportedOperationException("hincrBy not supported by this RuntimeRedisClient implementation");
    }

    /**
     * Evaluates a Lua script for compare-and-set style atomic semantics that single commands
     * cannot express, such as conditional hash writes with a multi-state outcome.
     *
     * <p>Scripts must reference exactly one key so cluster deployments route by slot; callers
     * coerce the returned object themselves (commonly {@link Number}). The default implementation
     * fails loudly by throwing {@link UnsupportedOperationException} instead of degrading to a
     * non-atomic equivalent; server-backed implementations override it. EVALSHA is deliberately
     * not modelled to avoid the script-not-loaded failure mode.
     *
     * @param script Lua script text
     * @param keys keys referenced by the script
     * @param args additional script arguments
     * @return the script result, whose type depends on the script
     */
    default Object eval(String script, List<String> keys, String... args) {
        throw new UnsupportedOperationException("eval not supported by this RuntimeRedisClient implementation;"
                + " deploy a server-backed implementation when atomic script semantics are required");
    }

    @Override
    default void close() {
        // Implementations that own resources should override.
    }
}
