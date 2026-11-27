---
id: 004
title: Thread pools must be explicit ThreadPoolExecutor with bounded queues and named threads
module: service/*
priority: P0
type: concurrency
tags: [thread-pool, executors, bounded-queue, naming, x.con.06]
---

## Description

`Executors.new*` convenience factories are banned (X.CON.06): they hide
unbounded queues (cached pool) or mislead on capacity (fixed pool). All
pools must be explicit `ThreadPoolExecutor` (or
`ScheduledThreadPoolExecutor`) with a bounded queue, named daemon threads
via a thread factory, explicit rejection policy, and a registered
shutdown path.

## Input Conditions

- Any new pool creation in service modules
- Existing pools being modified (capacity, rejection, lifecycle)

## Expected Behavior

- `new ThreadPoolExecutor(core, max, keepAlive, unit,
  new LinkedBlockingQueue<>(capacity), threadFactory("..."),
  explicitRejectionPolicy)` — see `A2AAutoConfiguration.HostedResources`
- Pool capacity changes keep `validateAdmissionCapacity` consistent
  (admission limit ≤ executor capacity)
- Every pool has an owner that closes it (`assembly.onClose`,
  `taskStoreCleanup`), mirroring `HostedRuntimeAssembler`
- `CompletableFuture.*Async` calls pass an explicit executor, never the
  common pool

## Test Location

- Conventions test: grep-based CI checks on `Executors.new` in
  `src/main` (currently zero occurrences — keep it zero)
- Reference: `HostedAssemblyTest`, `A2AQueueManagerCleanupWiringTest`

## Coverage

- [ ] not covered
