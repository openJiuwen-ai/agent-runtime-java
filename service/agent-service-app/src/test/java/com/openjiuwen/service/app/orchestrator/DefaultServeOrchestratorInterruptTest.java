/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DefaultServeOrchestratorInterruptTest
 *
 * @since 2026-07-03
 */
class DefaultServeOrchestratorInterruptTest {
    @Test
    void cancelActiveStopsStreamingObserver() throws Exception {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);

        AgentHandler handler = new AgentHandler() {
            @Override
            public com.openjiuwen.service.spec.dto.QueryResponse query(
                com.openjiuwen.service.spec.dto.ServeRequest request) {
                return null;
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
                observer.onNext(new QueryChunk("chunk", Map.of("content", "tick")));
                started.countDown();
                while (!observer.isCancelled()) {
                    observer.onNext(new QueryChunk("chunk", Map.of("content", "tick")));
                    // 使用更高效的等待机制（如 LockSupport.parkNanos()）
                    java.util.concurrent.locks.LockSupport.parkNanos(1000000); // 等待1ms，避免忙等待
                }
                observer.onComplete();
            }
        };

        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, registry);
        ServeRequest request = new ServeRequest();
        request.setConversationId("interrupt-me");
        List<QueryChunk> chunks = new ArrayList<>();

        Thread worker = new Thread(() -> orchestrator.streamQuery(request, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        }));
        worker.setUncaughtExceptionHandler((unused, error) -> {
            throw new AssertionError(error);
        });
        worker.start();

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.cancelActive("interrupt-me");
        worker.join(5000);

        assertThat(completed.get()).isTrue();
        assertThat(chunks).isNotEmpty();
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    void syncQueryNotAffectedByInterrupt() throws Exception {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        java.util.concurrent.atomic.AtomicBoolean queryFinished = new java.util.concurrent.atomic.AtomicBoolean(false);

        AgentHandler handler = new AgentHandler() {
            @Override
            public com.openjiuwen.service.spec.dto.QueryResponse query(ServeRequest request) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ex) {
                    queryFinished.set(true);
                }
                queryFinished.set(true);
                return new com.openjiuwen.service.spec.dto.QueryResponse("sync-ok", request.getConversationId());
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            }
        };

        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, registry);
        ServeRequest request = new ServeRequest();
        request.setConversationId("sync-conv");

        Thread worker = new Thread(() -> orchestrator.query(request));
        worker.setUncaughtExceptionHandler((unused, error) -> {
            throw new AssertionError(error);
        });
        worker.start();
        Thread.sleep(50);
        orchestrator.cancelActive("sync-conv");
        worker.join(5000);

        assertThat(queryFinished.get()).isTrue();
        assertThat(registry.activeCount()).isZero();
    }
}
