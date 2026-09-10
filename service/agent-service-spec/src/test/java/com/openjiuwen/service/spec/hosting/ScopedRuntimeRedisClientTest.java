/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class ScopedRuntimeRedisClientTest {
    @Test
    void mapsEverySingleKeyOverloadWithoutChangingValuesOrTtl() throws Exception {
        List<Call> calls = new ArrayList<>();
        RuntimeRedisClient delegate = recordingClient(calls);
        var scoped = new ScopedRuntimeRedisClient(delegate, "scope:");
        byte[] binaryKey = {(byte) 0xff, 0, 45};
        byte[] binaryValue = {9, 0, (byte) 0xfe};
        for (var method : RuntimeRedisClient.class.getMethods()) {
            if (method.getParameterCount() == 0 || method.isVarArgs() || method.getName().equals("scanIter")) {
                continue;
            }
            Object[] arguments = new Object[method.getParameterCount()];
            for (int i = 0; i < arguments.length; i++) {
                Class<?> type = method.getParameterTypes()[i];
                if (type == long.class) {
                    arguments[i] = 41L;
                } else if (type == byte[].class) {
                    arguments[i] = i == 0 ? binaryKey : binaryValue;
                } else {
                    arguments[i] = i == 0 ? "key" : "value";
                }
            }
            method.invoke(scoped, arguments);
            Call call = calls.get(calls.size() - 1);
            assertThat(call.name()).isEqualTo(method.getName());
            if (arguments[0] instanceof byte[]) {
                byte[] expected = {'s', 'c', 'o', 'p', 'e', ':', (byte) 0xff, 0, 45};
                assertThat((byte[]) call.arguments()[0]).containsExactly(expected);
            } else {
                assertThat(call.arguments()[0]).isEqualTo("scope:key");
            }
            for (int i = 1; i < arguments.length; i++) {
                assertThat(call.arguments()[i]).isEqualTo(arguments[i]);
            }
        }
        assertThat(calls).hasSize(13);
        assertThat(binaryKey).containsExactly((byte) 0xff, (byte) 0, (byte) 45);
    }

    @Test
    void preservesMgetPositionsAndNullsAndMapsBothDeleteOverloads() {
        List<Call> calls = new ArrayList<>();
        var scoped = new ScopedRuntimeRedisClient(recordingClient(calls), "scope:");
        assertThat(scoped.mget("b", "missing", "a")).containsExactly("second", null, "first");
        assertThat((String[]) calls.get(0).arguments()[0]).containsExactly("scope:b", "scope:missing", "scope:a");
        scoped.del("b", "a");
        assertThat((String[]) calls.get(1).arguments()[0]).containsExactly("scope:b", "scope:a");
        scoped.del(new byte[] {0}, new byte[] {(byte) 0xff});
        byte[][] keys = (byte[][]) calls.get(2).arguments()[0];
        assertThat(keys[0]).containsExactly((byte) 's', (byte) 'c', (byte) 'o', (byte) 'p', (byte) 'e', (byte) ':', (byte) 0);
        assertThat(keys[1][6]).isEqualTo((byte) 0xff);
    }

    @Test
    void scanRoundTripsLogicalKeysAndRejectsOutOfScopeResults() {
        List<Call> calls = new ArrayList<>();
        var scoped = new ScopedRuntimeRedisClient(recordingClient(calls), "scope:");
        assertThat(scoped.scanIter("*")).containsExactly("key");
        assertThat(calls.get(0).arguments()[0]).isEqualTo("scope:*");
        scoped.get(scoped.scanIter("*").get(0));
        assertThat(calls.get(2).arguments()[0]).isEqualTo("scope:key");
        var other = new ScopedRuntimeRedisClient(recordingClient(calls), "other:");
        assertThatThrownBy(() -> other.scanIter("*")).isInstanceOf(IllegalStateException.class);
        scoped.close();
        assertThat(calls).extracting(Call::name).doesNotContain("close");
    }

    @Test
    void scopesAreStableDistinctAndCannotNestOrInjectGlobPatterns() {
        String a = ScopedRuntimeRedisClient.namespace("应用:1", "a");
        String b = ScopedRuntimeRedisClient.namespace("应用:1", "b");
        assertThat(a).isNotEqualTo(b).endsWith(":YQ:");
        assertThat(a).isEqualTo(ScopedRuntimeRedisClient.namespace("应用:1", "a"));
        assertThat(a).isNotEqualTo(ScopedRuntimeRedisClient.namespace("应用:2", "a"));
        var delegate = recordingClient(new ArrayList<>());
        var scoped = new ScopedRuntimeRedisClient(delegate, a);
        assertThatThrownBy(() -> new ScopedRuntimeRedisClient(scoped, b)).isInstanceOf(IllegalArgumentException.class);
        for (String prefix : List.of("", " ", "bad:*:", "bad?[a]", "bad\\")) {
            assertThatThrownBy(() -> new ScopedRuntimeRedisClient(delegate, prefix))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private RuntimeRedisClient recordingClient(List<Call> calls) {
        return (RuntimeRedisClient) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {RuntimeRedisClient.class}, (proxy, method, arguments) -> {
                    calls.add(new Call(method.getName(), arguments));
                    return switch (method.getName()) {
                        case "get" -> method.getReturnType() == byte[].class
                                ? "value".getBytes(StandardCharsets.UTF_8) : "value";
                        case "set", "setex" -> "OK";
                        case "setnx", "del", "expire" -> 1L;
                        case "exists" -> true;
                        case "mget" -> Arrays.asList("second", null, "first");
                        case "scanIter" -> List.of("scope:key");
                        default -> null;
                    };
                });
    }

    private record Call(String name, Object[] arguments) {
    }
}
