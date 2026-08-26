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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DefaultServeOrchestratorInterruptTest
 *
 * @since 2026-07-03
 */
class DefaultServeOrchestratorInterruptTest {
    private ExecutorService workerPool;

    @AfterEach
    void tearDown() {
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
    }

    @Test
    void cancelActiveStopsStreamingObserver() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AgentHandler handler = new AgentHandler() {
            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
                observer.onNext(new QueryChunk("chunk", Map.of("content", "tick")));
                started.countDown();
                while (!observer.isCancelled()) {
                    observer.onNext(new QueryChunk("chunk", Map.of("content", "tick")));
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                }
                observer.onComplete();
            }

            @Override
            public com.openjiuwen.service.spec.dto.QueryResponse query(
                com.openjiuwen.service.spec.dto.ServeRequest request) {
                return null;
            }
        };
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, registry);
        ServeRequest request = new ServeRequest();
        request.setConversationId("interrupt-me");
        List<QueryChunk> chunks = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        workerPool = newWorkerPool();
        Future<?> worker = workerPool.submit(() -> orchestrator.streamQuery(request, new QueryStreamObserver() {
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

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.cancelActive("interrupt-me");
        worker.get(5, TimeUnit.SECONDS);

        assertThat(completed.get()).isTrue();
        assertThat(chunks).isNotEmpty();
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    void syncQueryNotAffectedByInterrupt() throws Exception {
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
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, registry);
        ServeRequest request = new ServeRequest();
        request.setConversationId("sync-conv");
        workerPool = newWorkerPool();
        Future<?> worker = workerPool.submit(() -> orchestrator.query(request));
        Thread.sleep(50);
        orchestrator.cancelActive("sync-conv");
        worker.get(5, TimeUnit.SECONDS);

        assertThat(queryFinished.get()).isTrue();
        assertThat(registry.activeCount()).isZero();
    }

    private static ExecutorService newWorkerPool() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }
}
