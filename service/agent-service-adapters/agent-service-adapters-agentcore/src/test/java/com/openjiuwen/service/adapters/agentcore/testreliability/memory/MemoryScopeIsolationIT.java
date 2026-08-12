/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.testreliability.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.agentcore.memory.MemoryStoreMemoryProvider;
import com.openjiuwen.service.adapters.common.memory.MemoryAddRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryDeleteRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryGetRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryRecord;
import com.openjiuwen.service.adapters.common.memory.MemorySearchRequest;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.memory.MemoryWriteResult;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * TC_M_001 — 并发请求的 prefetch/syncTurn scope 隔离验证。
 *
 * <p>测试场景：同时发送 10 个 Query，每个使用不同 user_id（user_1 ~ user_10），
 * 验证每个请求的 prefetch search 和 syncTurn add 中 user_id 与请求严格匹配，
 * 无跨请求 scope 错配或数据串扰。</p>
 *
 * <table>
 *   <tr><td><b>维度</b></td><td><b>内容</b></td></tr>
 *   <tr><td>场景</td><td>10个并发请求、10个不同user_id → prefetch/syncTurn scope隔离验证</td></tr>
 *   <tr><td>前置</td><td>长期记忆服务已启用(memory.enabled=true)</td></tr>
 *   <tr><td>步骤</td><td>同时发10个Query(user_1~user_10) → mock mem0按user_id返回不同记忆 → 断言scope匹配</td></tr>
 *   <tr><td>预期</td><td>10个请求全部成功；prefetch search filters.user_id严格匹配；syncTurn add user_id严格匹配；无scope错配</td></tr>
 * </table>
 *
 * @since 0.1.0
 */
@Tag("system-test")
class MemoryScopeIsolationIT {
    /** 并发用户数量。 */
    private static final int CONCURRENT_USERS = 10;

    /** 测试用户 ID 列表（user_1 ~ user_10）。 */
    private static final List<String> USER_IDS = IntStream.rangeClosed(1, CONCURRENT_USERS)
        .mapToObj(i -> "user_" + i)
        .toList();

    private ScopeRecordingMemoryStore memoryStore;

    private MemoryStoreMemoryProvider memoryProvider;

    @BeforeEach
    void setUp() {
        memoryStore = new ScopeRecordingMemoryStore();
        MiddlewareProperties.Memory memory = new MiddlewareProperties.Memory();
        memoryProvider = new MemoryStoreMemoryProvider(memoryStore, memory);
        memoryProvider.initialize(Map.of());
    }

    // ── TC_M_001-1：并发 prefetch scope 隔离 ──

    /**
     * 10 个并发 prefetch 请求，每个使用不同 user_id，
     * 验证 search scope.user_id 与请求严格匹配，无跨请求串扰。
     *
     * @throws Exception 如果测试线程被意外中断
     */
    @Test
    void concurrentPrefetchSearchScopeMatchesEachUserId() throws Exception {
        ExecutorService executor = createNamedThreadPool("prefetch-scope-test");
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_USERS);
        AtomicInteger successCount = new AtomicInteger(0);

        for (String userId : USER_IDS) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Map<String, Object> kwargs = Map.of("user_id", userId);
                    String result = memoryProvider.prefetch("偏好查询_" + userId, kwargs);
                    assertThat(result).contains("## Long-term Memory");
                    assertThat(result).contains(userId + "的记忆内容");
                    successCount.incrementAndGet();
                } catch (InterruptedException ex) {
                    // 线程被中断，直接退出（不调用 Thread.interrupt()，符合 G.CON.10）
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean isAllDone = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(isAllDone).as("所有并发请求应在 10s 内完成").isTrue();

        assertThat(successCount.get())
            .as("10 个并发 prefetch 请求全部成功返回")
            .isEqualTo(CONCURRENT_USERS);

        assertSearchScopeMatchesEachUserId();
    }

    // ── TC_M_001-2：并发 syncTurn scope 隔离 ──

    /**
     * 10 个并发 syncTurn 请求，每个使用不同 user_id，
     * 验证 add scope.user_id 与请求严格匹配，无跨请求串扰。
     *
     * @throws Exception 如果测试线程被意外中断
     */
    @Test
    void concurrentSyncTurnAddScopeMatchesEachUserId() throws Exception {
        ExecutorService executor = createNamedThreadPool("syncturn-scope-test");
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_USERS);
        AtomicInteger successCount = new AtomicInteger(0);

        for (String userId : USER_IDS) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Map<String, Object> kwargs = Map.of("user_id", userId);
                    memoryProvider.syncTurn("用户消息_" + userId, "助手回复_" + userId, kwargs);
                    successCount.incrementAndGet();
                } catch (InterruptedException ex) {
                    // 线程被中断，直接退出（不调用 Thread.interrupt()，符合 G.CON.10）
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean isAllDone = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(isAllDone).as("所有并发请求应在 10s 内完成").isTrue();

        assertThat(successCount.get())
            .as("10 个并发 syncTurn 请求全部成功完成")
            .isEqualTo(CONCURRENT_USERS);

        assertAddScopeMatchesEachUserId();
    }

    // ── TC_M_001-3：并发 prefetch + syncTurn 组合 scope 隔离 ──

    /**
     * 10 个并发请求（prefetch + syncTurn 组合），验证两者 scope 均严格匹配，
     * 无跨请求串扰。
     *
     * @throws Exception 如果测试线程被意外中断
     */
    @Test
    void concurrentPrefetchAndSyncTurnMaintainScopeIsolation() throws Exception {
        ExecutorService executor = createNamedThreadPool("combined-scope-test");
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_USERS);
        AtomicInteger successCount = new AtomicInteger(0);

        for (String userId : USER_IDS) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Map<String, Object> kwargs = Map.of("user_id", userId);
                    String prefetchResult = memoryProvider.prefetch("组合查询_" + userId, kwargs);
                    assertThat(prefetchResult).contains(userId + "的记忆内容");
                    memoryProvider.syncTurn("组合用户消息_" + userId, "组合助手回复_" + userId, kwargs);
                    successCount.incrementAndGet();
                } catch (InterruptedException ex) {
                    // 线程被中断，直接退出（不调用 Thread.interrupt()，符合 G.CON.10）
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean isAllDone = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(isAllDone).isTrue();
        assertThat(successCount.get()).isEqualTo(CONCURRENT_USERS);

        assertThat(memoryStore.searchRequests.size()).isEqualTo(CONCURRENT_USERS);
        assertThat(memoryStore.addRequests.size()).isEqualTo(CONCURRENT_USERS);

        assertSearchScopeMatchesEachUserId();
        assertAddScopeMatchesEachUserId();
    }

    // ── 辅助方法 ──

    /**
     * 从查询字符串中提取 user_id（格式如 "偏好查询_user_1"）。
     *
     * @param query 查询字符串
     * @return 提取出的 user_id（如 "user_1"）
     */
    private static String extractUserIdFromQuery(String query) {
        int userIdStart = query.lastIndexOf("user_");
        if (userIdStart >= 0) {
            return query.substring(userIdStart);
        }
        return query;
    }

    /**
     * 验证所有 search 请求的 scope.user_id 与请求的 user_id 严格匹配，
     * 无跨请求串扰（提取为独立方法以符合 G.MET.01 方法不超过50行）。
     */
    private void assertSearchScopeMatchesEachUserId() {
        assertThat(memoryStore.searchRequests.size())
            .as("应收到 %d 个 search 请求", CONCURRENT_USERS)
            .isEqualTo(CONCURRENT_USERS);

        for (int i = 0; i < memoryStore.searchRequests.size(); i++) {
            MemorySearchRequest request = memoryStore.searchRequests.get(i);
            assertThat(request.scope().userId())
                .as("search request %d 的 user_id 不应为空", i)
                .isNotBlank();
            assertThat(USER_IDS)
                .as("search request %d 的 user_id 必须在合法范围内", i)
                .contains(request.scope().userId());
            assertThat(request.query())
                .as("search request %d 的 query 应包含对应的 user_id", i)
                .contains(request.scope().userId());
        }

        assertThat(memoryStore.searchRequests.stream()
            .map(req -> req.scope().userId()).toList())
            .as("所有 %d 个 user_id 都应被搜索到", CONCURRENT_USERS)
            .containsAll(USER_IDS);

        for (MemorySearchRequest request : memoryStore.searchRequests) {
            assertThat(request.scope().userId())
                .as("prefetch scope.user_id 与 query 中的 user_id 应一致，无跨请求串扰")
                .isEqualTo(extractUserIdFromQuery(request.query()));
        }

        assertThat(memoryStore.searchRequests.stream()
            .noneMatch(req -> !USER_IDS.contains(req.scope().userId())))
            .as("不存在 scope 错配：所有 search 的 user_id 都在合法范围内")
            .isTrue();
    }

    /**
     * 验证所有 add 请求的 scope.user_id 与请求的 user_id 严格匹配，
     * 无跨请求串扰（提取为独立方法以符合 G.MET.01 方法不超过50行）。
     */
    private void assertAddScopeMatchesEachUserId() {
        assertThat(memoryStore.addRequests.size())
            .as("应收到 %d 个 add 请求", CONCURRENT_USERS)
            .isEqualTo(CONCURRENT_USERS);

        for (int i = 0; i < memoryStore.addRequests.size(); i++) {
            MemoryAddRequest request = memoryStore.addRequests.get(i);
            assertThat(request.scope().userId())
                .as("add request %d 的 user_id 不应为空", i)
                .isNotBlank();
            assertThat(USER_IDS)
                .as("add request %d 的 user_id 必须在合法范围内", i)
                .contains(request.scope().userId());
        }

        assertThat(memoryStore.addRequests.stream()
            .map(req -> req.scope().userId()).toList())
            .as("所有 %d 个 user_id 都应被写入到", CONCURRENT_USERS)
            .containsAll(USER_IDS);

        for (MemoryAddRequest request : memoryStore.addRequests) {
            assertThat(request.messages())
                .as("add request 的 messages 不应为空")
                .isNotEmpty();
            assertThat(request.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .anyMatch(m -> m.content().contains(request.scope().userId())))
                .as("add scope.user_id 与 user message 内容应一致，无跨请求串扰")
                .isTrue();
        }

        assertThat(memoryStore.addRequests.stream()
            .noneMatch(req -> !USER_IDS.contains(req.scope().userId())))
            .as("不存在 scope 错配：所有 add 的 user_id 都在合法范围内")
            .isTrue();
    }

    /**
     * 创建指定名称前缀的固定大小线程池。
     * 使用 ThreadPoolExecutor（符合 G.CON.12）+ 命名线程 + UncaughtExceptionHandler（符合 G.CON.08）。
     *
     * @param namePrefix 线程名前缀
     * @return 带命名线程和异常处理器的 ThreadPoolExecutor
     */
    private static ThreadPoolExecutor createNamedThreadPool(String namePrefix) {
        AtomicInteger threadNumber = new AtomicInteger(1);
        ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        ThreadFactory namedFactory = runnable -> {
            Thread thread = defaultFactory.newThread(runnable);
            thread.setName(namePrefix + "-thread-" + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((threadRef, ex) ->
                LoggerFactory.getLogger(MemoryScopeIsolationIT.class)
                    .warn("Uncaught exception in thread {}: {}", threadRef.getName(), ex.getMessage()));
            return thread;
        };
        return new ThreadPoolExecutor(
            CONCURRENT_USERS, CONCURRENT_USERS, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), namedFactory);
    }

    // ── 内部类：Scope Recording MemoryStore ──

    /**
     * Thread-safe recording MemoryStore that returns user-specific memory content.
     *
     * <p>For each search request, returns a MemoryRecord whose content includes the
     * requesting user's ID, enabling verification that prefetch results match the
     * correct user scope with no cross-request data leakage.</p>
     */
    private static final class ScopeRecordingMemoryStore implements MemoryStore {
        private final CopyOnWriteArrayList<MemorySearchRequest> searchRequests = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<MemoryAddRequest> addRequests = new CopyOnWriteArrayList<>();

        @Override
        public String getProvider() {
            return "scope-recording";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public MemoryWriteResult add(MemoryAddRequest request) {
            addRequests.add(request);
            return new MemoryWriteResult(List.of(), Map.of());
        }

        @Override
        public List<MemoryRecord> search(MemorySearchRequest request) {
            searchRequests.add(request);
            String userId = request.scope().userId();
            MemoryRecord record = new MemoryRecord(
                "mem-" + userId,
                userId + "的记忆内容",
                Map.of("source", "prefetch", "user_id", userId),
                Map.of("score", 0.95));
            return List.of(record);
        }

        @Override
        public Optional<MemoryRecord> get(MemoryGetRequest request) {
            return Optional.empty();
        }

        @Override
        public void delete(MemoryDeleteRequest request) {
            // 空实现：scope 隔离测试不涉及删除操作
        }
    }
}
