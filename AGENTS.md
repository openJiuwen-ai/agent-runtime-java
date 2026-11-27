---
description: Shared instructions for AI coding assistants in agent-runtime-java.
---

# AGENTS.md

Shared instructions for AI coding assistants working in `agent-runtime-java`.
Keep this file specific, factual, and cross-tool. Prefer nearby code and
tests over assumptions.

Root `pom.xml` is the canonical source of truth for versions and tooling
settings; the reactor lives under `service/`.

## What This Repo Is

This repository packages **Agent Core** (`agent-core-java`) into a
deployable **Agent Service** (HTTP + A2A). Module layout under `service/`:

- `agent-service-spec/`: framework-neutral service contracts — DTOs, SPI
  (`AgentHandler`, `ServeOrchestrator`, `QueryStreamObserver`), paths, and
  security annotations. Zero Spring dependencies; stays stable and small.
- `agent-service-adapters/`: adapter implementations binding the spec to
  Agent Core (`adapters-agentcore`), common middleware/external-call
  infrastructure (`adapters-common`), and vendor integrations.
- `agent-service-app/`: Spring Boot application — auto-configurations,
  REST/A2A controllers, orchestrators, task stores. The only module that
  owns HTTP ingress.
- `agent-service-demo/`: runnable demo apps and focused scenario examples
  (`example/*`) for redis, a2a, security, sandbox, memory, deepagent, etc.
- `documents/zh/`: user-facing guides. Update the relevant page and
  `documents/zh/SUMMARY.md` when adding topics or changing public behavior.

## Instruction Priority

- Follow system, tool, and user instructions first, then this file, then
  module-local docs (`CONTRIBUTING.md`, documents).
- Before changing behavior, inspect the touched module, its exported
  surface, and nearby tests/examples.
- Prefer small, targeted diffs. Do not refactor unrelated areas
  opportunistically.

## Core Architecture Rules

- **Module boundary discipline**: `spec` → `adapters` → `app` (one-way).
  Never let spec depend on adapters/app; never let adapters depend on app.
  Controllers must call `ServeOrchestrator`, never Core `Runner` directly.
- Changes to Agent Core primitives belong in
  [agent-core-java](https://gitcode.com/openJiuwen/agent-core-java), not
  here. This repo owns ingress, orchestration, and adapters only.
- Public API changes in `agent-service-spec` are deliberate and documented:
  spec is consumed by external service developers.
- A2A SDK (`org.a2aproject.sdk`) integration lives in `agent-service-app`
  (`controller/a2a`, `autoconfigure`). Protocol-boundary code may use Gson
  (the SDK's wire format); everything else uses Jackson. Do not mix.
- Auto-configurations (`*AutoConfiguration.java`) are the extension points
  of the app module: `@ConditionalOnMissingBean` first, then
  `@ConditionalOnProperty` gates; preserve that order when adding beans.
- Task store implementations (`InMemoryTaskStore`, `RedisTaskStore`,
  `WriteThrottlingTaskStore`) are interchangeable behind the SDK
  `TaskStore` interface; selection is driven by
  `checkpointer.type` config (`in_memory` / `redis` / extension providers).

## Build Layout and Commands

The Maven reactor root for code is `service/` (root pom manages versions
and dependencyManagement for the whole repo). Run with JDK 17 or newer:

- Build and install: `mvn clean install -DskipTests` (from `service/`)
- Run default tests: `cd service && mvn clean test`
- Single module with dependencies: `mvn -pl agent-service-app -am clean test`
- Run the demo: `mvn -pl agent-service-demo -am spring-boot:run`
- Surefire includes `**/*Test.java`, `**/*Tests.java`, and `**/*IT.java`
  — integration tests run with the normal test phase; some need Redis or
  other middleware (check test class Javadoc before running locally).
- Requires `agent-core-java` installed locally (or from the configured
  repository); the pinned version lives in root `pom.xml`
  `dependencyManagement` as a literal version.

## Java & JDK 17 Notes

- Source and target: Java 17. Use JDK 17 features (records, sealed
  classes, pattern matching for instanceof, text blocks) where
  appropriate.
- Lombok is not used in this repo — write explicit code.
- Jackson for JSON in service/adapters code. Gson appears only at the A2A
  SDK protocol boundary (`JsonUtil`/`GsonBuilder` in `controller/a2a`);
  keep new code on Jackson unless implementing an SDK type.
- SLF4J directly for logging: `private static final Logger log =
  LoggerFactory.getLogger(X.class)`. Do not introduce the Core
  `Loggers` facade here — runtime modules own their logging.
- Spring Boot 3.x: constructor injection, no field `@Autowired` on new
  beans (existing field injection for optional hooks is legacy, don't
  copy it).
- Do not use `Executors.newCachedThreadPool()` /
  `newFixedThreadPool()` — create `ThreadPoolExecutor` explicitly with a
  bounded queue and named threads (`threadFactory(...)` helpers exist in
  `A2AAutoConfiguration`).

## Coding Standards and Verification

- Follow the full rule set in `.claude/rules/` (see Reference Guides
  below); it encodes the same Huawei CodeArts 124-rule base as
  agent-core-java plus runtime-specific conventions.
- Use UTF-8, four-space indentation, explicit imports, and the
  surrounding project's Java style.
- Inspect the affected behavior and reuse existing test coverage. Add
  regression coverage when a meaningful gap exists; do not add tests
  mechanically.
- Run relevant tests for the affected module and `git diff --check` for
  whitespace. Investigate failures without weakening assertions or
  skipping checks.
- `IT` tests requiring external services document their prerequisites;
  run them when relevant to the change.
- Preserve unrelated changes and developer-local configuration. Report
  checks actually run, their results, and any unavailable verification.

## Reference Guides

- Coding standards quick reference: `.claude/rules/coding-standard.md`
  (full 144-rule detail: agent-core-java
  `.claude/skills/coding-standard-full/` — same rule set applies)
- Architecture boundaries: `.claude/rules/architecture.md`
- Testing conventions: `.claude/rules/test-flow.md`
- Dependency rules (incl. a2a-sdk/gson/protobuf compatibility):
  `.claude/rules/dependency-management.md`
- Dev workflow skills (specify/design/tdd/clean-code/review/fix/ship):
  `.claude/skills/devflow-*/`
- DevFlow entry point and workflow selection: `.claude/skills/using-devflow/`
- Templates and test scenarios: `.claude/templates/`, `.claude/test-scenarios/`
- Permissions and env vars: `.claude/settings.json`
