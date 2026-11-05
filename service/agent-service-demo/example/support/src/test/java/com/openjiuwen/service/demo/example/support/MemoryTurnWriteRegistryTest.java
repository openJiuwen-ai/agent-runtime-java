/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unit tests for {@link MemoryTurnWriteRegistry}.
 *
 * @since 0.1.0
 */
class MemoryTurnWriteRegistryTest {
    @Test
    void consumeReturnsTrueOnceAfterMark() {
        MemoryTurnWriteRegistry registry = new MemoryTurnWriteRegistry();

        registry.markToolWrite("conv-1");

        assertThat(registry.consumeToolWrite("conv-1")).isTrue();
        // 一轮只消费一次：避免同一会话后续轮次被误跳过
        assertThat(registry.consumeToolWrite("conv-1")).isFalse();
    }

    @Test
    void consumeIgnoresOtherConversationsAndBlankIds() {
        MemoryTurnWriteRegistry registry = new MemoryTurnWriteRegistry();

        registry.markToolWrite("conv-1");
        registry.markToolWrite("");

        assertThat(registry.consumeToolWrite("conv-2")).isFalse();
        assertThat(registry.consumeToolWrite(null)).isFalse();
        assertThat(registry.consumeToolWrite("conv-1")).isTrue();
    }

    @Test
    void staleAnnouncementIsIgnored() {
        AtomicLong clock = new AtomicLong();
        MemoryTurnWriteRegistry registry = new MemoryTurnWriteRegistry(clock::get);

        registry.markToolWrite("conv-1");
        clock.addAndGet(Duration.ofMinutes(6).toNanos());

        // 工具写入后本轮没有正常结束（异常/取消）：过期后不再抑制回写
        assertThat(registry.consumeToolWrite("conv-1")).isFalse();
    }
}
