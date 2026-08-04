# openJiuwen Agent Runtime Java

[中文版](README.zh.md) | [English Version](README.md)

## Introduction

**openJiuwen Agent Runtime Java** (`agent-runtime-java`) is the **Java implementation repository for Agent Distributed Runtime** (the large Runtime box in the architecture diagram). **`service/`** is the **Agent Server** slice inside that Runtime — currently the main delivered module: Spring Boot HTTP ingress, in-process A2A, Adapters, and **Agent Core** via Maven.

**Agent Core** lives in [agent-core-java](https://github.com/openJiuwen-ai/agent-core-java). **Agent Runtime Manager** is planned under **`manager/*` in this same repo**. Python is a **peer Runtime** implementation (Agent Server often on FastAPI / Yuanrong FaaS).

See [Scope & roadmap](#scope--roadmap) and [Logical Architecture](documents/zh/2.开发指南/逻辑架构.md) (Chinese).

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
- **agent-core-java**: see root `pom.xml` (`agent-core.version` / `agent-core.git.branch`). Local build: [Agent Core dependency](documents/zh/2.开发指南/Agent Core 依赖.md).

### Build from Source

```bash
git clone https://github.com/openJiuwen-ai/agent-runtime-java.git
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
    <version>0.1.1</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-adapters-agentcore</artifactId>
    <version>0.1.1</version>
</dependency>
```

Provide a `@Bean AgentHandler` or set `openjiuwen.service.agent-id` for the default Core handler.

## Architecture

**Agent Runtime Java** maps to **Agent Distributed Runtime (Java)** in the architecture diagram; **`service/`** maps to **Agent Server** inside it.

```text
Middleware (Redis…)  →  Runtime (this repo) · Agent Server (service/)  →  External (LLM, MCP, A2A…)
```

| Logical component | In `agent-runtime-java` | Status |
|-------------------|-------------------------|--------|
| **Agent Distributed Runtime** | **repo root** | ✅ Java carrier (expanding) |
| **Agent Core** | Maven `agent-core-java` | ✅ dependency |
| **Agent Server** | `service/*` | ✅ main delivery today |
| **In-process A2A** | `agent-service-app` | ✅ |
| **Agent Runtime Manager** | `manager/*` | ⏳ planned **in this repo** |
| **Platform A2A gateway** | `applications/*` or external | ⏳ / 🔌 |

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

| Topic | Path in repo | Notes |
|-------|--------------|-------|
| Agent Server (HTTP + A2A) | ✅ `service/*` | main delivery today |
| Agent Runtime Manager | ⏳ `manager/*` | control plane, **this repo** |
| Platform A2A gateway | ⏳ `applications/*` etc. | or external platform service |
| App control plane | ❌ | `/chat`, Session CRUD not in Server scope |

## Project Structure

```text
agent-runtime-java/                 # Agent Distributed Runtime (Java)
├── service/                        # Agent Server
│   ├── agent-service-spec/
│   ├── agent-service-adapters/
│   ├── agent-service-app/
│   └── agent-service-demo/
├── manager/（planned）             # Agent Runtime Manager
├── applications/（planned）
├── documents/zh/
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

**Agent Core** (agents, workflows, Runner): [agent-core-java](https://github.com/openJiuwen-ai/agent-core-java) ([dependency pinning](documents/zh/2.开发指南/Agent Core 依赖.md)).

English documentation is planned; README is bilingual; detailed guides are currently Chinese-only.

## Contributing

We welcome issues, documentation improvements, code, and usage feedback. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

This project is licensed under the [Apache License 2.0](LICENSE).

This product serves solely as a workflow orchestration tool and does not embed any AI model capabilities. When users integrate AI models for specific business scenarios, they shall bear full responsibility for compliance obligations under the EU AI Act and other relevant regulatory frameworks.
