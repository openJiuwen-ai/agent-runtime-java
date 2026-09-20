/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.redis.inmemory;

import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * In-memory {@link RuntimeRedisClient} plugin example.
 *
 * <p>Emulates Redis in a single process-local keyspace. Keys are canonically stored as bytes:
 * a text key is its UTF-8 encoding, so text and binary views address the same data, and
 * byte sequences that are not valid UTF-8 stay distinct instead of collapsing into the same
 * text. Every key holds exactly one typed value (string, hash or set), so string, hash and
 * set commands share Redis unified-keyspace semantics: setnx probes the whole keyspace, SET
 * replaces the value of any type, and commands targeting a mismatched type fail with a
 * WRONGTYPE error. hincrBy rejects non-integer values and overflow like the Redis server.
 * Expiry timestamps are evaluated lazily on access. State is process-bound: nothing survives
 * a restart, and eval is not approximated because atomic Lua semantics cannot be emulated in
 * memory.
 *
 * @since 0.1.3
 */
public final class InMemoryRuntimeRedisClient implements RuntimeRedisClient {
    private static final String REGEX_META = "\\.^$|()[]{}+";
    private static final String WRONG_TYPE_MESSAGE =
            "WRONGTYPE Operation against a key holding the wrong kind of value";

    private final Map<RedisKey, Value> keyspace = new ConcurrentHashMap<>();

    private final Map<RedisKey, Long> expiryMillis = new ConcurrentHashMap<>();

    @Override
    public Object get(String key) {
        Value value = valueAt(key(key));
        return value == null ? null : new String(value.asString(), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] get(byte[] key) {
        Value value = valueAt(key(key));
        if (value == null) {
            return null;
        }
        byte[] data = value.asString();
        return Arrays.copyOf(data, data.length);
    }

    @Override
    public String set(String key, String value) {
        Objects.requireNonNull(value, "Redis value is required");
        return storeString(key(key), value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String set(String key, byte[] value) {
        return storeString(key(key), copyOf(value));
    }

    @Override
    public String set(byte[] key, byte[] value) {
        return storeString(key(key), copyOf(value));
    }

    @Override
    public String setex(String key, long seconds, String value) {
        Objects.requireNonNull(value, "Redis value is required");
        RedisKey redisKey = key(key);
        keyspace.put(redisKey, Value.ofString(value.getBytes(StandardCharsets.UTF_8)));
        expiryMillis.put(redisKey, deadline(seconds));
        return "OK";
    }

    @Override
    public String setex(byte[] key, long seconds, byte[] value) {
        RedisKey redisKey = key(key);
        keyspace.put(redisKey, Value.ofString(copyOf(value)));
        expiryMillis.put(redisKey, deadline(seconds));
        return "OK";
    }

    @Override
    public long setnx(String key, String value) {
        Objects.requireNonNull(value, "Redis value is required");
        return setIfAbsent(key(key), value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public long setnx(byte[] key, byte[] value) {
        return setIfAbsent(key(key), copyOf(value));
    }

    @Override
    public long del(String... keys) {
        long deleted = 0L;
        for (String key : keys) {
            if (removeKey(key(key))) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public long del(byte[]... keys) {
        long deleted = 0L;
        for (byte[] key : keys) {
            if (removeKey(key(key))) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public boolean exists(String key) {
        return valueAt(key(key)) != null;
    }

    @Override
    public boolean exists(byte[] key) {
        return valueAt(key(key)) != null;
    }

    @Override
    public long expire(String key, long seconds) {
        return expireKey(key(key), seconds);
    }

    @Override
    public long expire(byte[] key, long seconds) {
        return expireKey(key(key), seconds);
    }

    @Override
    public List<Object> mget(String... keys) {
        List<Object> values = new ArrayList<>(keys.length);
        for (String key : keys) {
            values.add(get(key));
        }
        return values;
    }

    @Override
    public List<String> scanIter(String pattern) {
        Pattern regex = compileGlob(Objects.requireNonNull(pattern, "Redis pattern is required"));
        List<String> matches = new ArrayList<>();
        for (RedisKey key : new ArrayList<>(keyspace.keySet())) {
            purgeIfExpired(key);
            if (!keyspace.containsKey(key)) {
                continue;
            }
            String textKey = new String(key.bytes(), StandardCharsets.UTF_8);
            if (regex.matcher(textKey).matches()) {
                matches.add(textKey);
            }
        }
        return matches;
    }

    @Override
    public long hset(String key, String field, String value) {
        Objects.requireNonNull(field, "Redis field is required");
        Objects.requireNonNull(value, "Redis value is required");
        Map<String, String> hash = hashAt(key(key));
        return hash.put(field, value) == null ? 1L : 0L;
    }

    @Override
    public String hget(String key, String field) {
        Objects.requireNonNull(field, "Redis field is required");
        Value value = valueAt(key(key));
        return value == null ? null : value.asHash().get(field);
    }

    @Override
    public long hdel(String key, String... fields) {
        Value value = valueAt(key(key));
        if (value == null) {
            return 0L;
        }
        Map<String, String> hash = value.asHash();
        long removed = 0L;
        for (String field : fields) {
            if (hash.remove(field) != null) {
                removed++;
            }
        }
        dropKeyWhenEmpty(key(key));
        return removed;
    }

    @Override
    public Map<String, String> hgetAll(String key) {
        Value value = valueAt(key(key));
        return value == null ? Map.of() : Map.copyOf(value.asHash());
    }

    @Override
    public long sadd(String key, String... members) {
        Set<String> bucket = setAt(key(key));
        long added = 0L;
        for (String member : members) {
            if (bucket.add(member)) {
                added++;
            }
        }
        return added;
    }

    @Override
    public boolean sismember(String key, String member) {
        Value value = valueAt(key(key));
        return value != null && value.asSet().contains(member);
    }

    @Override
    public long srem(String key, String... members) {
        Value value = valueAt(key(key));
        if (value == null) {
            return 0L;
        }
        Set<String> bucket = value.asSet();
        long removed = 0L;
        for (String member : members) {
            if (bucket.remove(member)) {
                removed++;
            }
        }
        dropKeyWhenEmpty(key(key));
        return removed;
    }

    @Override
    public long hincrBy(String key, String field, long delta) {
        Objects.requireNonNull(field, "Redis field is required");
        Map<String, String> hash = hashAt(key(key));
        String updated = hash.compute(field, (ignored, current) -> incremented(current, delta));
        return Long.parseLong(updated);
    }

    @Override
    public Object eval(String script, List<String> keys, String... args) {
        throw new UnsupportedOperationException(
                "InMemoryRuntimeRedisClient cannot evaluate Lua scripts; deploy a server-backed"
                        + " RuntimeRedisClient when atomic script semantics are required");
    }

    @Override
    public void close() {
        keyspace.clear();
        expiryMillis.clear();
    }

    private String storeString(RedisKey key, byte[] value) {
        keyspace.put(key, Value.ofString(value));
        expiryMillis.remove(key);
        return "OK";
    }

    private long setIfAbsent(RedisKey key, byte[] value) {
        purgeIfExpired(key);
        return keyspace.putIfAbsent(key, Value.ofString(value)) == null ? 1L : 0L;
    }

    private boolean removeKey(RedisKey key) {
        purgeIfExpired(key);
        boolean removed = keyspace.remove(key) != null;
        if (removed) {
            expiryMillis.remove(key);
        }
        return removed;
    }

    private long expireKey(RedisKey key, long seconds) {
        purgeIfExpired(key);
        if (!keyspace.containsKey(key)) {
            return 0L;
        }
        expiryMillis.put(key, deadline(seconds));
        purgeIfExpired(key);
        return 1L;
    }

    private Value valueAt(RedisKey key) {
        purgeIfExpired(key);
        return keyspace.get(key);
    }

    private Map<String, String> hashAt(RedisKey key) {
        Value value = valueAt(key);
        if (value == null) {
            Value created = Value.ofHash();
            Value previous = keyspace.putIfAbsent(key, created);
            value = previous == null ? created : previous;
        }
        return value.asHash();
    }

    private Set<String> setAt(RedisKey key) {
        Value value = valueAt(key);
        if (value == null) {
            Value created = Value.ofSet();
            Value previous = keyspace.putIfAbsent(key, created);
            value = previous == null ? created : previous;
        }
        return value.asSet();
    }

    private void dropKeyWhenEmpty(RedisKey key) {
        Value value = keyspace.get(key);
        if (value != null && value.isEmpty()) {
            keyspace.remove(key, value);
            expiryMillis.remove(key);
        }
    }

    private void purgeIfExpired(RedisKey key) {
        Long deadline = expiryMillis.get(key);
        if (deadline != null && System.currentTimeMillis() >= deadline) {
            keyspace.remove(key);
            expiryMillis.remove(key, deadline);
        }
    }

    private long deadline(long seconds) {
        return System.currentTimeMillis() + seconds * 1000L;
    }

    private static String incremented(String current, long delta) {
        long base = 0L;
        if (current != null) {
            try {
                base = Long.parseLong(current);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("ERR hash value is not an integer");
            }
        }
        try {
            return Long.toString(Math.addExact(base, delta));
        } catch (ArithmeticException e) {
            throw new IllegalStateException("ERR increment or decrement would overflow");
        }
    }

    private static RedisKey key(String key) {
        return new RedisKey(Objects.requireNonNull(key, "Redis key is required")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static RedisKey key(byte[] key) {
        return new RedisKey(copyOf(key));
    }

    private static byte[] copyOf(byte[] value) {
        Objects.requireNonNull(value, "Redis value is required");
        return Arrays.copyOf(value, value.length);
    }

    private static Pattern compileGlob(String pattern) {
        StringBuilder regex = new StringBuilder(pattern.length() + 8);
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            switch (current) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> {
                    if (REGEX_META.indexOf(current) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(current);
                }
            }
        }
        return Pattern.compile(regex.toString());
    }

    private record RedisKey(byte[] bytes) {
        @Override
        public boolean equals(Object obj) {
            return obj instanceof RedisKey other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private enum ValueType {
        STRING,
        HASH,
        SET
    }

    private static final class Value {
        private final ValueType type;

        private final byte[] string;

        private final Map<String, String> hash;

        private final Set<String> set;

        private Value(ValueType type, byte[] string, Map<String, String> hash, Set<String> set) {
            this.type = type;
            this.string = string;
            this.hash = hash;
            this.set = set;
        }

        static Value ofString(byte[] data) {
            return new Value(ValueType.STRING, data, null, null);
        }

        static Value ofHash() {
            return new Value(ValueType.HASH, null, new ConcurrentHashMap<>(), null);
        }

        static Value ofSet() {
            return new Value(ValueType.SET, null, null, ConcurrentHashMap.newKeySet());
        }

        byte[] asString() {
            requireType(ValueType.STRING);
            return string;
        }

        Map<String, String> asHash() {
            requireType(ValueType.HASH);
            return hash;
        }

        Set<String> asSet() {
            requireType(ValueType.SET);
            return set;
        }

        boolean isEmpty() {
            if (type == ValueType.HASH) {
                return hash.isEmpty();
            }
            if (type == ValueType.SET) {
                return set.isEmpty();
            }
            return false;
        }

        private void requireType(ValueType expected) {
            if (type != expected) {
                throw new IllegalStateException(WRONG_TYPE_MESSAGE);
            }
        }
    }
}
