/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.redis.inmemory;

import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * In-memory {@link RuntimeRedisClient} plugin example.
 *
 * <p>Stores strings, hashes and sets in process-local concurrent maps with lazily evaluated
 * expiry timestamps, so an application can run the full SPI command surface without a Redis
 * server. State is process-bound: nothing survives a restart, and eval is not approximated
 * because atomic Lua semantics cannot be emulated in memory.
 *
 * @since 0.1.3
 */
public final class InMemoryRuntimeRedisClient implements RuntimeRedisClient {
    private static final String REGEX_META = "\\.^$|()[]{}+";

    private final Map<String, byte[]> strings = new ConcurrentHashMap<>();

    private final Map<String, Map<String, String>> hashes = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> sets = new ConcurrentHashMap<>();

    private final Map<String, Long> expiryMillis = new ConcurrentHashMap<>();

    @Override
    public Object get(String key) {
        purgeIfExpired(key);
        byte[] value = strings.get(key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    @Override
    public byte[] get(byte[] key) {
        String textKey = keyOf(key);
        purgeIfExpired(textKey);
        byte[] value = strings.get(textKey);
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    @Override
    public String set(String key, String value) {
        Objects.requireNonNull(value, "Redis value is required");
        store(key, value.getBytes(StandardCharsets.UTF_8));
        return "OK";
    }

    @Override
    public String set(String key, byte[] value) {
        store(key, copyOf(value));
        return "OK";
    }

    @Override
    public String set(byte[] key, byte[] value) {
        store(keyOf(key), copyOf(value));
        return "OK";
    }

    @Override
    public String setex(String key, long seconds, String value) {
        Objects.requireNonNull(value, "Redis value is required");
        store(key, value.getBytes(StandardCharsets.UTF_8));
        expiryMillis.put(key, deadline(seconds));
        return "OK";
    }

    @Override
    public String setex(byte[] key, long seconds, byte[] value) {
        String textKey = keyOf(key);
        store(textKey, copyOf(value));
        expiryMillis.put(textKey, deadline(seconds));
        return "OK";
    }

    @Override
    public long setnx(String key, String value) {
        Objects.requireNonNull(value, "Redis value is required");
        return setnxInternal(key, value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public long setnx(byte[] key, byte[] value) {
        return setnxInternal(keyOf(key), copyOf(value));
    }

    @Override
    public long del(String... keys) {
        long deleted = 0L;
        for (String key : keys) {
            if (removeKey(key)) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public long del(byte[]... keys) {
        long deleted = 0L;
        for (byte[] key : keys) {
            if (removeKey(keyOf(key))) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public boolean exists(String key) {
        purgeIfExpired(key);
        return existsInAnyNamespace(key);
    }

    @Override
    public boolean exists(byte[] key) {
        return exists(keyOf(key));
    }

    @Override
    public long expire(String key, long seconds) {
        purgeIfExpired(key);
        if (!existsInAnyNamespace(key)) {
            return 0L;
        }
        expiryMillis.put(key, deadline(seconds));
        purgeIfExpired(key);
        return 1L;
    }

    @Override
    public long expire(byte[] key, long seconds) {
        return expire(keyOf(key), seconds);
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
        for (String key : liveKeys()) {
            if (regex.matcher(key).matches()) {
                matches.add(key);
            }
        }
        return matches;
    }

    @Override
    public long hset(String key, String field, String value) {
        Objects.requireNonNull(field, "Redis field is required");
        Objects.requireNonNull(value, "Redis value is required");
        purgeIfExpired(key);
        Map<String, String> hash = hashes.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        return hash.put(field, value) == null ? 1L : 0L;
    }

    @Override
    public String hget(String key, String field) {
        Objects.requireNonNull(field, "Redis field is required");
        purgeIfExpired(key);
        Map<String, String> hash = hashes.get(key);
        return hash == null ? null : hash.get(field);
    }

    @Override
    public long hdel(String key, String... fields) {
        purgeIfExpired(key);
        Map<String, String> hash = hashes.get(key);
        if (hash == null) {
            return 0L;
        }
        long removed = 0L;
        for (String field : fields) {
            if (hash.remove(field) != null) {
                removed++;
            }
        }
        dropKeyWhenEmpty(key);
        return removed;
    }

    @Override
    public Map<String, String> hgetAll(String key) {
        purgeIfExpired(key);
        Map<String, String> hash = hashes.get(key);
        return hash == null ? Map.of() : Map.copyOf(hash);
    }

    @Override
    public long sadd(String key, String... members) {
        purgeIfExpired(key);
        Set<String> bucket = sets.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
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
        purgeIfExpired(key);
        Set<String> bucket = sets.get(key);
        return bucket != null && bucket.contains(member);
    }

    @Override
    public long srem(String key, String... members) {
        purgeIfExpired(key);
        Set<String> bucket = sets.get(key);
        if (bucket == null) {
            return 0L;
        }
        long removed = 0L;
        for (String member : members) {
            if (bucket.remove(member)) {
                removed++;
            }
        }
        dropKeyWhenEmpty(key);
        return removed;
    }

    @Override
    public long hincrBy(String key, String field, long delta) {
        Objects.requireNonNull(field, "Redis field is required");
        purgeIfExpired(key);
        Map<String, String> hash = hashes.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        String updated = hash.compute(field,
                (ignored, current) -> String.valueOf(current == null ? delta : Long.parseLong(current) + delta));
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
        strings.clear();
        hashes.clear();
        sets.clear();
        expiryMillis.clear();
    }

    private void store(String key, byte[] value) {
        Objects.requireNonNull(key, "Redis key is required");
        strings.put(key, value);
        expiryMillis.remove(key);
    }

    private long setnxInternal(String key, byte[] value) {
        Objects.requireNonNull(key, "Redis key is required");
        purgeIfExpired(key);
        return strings.putIfAbsent(key, value) == null ? 1L : 0L;
    }

    private boolean removeKey(String key) {
        Objects.requireNonNull(key, "Redis key is required");
        purgeIfExpired(key);
        boolean existed = existsInAnyNamespace(key);
        strings.remove(key);
        hashes.remove(key);
        sets.remove(key);
        expiryMillis.remove(key);
        return existed;
    }

    private boolean existsInAnyNamespace(String key) {
        return strings.containsKey(key) || hashes.containsKey(key) || sets.containsKey(key);
    }

    private void dropKeyWhenEmpty(String key) {
        Map<String, String> hash = hashes.get(key);
        if (hash != null && hash.isEmpty()) {
            hashes.remove(key, hash);
            expiryMillis.remove(key);
        }
        Set<String> bucket = sets.get(key);
        if (bucket != null && bucket.isEmpty()) {
            sets.remove(key, bucket);
            expiryMillis.remove(key);
        }
    }

    private void purgeIfExpired(String key) {
        Objects.requireNonNull(key, "Redis key is required");
        Long deadline = expiryMillis.get(key);
        if (deadline != null && System.currentTimeMillis() >= deadline) {
            strings.remove(key);
            hashes.remove(key);
            sets.remove(key);
            expiryMillis.remove(key, deadline);
        }
    }

    private long deadline(long seconds) {
        return System.currentTimeMillis() + seconds * 1000L;
    }

    private Set<String> liveKeys() {
        Set<String> keys = new LinkedHashSet<>();
        collectLiveKeys(strings.keySet(), keys);
        collectLiveKeys(hashes.keySet(), keys);
        collectLiveKeys(sets.keySet(), keys);
        return keys;
    }

    private void collectLiveKeys(Set<String> candidates, Set<String> target) {
        for (String key : candidates) {
            purgeIfExpired(key);
            if (existsInAnyNamespace(key)) {
                target.add(key);
            }
        }
    }

    private static String keyOf(byte[] key) {
        Objects.requireNonNull(key, "Redis key is required");
        return new String(key, StandardCharsets.UTF_8);
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
}
