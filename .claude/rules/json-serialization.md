---
description: Jackson/Gson split, ObjectMapper reuse, and protocol-boundary serialization rules for agent-runtime-java.
language: english
paths:
  - "service/**/src/main/java/**/*.java"
  - "service/**/src/test/java/**/*.java"
---

# JSON Serialization Rules

## Two Libraries, One Boundary

| Library | Where | Why |
|---|---|---|
| **Jackson** | Default everywhere: adapters, app services, DTOs, tests | House standard, shared with agent-core-java |
| **Gson** | A2A SDK protocol boundary only: `controller/a2a` (`A2aJsonRpcController.GSON`, `A2aJsonRpcProtocol`), SDK type serialization | The SDK's `JsonUtil`/`JSONRPCUtils` wire format is Gson-based; gson 2.11.0 is pinned for it |

**Rule**: new code uses Jackson. Touch Gson only when (de)serializing
SDK wire types (`Task`, `Message`, `StreamingEventKind`,
`TaskPushNotificationConfig`, JSON-RPC envelopes). Never introduce Gson
into adapters-common or spec.

## ObjectMapper — Reuse, Never Per-Call

```java
// OK — class-level singleton
private static final ObjectMapper MAPPER = new ObjectMapper();

// BAD — per call (rebuilds serializer caches; G.PRM.05 violation)
public String toJson() { return new ObjectMapper().writeValueAsString(this); }
```

- Thread-safe after configuration; one per class with custom config, share
  otherwise.
- Default configuration for most cases; when customizing:
  `.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)`
  for tolerant inbound DTOs, `@JsonInclude(Include.NON_NULL)` on the
  class over global config.
- Java 8 date/time: register `JavaTimeModule` (via
  `.findAndRegisterModules()`).

## Gson at the Boundary

- One `GsonBuilder` per concern, `static final`:
  `A2aJsonRpcController.GSON` uses `.disableHtmlEscaping()` — copy that
  flag when adding another Gson instance or JSON-RPC output breaks on
  HTML entities.
- Numeric JSON-RPC id fidelity: ids must round-trip as parsed
  (`JsonElement`), never through `double` — see `A2aJsonRpcProtocol`
  (regression: GitCode !262, issues 641/642). BigDecimal for exact
  integer checks.
- SDK `JsonUtil.toJson` wraps streaming events in a discriminator
  object — unwrapping happens in `jsonRpcResponse`; keep that logic
  centralized there.

## DTO Conventions

- Records or immutable POJOs for spec DTOs; Jackson works via
  components/getters — no `@JsonProperty` unless the wire key differs
  from the Java name (e.g. snake_case protocol keys).
- Inbound payloads are parsed defensively: unknown fields ignored,
  missing fields default; validation errors surface as protocol errors
  (`InvalidParamsError`), not Jackson exceptions.
