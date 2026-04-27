package com.openjiuwen.a2a_service.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 异步 Redis 客户端（基于 Spring Data Redis）。
 */
public class RedisClient {

    private static final Logger logger = LoggerFactory.getLogger(RedisClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final StringRedisTemplate redisTemplate;

    public RedisClient(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public StringRedisTemplate getTemplate() {
        return redisTemplate;
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    public void set(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public void set(String key, String value, long ttlSeconds) {
        redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * SET if Not eXists。返回 true 表示写入成功（key 之前不存在）。
     */
    public boolean setNx(String key, String value, long ttlSeconds) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key, value, ttlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    public void delete(String... keys) {
        if (keys != null && keys.length > 0) {
            redisTemplate.delete(java.util.Arrays.asList(keys));
        }
    }

    public void zRemoveRangeByScore(String key, double min, double max) {
        redisTemplate.opsForZSet().removeRangeByScore(key, min, max);
    }

    public long zCard(String key) {
        Long count = redisTemplate.opsForZSet().zCard(key);
        return count != null ? count : 0L;
    }

    public void zAdd(String key, String member, double score) {
        redisTemplate.opsForZSet().add(key, member, score);
    }

    public Set<String> zRange(String key, long start, long end) {
        Set<String> values = redisTemplate.opsForZSet().range(key, start, end);
        return values != null ? values : Set.of();
    }

    public void expire(String key, long ttlSeconds) {
        redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
    }

    public Optional<Object> getJson(String key) {
        Optional<String> raw = get(key);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw.get(), Object.class));
        } catch (JsonProcessingException e) {
            logger.warn("[Redis] JSON 解析失败：key={}", key, e);
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getJsonAsMap(String key) {
        Optional<String> raw = get(key);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw.get(), Map.class));
        } catch (JsonProcessingException e) {
            logger.warn("[Redis] JSON 解析失败：key={}", key, e);
            return Optional.empty();
        }
    }

    public void setJson(String key, Object value) {
        try {
            set(key, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            logger.error("[Redis] JSON 序列化失败：key={}", key, e);
        }
    }

    public void setJson(String key, Object value, long ttlSeconds) {
        try {
            set(key, objectMapper.writeValueAsString(value), ttlSeconds);
        } catch (JsonProcessingException e) {
            logger.error("[Redis] JSON 序列化失败：key={}", key, e);
        }
    }
}
