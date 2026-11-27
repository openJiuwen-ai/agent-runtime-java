---
id: 002
title: ObjectMapper and Gson instances must be class-level static final, never created inside methods
module: service/*
priority: P0
type: resource
tags: [object-mapper, gson, gc, performance, json]
---

## Description

`ObjectMapper` (and `Gson`) are expensive to create but thread-safe.
Per-call instantiation causes GC pressure under frequent invocation.
All instances must be `private static final` class-level fields.

## Input Conditions

- All occurrences of `new ObjectMapper()` / `new GsonBuilder()` in
  service modules
- Class-level `static final` declarations are compliant; in-method
  creation is a violation

## Expected Behavior

- No `new ObjectMapper()` / `new Gson()` inside method bodies
- Protocol-boundary Gson instances keep their documented builder flags
  (`.disableHtmlEscaping()` in `A2aJsonRpcController.GSON`)

## Test Location

- Follows CodeCheck/CI-Bot repeated findings on this repo
- Reference tests: `JsonRpcA2aTransportFactory`, `JiuwenMemoryApi`
  (compliant singleton pattern)

## Coverage

- [ ] not covered
