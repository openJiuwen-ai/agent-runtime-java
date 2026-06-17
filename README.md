# openJiuwen Agent Runtime Java

[中文版](README.zh.md) | [English Version](README.md)

## Introduction

**openJiuwen Agent Runtime Java** (`agent-runtime-java`) is the runtime repository for **Java high-code Agent as a Service (AaaS)**. It builds on **openJiuwen Agent Core Java** and provides the **Agent Service** layer: packaging a single Agent into a deployable HTTP service (OCI image) with a Python `AgentApp`-aligned data plane (C-013) and **Adapters** for Core `Runner`, remote Versatile, and other backends.

This repository does **not** implement the platform Deploy Manager or standalone A2A Service process. See architecture docs and the planned `runtime-management` module for control plane and platform A2A.

## Why Agent Runtime Java?

- **Ready-to-run Agent Service**: Spring Boot auto-configures controllers, orchestrator, lifecycle, and probes; you mainly implement or select an `AgentHandler`.

- **Python-aligned data plane**: `POST /query` (SSE), `POST /reset_conversation`, `GET /health` for cross-language migration and unified gateway routing.

- **Clear module boundaries**: `spec` (contracts & SPI) → `adapters` (engine bindings) → `app` (Ingress + Orchestrator), one-way dependencies for custom images.

- **Multiple Handler backends**: default **Agent Core** path; optional **agentcore-ext** (interrupt/hold) and **versatile** (remote low-code HTTP).

## Quick Start

### Requirements

- **OS**: Windows, Linux, macOS.
- **Java**: 17+ for this repo build; `vendor/agent-core-java` submodule requires Java 21.
- **Build**: Maven 3.9+.
- **Submodule**: run `git submodule update --init --recursive` after clone.

### Build from Source

```bash
git clone <repository-url>
cd agent-runtime-java
git submodule update --init --recursive
mvn clean install -DskipTests
```

### Run the Demo

From the `service` directory (default mock handler, port 8090):

```bash
cd service
mvn -pl agent-service-demo -am spring-boot:run
```

Non-streaming request:

```bash
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1","message":"hello","stream":false}'
```

In mock mode, expect `{"result":{"content":"demo:hello",...}}`.

See [service/agent-service-demo/README.md](service/agent-service-demo/README.md) for more examples.

### Maven Dependencies

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

**Agent Runtime Java** implements the **Agent Distributed Runtime** in Java: **middleware** (left), **Gateway + distributed runtime** (center), and **external services** (right: LLM, MCP, A2A, RAG, etc.). This repo currently ships **Agent Server** (`service/`) and **Agent-Core** (submodule); **Agent Runtime Manager** and full Gateway modules are planned under `manager/*`.

See [Logical Architecture](documents/zh/2.开发指南/逻辑架构.md) (Chinese) and [Architecture Overview](documents/zh/2.开发指南/架构概述.md).

**Data plane call chain**:

```text
HTTP Controller → ServeOrchestrator → AgentHandler → (Core Runner / Versatile HTTP)
```

Controllers must **not** bypass the orchestrator to call Runner directly.

## Features

### Agent Service (Ingress)

- **Query REST**: `POST /v1/query`, `POST /query` (legacy), `POST /v1/query/reactive` (WebFlux).
- **Reset**: `POST /v1/reset_conversation`.
- **Health**: `GET /health` (`process_up` / `agent_loaded`).
- **Tenant headers**: `X-User-ID`, `X-Space-ID` aligned with Python.

### Adapters

| Handler | Configuration | Backend |
|---------|---------------|---------|
| **agentcore** (default) | `openjiuwen.service.agent-id` | `JiuwenCoreAgentHandler` → Core `Runner` |
| **agentcore-ext** | `handler=agentcore-ext` + `agent-id` | interrupt / hold-resume |
| **versatile** | `handler=versatile` + `versatile.base-url` | remote Versatile HTTP |
| **custom** | `@Bean AgentHandler` | overrides auto-config |

### Out of Scope (current P0)

- In-process A2A Server (Agent Card, JSON-RPC); platform A2A is a separate service.
- App control plane `/chat`, Session CRUD, dynamic Workspace.
- Deploy Manager REST (`runtime-management`).

## Project Structure

```text
agent-runtime-java/
├── vendor/agent-core-java/          # Submodule · Agent Core SDK
├── service/
│   ├── agent-service-spec/
│   ├── agent-service-adapters/
│   ├── agent-service-app/
│   └── agent-service-demo/
├── documents/zh/
│   └── SUMMARY.md
├── README.md
└── README.zh.md
```

## Documentation

Index: [documents/zh/SUMMARY.md](documents/zh/SUMMARY.md).

Recommended reading:

- [Development Guide](documents/zh/2.开发指南/README.md)
- [Quick Start](documents/zh/2.开发指南/快速开始.md)
- [Architecture Overview](documents/zh/2.开发指南/架构概述.md)
- [HTTP Data Plane](documents/zh/2.开发指南/HTTP对话面.md)
- [Building Agent Service](documents/zh/2.开发指南/开发Agent Service.md)
- [Service Modules](service/README.md)

Agent Core docs: [vendor/agent-core-java/documents/zh/SUMMARY.md](vendor/agent-core-java/documents/zh/SUMMARY.md).

## Contributing

We welcome issues, documentation improvements, code contributions, and usage feedback.

## License

Apache-2.0 License (see submodule LICENSE files where applicable).
