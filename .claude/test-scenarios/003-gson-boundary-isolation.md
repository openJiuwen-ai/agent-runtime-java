---
id: 003
title: Gson stays at the A2A protocol boundary — Jackson everywhere else
module: service/*
priority: P0
type: boundary
tags: [gson, jackson, a2a, serialization, architecture]
---

## Description

Gson 2.11.0 is pinned solely for the A2A SDK wire format
(`JSONRPCUtils.setStrictness` requirement). Business/adapters/app code
uses Jackson. Gson leaking outside `controller/a2a` (or Jackson entering
SDK envelope serialization) creates double-maintenance and subtle
wire-format drift.

## Input Conditions

- New `com.google.gson` imports outside `controller/a2a/**`
- New `com.fasterxml.jackson` usage serializing SDK types
  (`Task`, `Message`, JSON-RPC envelopes)

## Expected Behavior

- Gson usage confined to `A2aJsonRpcController`, `A2aJsonRpcProtocol`,
  and SDK-type (de)serialization helpers
- All other JSON work on Jackson, singletons reused per
  scenario 002
- JSON-RPC numeric id round-trips exactly (JsonElement, never double)
  — regression area of GitCode !262 / issues 641/642

## Test Location

- `A2aJsonRpcProtocol` id echo tests; `A2ATaskLifecycleTest` lifecycle
  envelope checks

## Coverage

- [ ] not covered
