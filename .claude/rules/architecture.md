---
description: Module boundaries, dependency direction, controller discipline, and extension points for agent-runtime-java.
language: english
paths:
  - "service/agent-service-spec/**"
  - "service/agent-service-adapters/**"
  - "service/agent-service-app/**"
  - "service/agent-service-demo/**"
---

# Architecture Rules

## Module Boundary Discipline (enforced)

Dependency direction is one-way. Violations are review blockers:

```text
agent-service-spec  (contracts, DTO, SPI)
        ▲
agent-service-adapters  (bind spec → Agent Core; common middleware)
        ▲
agent-service-app  (Spring Boot app: controllers, auto-config, orchestration)
        ▲
agent-service-demo  (runnable apps, scenario examples)
```

| Forbidden | Why |
|---|---|
| spec → adapters/app/Spring | Spec is consumed by external service developers; must stay framework-neutral and dependency-free |
| adapters → app | Adapters bind contracts, they don't know the ingress |
| adapters → adapters (sibling) | Shared code goes in `adapters-common` |
| app classes calling Core `Runner` directly | All ingress flows through `ServeOrchestrator` (CONTRIBUTING rule) |

**Rule**: a new dependency in `agent-service-spec/pom.xml` requires a
justification in the PR description — spec is the public contract surface.

## Where Things Belong

| Change | Target module |
|---|---|
| New DTO / SPI / path constant / annotation | `agent-service-spec` |
| New middleware (redis, external-call, credential) | `agent-service-adapters/agent-service-adapters-common` |
| Binding spec interfaces to Agent Core types | `agent-service-adapters/agent-service-adapters-agentcore` |
| New HTTP/JSON-RPC endpoint | `agent-service-app` controller, delegating to an orchestrator |
| New Spring beans / configuration properties | `agent-service-app` auto-configuration |
| Agent Core primitive change | **agent-core-java repo**, not here |
| Runnable example for a scenario | `agent-service-demo/example/<scenario>` |

## Controller Discipline

- Controllers parse/validate transport payloads and delegate to
  `ServeOrchestrator` (or a dedicated coordinator); no business logic.
- Ingress security annotations (`@AuthorizedResource`) sit on the
  controller method; the aspect enforces them only when
  `openjiuwen.service.security.enabled=true` + `auth.enabled=true`.
- A2A JSON-RPC methods are dispatched in `A2aJsonRpcController.dispatch`;
  adding a method = new `case` + parser + handler delegation, keep the
  switch exhaustive and fall through to `MethodNotFoundError`.

## Auto-Configuration Conventions

- Every `*AutoConfiguration` is registered in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  — new files must be added there or they silently don't load.
- Bean gating order: `@ConditionalOnMissingBean` for the default bean,
  then `@ConditionalOnProperty` for the feature flag. User-provided beans
  must always win over framework defaults.
- Optional collaborators arrive as `ObjectProvider<T>` / field injection
  with `required = false` — treat `getIfAvailable()` null as a valid,
  supported state, never NPE.

## Task Store & Checkpoint Selection

`checkpointer.type` drives the store chain (see
`A2AAutoConfiguration.a2aTaskStore`):

- `in_memory` (default) → SDK `InMemoryTaskStore`
- `redis` → `WriteThrottlingTaskStore(RedisTaskStore)` — the write-behind
  wrapper exists so per-chunk SDK persistence doesn't throttle SSE; do not
  bypass it when touching Redis stores
- extension types → `A2ATaskStoreProvider` SPI (connection-ref scoped)

When changing store behavior, check `RedisTaskStoreTest`,
`WriteThrottlingTaskStoreTest`, and `TaskStoreProviderTest` still hold.

## Anti-patterns

- Business logic in controllers (parsing is fine, deciding is not)
- Reaching into `agent-service-app` internals from an adapter
- Duplicating a spec DTO into app because "it's easier" — extend the spec
  deliberately instead
- New `@Configuration` that duplicates an existing auto-configuration's
  bean name without `@ConditionalOnMissingBean`
