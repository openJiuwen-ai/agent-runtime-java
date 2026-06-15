/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import com.openjiuwen.service.spec.lifecycle.AgentInterruptHandler;
import com.openjiuwen.service.spec.lifecycle.InterruptReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ActiveStreamInterruptorTest {

    private ActiveStreamRegistry registry;
    private AtomicInteger interruptCount;

    @BeforeEach
    void reset() {
        registry = new ActiveStreamRegistry();
        interruptCount = new AtomicInteger();
    }

    @Test
    void interruptBlankConversationIdIgnored() {
        StreamCancellationHandle handle = registry.register("c1");
        ActiveStreamInterruptor interruptor = newInterruptor(null);

        interruptor.interrupt(null);
        interruptor.interrupt("");
        interruptor.interrupt("   ");

        assertThat(handle.isCancelled()).isFalse();
        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(interruptCount.get()).isZero();
    }

    @Test
    void interruptWithoutOrchestratorStillNotifiesHandlers() {
        StreamCancellationHandle handle = registry.register("c1");
        ActiveStreamInterruptor interruptor = newInterruptor(null);

        interruptor.interrupt("c1");

        assertThat(handle.isCancelled()).isFalse();
        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(interruptCount.get()).isEqualTo(1);
    }

    @Test
    void interruptHandlerFailureDoesNotBlockOthers() {
        AtomicInteger secondCount = new AtomicInteger();
        List<AgentInterruptHandler> handlers = List.of(
                (conversationId, reason) -> {
                    throw new IllegalStateException("first failed");
                },
                (conversationId, reason) -> secondCount.incrementAndGet()
        );
        ActiveStreamInterruptor interruptor = new ActiveStreamInterruptor(providerOf(null), handlers);

        interruptor.interrupt("c1");

        assertThat(secondCount.get()).isEqualTo(1);
    }

    private ActiveStreamInterruptor newInterruptor(
            com.openjiuwen.service.spec.spi.ServeOrchestrator orchestrator) {
        List<AgentInterruptHandler> handlers = new ArrayList<>();
        handlers.add((conversationId, reason) -> {
            interruptCount.incrementAndGet();
            assertThat(reason).isEqualTo(InterruptReason.LIFECYCLE_INTERRUPT);
        });
        return new ActiveStreamInterruptor(providerOf(orchestrator), handlers);
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
