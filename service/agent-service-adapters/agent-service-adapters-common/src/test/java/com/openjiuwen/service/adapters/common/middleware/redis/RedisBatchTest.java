/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.spec.hosting.ScopedRuntimeRedisClient;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.BuilderFactory;
import redis.clients.jedis.ClusterPipeline;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RedisBatchTest {
    private static final byte[] VALUE = {0, (byte) 0xff};

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checksRepliesAndClosesStandaloneAndClusterPipelinesThroughHostedScope(boolean cluster) {
        UnifiedJedis jedis = cluster ? mock(JedisCluster.class) : mock(JedisPooled.class);
        AbstractPipeline pipeline = cluster ? mock(ClusterPipeline.class) : mock(Pipeline.class);
        doReturn(pipeline).when(jedis).pipelined();
        Response<String> first = new Response<>(BuilderFactory.STRING);
        Response<String> second = new Response<>(BuilderFactory.STRING);
        Response<Long> expiry = new Response<>(BuilderFactory.LONG);
        first.set(bytes("OK"));
        second.set(bytes("OK"));
        expiry.set(0L);
        when(pipeline.setex(bytes("scope:task"), 41L, VALUE)).thenReturn(first);
        when(pipeline.setex(bytes("scope:index"), 41L, bytes("1"))).thenReturn(second);
        when(pipeline.expire(bytes("scope:ready"), 41L)).thenReturn(expiry);
        RuntimeRedisClient client = cluster ? new JedisClusterRuntimeRedisClient((JedisCluster) jedis)
                : new JedisPooledRuntimeRedisClient((JedisPooled) jedis);
        Map<String, byte[]> values = new LinkedHashMap<>();
        values.put("task", VALUE);
        values.put("index", bytes("1"));

        new ScopedRuntimeRedisClient(client, "scope:").setexBatch(values, 41L, List.of("ready"));

        var order = inOrder(pipeline);
        order.verify(pipeline).setex(bytes("scope:task"), 41L, VALUE);
        order.verify(pipeline).setex(bytes("scope:index"), 41L, bytes("1"));
        order.verify(pipeline).expire(bytes("scope:ready"), 41L);
        order.verify(pipeline).sync();
        order.verify(pipeline).close();
        verify(jedis, never()).exists(any(byte[].class));
        verify(jedis, never()).setex(any(byte[].class), anyLong(), any(byte[].class));
        assertThat(values).containsOnlyKeys("task", "index");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void surfacesCommandErrorsInEveryReplyWithoutSequentialRetry(int failedCommand) {
        JedisPooled jedis = mock(JedisPooled.class);
        Pipeline pipeline = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(pipeline);
        Response<String> first = new Response<>(BuilderFactory.STRING);
        Response<String> second = new Response<>(BuilderFactory.STRING);
        Response<Long> expiry = new Response<>(BuilderFactory.LONG);
        first.set(bytes("OK"));
        second.set(bytes("OK"));
        expiry.set(1L);
        JedisDataException failure = new JedisDataException("denied");
        List.of(first, second, expiry).get(failedCommand).set(failure);
        when(pipeline.setex(bytes("task"), 41L, VALUE)).thenReturn(first);
        when(pipeline.setex(bytes("index"), 41L, bytes("1"))).thenReturn(second);
        when(pipeline.expire(bytes("ready"), 41L)).thenReturn(expiry);
        Map<String, byte[]> values = new LinkedHashMap<>();
        values.put("task", VALUE);
        values.put("index", bytes("1"));

        assertThatThrownBy(() -> new JedisPooledRuntimeRedisClient(jedis)
                .setexBatch(values, 41L, List.of("ready"))).isSameAs(failure);

        verify(pipeline).close();
        verify(jedis, never()).setex(any(byte[].class), anyLong(), any(byte[].class));
    }

    @Test
    void translatesUnfilledClusterReplyToConnectionFailure() {
        JedisCluster jedis = mock(JedisCluster.class);
        ClusterPipeline pipeline = mock(ClusterPipeline.class);
        when(jedis.pipelined()).thenReturn(pipeline);
        when(pipeline.setex(bytes("task"), 41L, VALUE)).thenReturn(new Response<>(BuilderFactory.STRING));

        assertThatThrownBy(() -> new JedisClusterRuntimeRedisClient(jedis)
                .setexBatch(Map.of("task", VALUE), 41L, List.of()))
                .isInstanceOf(JedisConnectionException.class).hasCauseInstanceOf(IllegalStateException.class);
        verify(pipeline).close();
    }

    @Test
    void closesPipelineAfterSyncConnectionFailure() {
        JedisPooled jedis = mock(JedisPooled.class);
        Pipeline pipeline = mock(Pipeline.class);
        when(jedis.pipelined()).thenReturn(pipeline);
        doThrow(new JedisConnectionException("reset")).when(pipeline).sync();

        assertThatThrownBy(() -> new JedisPooledRuntimeRedisClient(jedis)
                .setexBatch(Map.of("task", VALUE), 41L, List.of()))
                .isInstanceOf(JedisConnectionException.class);
        verify(pipeline).close();
    }

    @Test
    void emptyBatchDoesNotBorrowConnection() {
        JedisPooled jedis = mock(JedisPooled.class);
        new JedisPooledRuntimeRedisClient(jedis).setexBatch(Map.of(), 41L, List.of());
        verify(jedis, never()).pipelined();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
