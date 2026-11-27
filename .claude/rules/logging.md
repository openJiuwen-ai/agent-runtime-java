---
description: Logger conventions, log levels, and sensitive-data rules for agent-runtime-java.
language: english
paths:
  - "service/**/src/main/java/**/*.java"
---

# Logging Rules

## Declaration

Direct SLF4J (this repo does **not** use the Core `Loggers` facade):

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpPushNotificationSender {
    private static final Logger log = LoggerFactory.getLogger(HttpPushNotificationSender.class);
}
```

- `private static final`, class-level, one per class (G.LOG.02).
- SLF4J facade only — never Log4j/Logback API classes directly (G.LOG.01).
- No `System.out.println` — no level, no routing.

## MDC / Trace Context

- Ingress trace ids are propagated via the A2A propagation-header
  registry (`A2APropagationHeaderRegistry`) and task logging keys on
  `taskId` / `contextId` / `conversationId`.
- Include the relevant ids in log messages as structured key=value pairs:
  `log.info("A2A execute START taskId={} contextId={} conversationId={} resume={} stream={}", ...)`
  — matches the existing style in `A2AAgentExecutor`.
- Do not invent new id-key formats; follow `key=value` with `{}`
  placeholders (never string concatenation).

## Levels

| Level | When |
|---|---|
| `debug` | Flow tracing, chunk-level detail; off in production |
| `info` | Lifecycle events (task admitted/started/completed, config loaded) |
| `warn` | Recoverable failures: delivery failed, recheck scheduled, invalid callback rejected |
| `error` | Failures affecting the current operation, always with exception |

- Log the exception object as last arg: `log.warn("... task {}", task.id(), e)`
  — never swallow silently, never `e.getMessage()` only.

## Sensitive Data

- Do not log raw request/response bodies, LLM content, credentials,
  tokens, or `Authorization` headers at `info`+.
- Push-notification callback URLs may embed tokens — log host only, or
  the sanitized `DeliveryRecord` reason string.
- `G.LOG.04`: log messages in English (this is a global product), code
  comments may be Chinese where the file's local convention is Chinese.

## Threading

- Log statements in async delivery paths (`CompletableFuture.runAsync`)
  must include the task/config identifiers — without MDC those logs are
  the only correlation.
