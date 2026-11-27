---
description: Test location, style, mocking, IT conventions, and running rules for agent-runtime-java.
language: english
paths:
  - "service/**/src/test/java/**/*.java"
alwaysApply: false
---

# Testing Rules

## Test Location & Naming

- Mirror the source path:
  `service/agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/RedisTaskStore.java`
  → `.../src/test/java/com/openjiuwen/service/app/controller/a2a/RedisTaskStoreTest.java`
- Naming: `*Test.java` (unit), `*IT.java` (integration, e.g.
  `A2aIntegrationTest`, `SessionManageEndpointIntegrationTest`).
  Surefire includes **both** — `mvn test` runs ITs too.
- Integration tests needing Redis/middleware live under
  `src/test/java/.../it/` and document prerequisites in the class
  Javadoc; check before running locally.

## Test Framework and Style

- JUnit 5 (via `spring-boot-starter-test`) + Mockito 5.14 + AssertJ
  3.27. Versions are managed in root `pom.xml` — never add per-module
  versions.
- Test method naming: `methodName_scenario_expected`.
- Structure as **Given / When / Then** (per X.TST.06).
- `@Nested` + `@DisplayName` for grouping complex scenarios.
- Spring slices: `@SpringBootTest` with properties/classes filters for
  wiring tests; `webmvc-test` for controller slice tests.

## Mocking Conventions

- **Programmatic Mockito** — inline `mock(X.class)` /
  `when(...).thenReturn(...)`; no `@Mock` / `@InjectMocks` /
  `@ExtendWith(MockitoExtension.class)` on new tests (aligns with
  agent-core-java convention).
- `ObjectProvider<T>` collaborators: use a simple stub or mock with
  `getIfAvailable()` returning null to cover the "absent" path — that's a
  supported runtime state, test it explicitly.
- Never mock the A2A SDK `Task`/`Message` records — build real instances;
  they're value types.

## What Must Be Covered

- New auto-configuration bean: a context test asserting the bean loads
  with defaults, is absent when the feature flag is off, and yields to a
  user bean (`@ConditionalOnMissingBean` order).
- New store / cache / queue: concurrency test (multi-threaded
  read/write), TTL/expiry behavior, and shutdown/close cleanup.
- A2A protocol changes: extend the existing lifecycle tests
  (`A2ATaskLifecycleTest`) rather than creating parallel ones.
- Regression rule: a bug fix ships with a failing-first test that
  reproduces the defect.

## Credentials and External Services

- No real keys/endpoints in test files. Use placeholder values or
  `ObjectProvider` absent-mocks.
- ITs requiring Redis declare it in Javadoc ("Requires Redis on
  localhost:6379 or TESTCONTAINERS"); skip-mark them when the service is
  unavailable only if they already handle it — do not add blanket
  `@Disabled`.

## Demo Examples

`agent-service-demo/example/*` modules are user-facing references —
each stays runnable (`spring-boot:run`) and self-contained. When adding
an example, mirror the existing module structure (own pom, own README).
