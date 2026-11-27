---
description: ThreadPoolExecutor, CompletableFuture, SSE/streaming, and scheduler conventions for agent-runtime-java.
language: english
paths:
  - "service/**/src/main/java/**/*.java"
---

# Concurrency Rules

## Thread Pools

Explicit `ThreadPoolExecutor` only (X.CON.06). Bounded queue always.
Named threads always. Existing patterns to follow:

```java
// A2AAutoConfiguration.HostedResources — reference pattern
new ThreadPoolExecutor(instances, instances, 0, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(instances),      // bounded
        threadFactory("hosted-a2a-events-"),        // named
        new ThreadPoolExecutor.AbortPolicy());      // explicit rejection

// Scheduled retries
ScheduledThreadPoolExecutor retries = new ScheduledThreadPoolExecutor(1, threadFactory("hosted-a2a-retry-"));
retries.setRemoveOnCancelPolicy(true);
```

- **Banned**: `Executors.newCachedThreadPool()`,
  `newFixedThreadPool()`, `newSingleThreadExecutor()`,
  `Executors.newScheduledThreadPool()`.
- Every pool needs a shutdown path: register close/cleanup with the
  owning assembly (`assembly.onClose(...)`, `taskStoreCleanup`),
  mirroring `HostedRuntimeAssembler`.
- Rejection policy is a decision, not a default: `AbortPolicy` for
  user-facing ingress (fail fast → 503/429), `CallerRunsPolicy` only for
  background sweepers.

## Admission & Capacity Discipline

- `TaskAdmissionGate.limit()` must not exceed the agent executor thread
  capacity — `validateAdmissionCapacity` enforces it at startup
  (`IllegalStateException`). When adding executors/queues, update both
  sides of that validation.
- Permits acquired at transport entry are released exactly once when the
  round's consequence lands (see `AdmissionReleaseCoordinator`); any new
  early-return path between acquire and handover must release.

## CompletableFuture

- `supplyAsync`/`runAsync` **must** take an explicit executor — the
  default `ForkJoinPool.commonPool()` is banned for anything long-lived
  (see `scheduleConfigRecheck` using
  `CompletableFuture.delayedExecutor(...)`).
- `.join()` at synchronous entry points only (controller dispatch);
  never inside event-bus processors or streaming callbacks.

## SSE / Streaming

- A2A SSE subscriptions run on a dedicated executor
  (`A2aJsonRpcController.SSE_EXECUTOR`): virtual threads on JDK 21, small
  daemon platform pool on JDK 17 — keep long-lived subscriptions off
  `commonPool()`.
- Stream cancellation must interrupt upstream work
  (`ActiveStreamRegistry` tracks live publishers); a cancelled stream
  must not leak the admission permit.
- Delivery dedup: `HttpPushNotificationSender` uses per-notificationId
  locks + TTL'd delivery records — when adding async delivery features,
  follow that idempotency pattern rather than inventing new one.

## State Maps

- Session/task-keyed mutable state: `ConcurrentHashMap` /
  `ConcurrentMap.newKeySet()` (see `deliveryRecords`,
  `pendingConfigRechecks`).
- `computeIfAbsent` for lock/record acquisition — never get+put.
- TTL cleanup is lazy (`sweepExpiredRecords`) — keep that for any
  record map that can grow with task volume, or it becomes a leak.
