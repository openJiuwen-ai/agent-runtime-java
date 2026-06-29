# openJiuwen Agent Runtime Java

[中文版](README.zh.md) | [English Version](README.md)

## Introduction

**openJiuwen Agent Runtime Java** (`agent-runtime-java`) packages agents built on **openJiuwen Agent Core Java** into a **deployable Spring Boot HTTP service** (OCI image). It provides the **Agent Service** layer: HTTP ingress aligned with Python `AgentApp`, in-process **A2A** (Agent Card + JSON-RPC), session reset, health probes, and **Adapters** that bind execution backends (Core `Runner`), middleware (e.g. Redis Checkpointer), and external egress (MCP, remote/A2A, Sandbox).

**Agent Core** (graph execution, agents, workflows) lives in the separate [agent-core-java](https://gitcode.com/openJiuwen/agent-core-java) repository. This repository focuses on **service packaging and the data plane**.

**Not in this repository** (planned elsewhere): platform Deploy Manager, standalone platform A2A gateway, App control plane (`/chat`, Session CRUD). See [Scope & roadmap](#scope--roadmap) below.

## Why Agent Runtime Java?

- **Minimal path to HTTP**: Spring Boot auto-configures controllers, orchestrator, lifecycle hooks, and probes — you mainly provide or select an `AgentHandler`.

- **Cross-language alignment**: `POST /v1/query` (SSE), `POST /v1/reset_conversation`, `GET /health` — same routes and semantics as Python `AgentApp` for gateway routing and migration.

- **In-process A2A**: Agent Card, JSON-RPC (`SendMessage` / `SendStreamingMessage`), TaskStore, and remote-agent delegation without a separate A2A platform process.

- **Clear module boundaries**: `spec` (contracts & SPI) → `adapters` (execution engine, middleware, external egress) → `app` (Ingress + Orchestrator) — one-way dependencies for custom images.

## Quick Start

### Requirements

- **OS**: Windows, Linux, macOS.
- **Java**: 17+.
- **Build**: Maven 3.9+.
- **agent-core-java**: `com.openjiuwen:agent-core-java:0.1.12` (see root `pom.xml`). Clone [agent-core-java](https://gitcode.com/openJiuwen/agent-core-java), checkout tag `0.1.12`, run `mvn install`, or resolve from your Maven repository when published.

### Build from Source

```bash
git clone https://gitcode.com/openJiuwen/agent-runtime-java.git
cd agent-runtime-java
mvn clean install -DskipTests
```

### Run Tests

From the `service` directory:

```bash
cd service
mvn clean test
```

### Run the Demo

Default **mock** handler, port **8090**:

```bash
cd service
mvn -pl agent-service-demo -am spring-boot:run
```

Non-streaming query:

```bash
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1","message":"hello","stream":false}'
```

Expected in mock mode: `{"result":{"content":"demo:hello",...}}`.

Streaming query (SSE):

```bash
curl -N -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1","message":"hello","stream":true}'
```

Agent Card (A2A):

```bash
curl -s http://localhost:8090/.well-known/agent-card.json
```

More examples (LLM mode, custom handlers, MCP/A2A samples): [service/agent-service-demo/README.md](service/agent-service-demo/README.md).

### Use as a Maven Dependency

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-app</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-adapters-agentcore</artifactId>
    <version>0.1.0</version>
</dependency>
```

Provide a `@Bean AgentHandler` or set `openjiuwen.service.agent-id` for the default Core handler.

## Architecture

**Agent Runtime Java** is the Java side of the distributed Agent runtime: **middleware** (Redis, etc.) on the left, **Gateway + Agent Server** in the center, **external services** (LLM, MCP, A2A peers, RAG) on the right.

```text
Middleware (Redis…)  →  Agent Server (this repo: Spring Boot + Adapters + Core)  →  External (LLM, MCP, A2A…)
```

| Logical component | In this repo | Status |
|-------------------|--------------|--------|
| **Agent Core** | Maven `agent-core-java` (separate repo) | ✅ dependency |
| **Agent Server** | `service/*` | ✅ |
| **In-process A2A** | `agent-service-app` (`controller.a2a`) | ✅ |
| **Agent Runtime Manager** | planned (`manager/*`) | ⏳ |
| **Platform A2A gateway** | planned (separate service) | ⏳ |

Deep dive (Chinese): [Logical Architecture](documents/zh/2.开发指南/逻辑架构.md), [Architecture Overview](documents/zh/2.开发指南/架构概述.md).

**HTTP query call chain**:

```text
HTTP Controller → ServeOrchestrator → AgentHandler → Core Runner
```

**A2A call chain** (when enabled):

```text
A2A Client → Agent Card / JSON-RPC → A2AProtocolAdapter → ServeOrchestrator → AgentHandler → Runner
```

Controllers must **not** bypass the orchestrator to call `Runner` directly.

## Features

### Agent Service (HTTP Ingress)

- **Query**: `POST /v1/query`, `POST /query` (legacy), `POST /v1/query/reactive` (WebFlux).
- **Reset**: `POST /v1/reset_conversation`, `POST /reset_conversation`.
- **Health**: `GET /health` — `process_up`, `agent_loaded` for liveness/readiness.
- **Tenant context**: `X-User-ID`, `X-Space-ID` headers aligned with Python.

### A2A (in-process)

- **Agent Card**: `GET /.well-known/agent-card.json` (and compatible paths under `/a2a`).
- **JSON-RPC**: `POST /a2a/` — `SendMessage`, `SendStreamingMessage`.
- **TaskStore**: in-memory or Redis; remote-agent discovery and delegation via enhanced orchestrator.
- Details: [A2A Guide](documents/zh/2.开发指南/A2A开发指导.md) (Chinese).

### Adapters

Adapters bind the orchestrator to **execution backends** and wire **middleware** and **external services** into the runtime:

| Layer | Module | Role |
|-------|--------|------|
| **Shared** | `adapters-common` | Engine-agnostic middleware clients (Redis, etc.), credential helpers, external-call DFX (timeout, retry, circuit breaker) |
| **Agent Core leaf** | `adapters-agentcore` | `JiuwenCoreAgentHandler`; registers Checkpointer/middleware into Core `RunnerConfig`; binds MCP, remote/A2A, Sandbox outbound SPI |

| Handler | Configuration | Backend |
|---------|---------------|---------|
| **agentcore** (default) | `openjiuwen.service.agent-id` | `JiuwenCoreAgentHandler` → Core `Runner` |
| **custom** | `@Bean AgentHandler` | override default binding (proxy, remote engine, etc.) |

Details: [Adapters & Handler](documents/zh/2.开发指南/Adapters与Handler.md) (Chinese).

### Lifecycle

- Init / Shutdown hooks, readiness gate (`agent_loaded`), active stream tracking, in-process interrupt (no separate interrupt REST).

## Scope & roadmap

| Topic | In this repo | Notes |
|-------|--------------|-------|
| HTTP Agent Service | ✅ | `service/*` |
| In-process A2A Server | ✅ | Agent Card, JSON-RPC, TaskStore |
| Platform A2A gateway | ❌ | multi-agent routing at platform level |
| Deploy Manager REST | ❌ | planned control plane |
| App control plane | ❌ | `/chat`, Session CRUD, dynamic Workspace |

## Project Structure

```text
agent-runtime-java/
├── service/
│   ├── agent-service-spec/           # paths, DTOs, SPI (AgentHandler, Orchestrator)
│   ├── agent-service-adapters/       # common (middleware + external DFX) + agentcore leaf
│   │   ├── agent-service-adapters-common   # middleware clients (Redis), credential, external-call DFX
│   │   └── agent-service-adapters-agentcore # Handler + Core middleware/external registration
│   ├── agent-service-app/            # controllers, orchestrator, A2A, auto-config
│   ├── agent-service-demo/           # runnable Spring Boot demo
│   └── agent-service-a2a-test/       # A2A integration / scenario tests
├── documents/zh/                     # Chinese development guide
│   └── SUMMARY.md
├── CONTRIBUTING.md
├── LICENSE
├── README.md
└── README.zh.md
```

## Documentation

Index: [documents/zh/SUMMARY.md](documents/zh/SUMMARY.md) (Chinese).

**By goal**:

| Goal | Start here |
|------|------------|
| Run demo locally | [Quick Start](documents/zh/2.开发指南/快速开始.md) · [demo README](service/agent-service-demo/README.md) |
| HTTP API & SSE | [HTTP Data Plane](documents/zh/2.开发指南/HTTP对话面.md) |
| A2A Server / Client | [A2A Guide](documents/zh/2.开发指南/A2A开发指导.md) · [A2A vs platform](documents/zh/2.开发指南/A2A与平台边界.md) |
| Custom Handler | [Adapters & Handler](documents/zh/2.开发指南/Adapters与Handler.md) |
| Lifecycle & probes | [Lifecycle & Probes](documents/zh/2.开发指南/生命周期与探针.md) |
| Big picture | [Development Guide](documents/zh/2.开发指南/README.md) · [Logical Architecture](documents/zh/2.开发指南/逻辑架构.md) |
| Module layout | [service/README.md](service/README.md) |

**Agent Core** (agents, workflows, Runner): [agent-core-java docs](https://gitcode.com/openJiuwen/agent-core-java/tree/0.1.12/documents/zh/SUMMARY.md).

English documentation is planned; README is bilingual; detailed guides are currently Chinese-only.

## Contributing

We welcome issues, documentation improvements, code, and usage feedback. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

This project is licensed under the [Apache License 2.0](LICENSE).
