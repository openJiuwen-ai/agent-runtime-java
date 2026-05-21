package com.openjiuwen.a2a_service.common;

public final class RedisHolder {

    private static volatile RedisClient instance;

    private RedisHolder() {
    }

    public static void set(RedisClient redisClient) {
        instance = redisClient;
    }

    public static RedisClient get() {
        return instance;
    }
}