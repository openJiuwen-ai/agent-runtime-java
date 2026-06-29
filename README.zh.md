# openJiuwen Agent Runtime Java

[中文版](README.zh.md) | [English Version](README.md)

## 简介

**openJiuwen Agent Runtime Java**（`agent-runtime-java`）将基于 **openJiuwen Agent Core Java** 的智能体封装为 **可部署的 Spring Boot HTTP 服务**（OCI 镜像）。本仓库提供 **Agent Service** 层：与 Python `AgentApp` 对齐的 HTTP 对话面、进程内 **A2A**（Agent Card + JSON-RPC）、会话重置、健康探针，以及通过 **Adapters** 接入执行后端（Core `Runner`）、中间件（如 Redis Checkpointer）与外部服务 egress（MCP、远端/A2A、Sandbox）。

**Agent Core**（图执行、Agent、工作流）在独立仓库 [agent-core-java](https://gitcode.com/openJiuwen/agent-core-java) 中维护；本仓库专注 **服务化封装与数据面**。

**不在本仓库**（规划在其他模块/服务）：平台 Deploy Manager、独立平台 A2A 网关、App 控制面（`/chat`、Session CRUD）。详见下文 [范围与路线图](#范围与路线图)。

## 为什么选择 Agent Runtime Java？

- **最快上线 HTTP**：Spring Boot 自动装配 Controller、编排器、生命周期 Hook 与探针，开发者主要实现或选择 `AgentHandler`。

- **跨语言对齐**：`POST /v1/query`（SSE）、`POST /v1/reset_conversation`、`GET /health` — 与 Python `AgentApp` 路径与语义一致，便于网关统一路由与迁移。

- **进程内 A2A**：Agent Card、JSON-RPC（`SendMessage` / `SendStreamingMessage`）、TaskStore、远端 Agent 委派，无需单独部署平台 A2A 进程。

- **清晰模块边界**：`spec`（契约与 SPI）→ `adapters`（执行引擎、中间件、外部服务 egress）→ `app`（Ingress + Orchestrator），依赖单向，便于定制镜像。

## 快速开始

### 环境要求

- **操作系统**：Windows、Linux、macOS。
- **Java 版本**：Java 17 或更高。
- **构建工具**：Maven 3.9+。
- **agent-core-java**：Maven 依赖 `com.openjiuwen:agent-core-java:0.1.12`（见根 `pom.xml`）。需 clone [agent-core-java](https://gitcode.com/openJiuwen/agent-core-java) 并 checkout 标签 `0.1.12` 后执行 `mvn install`，或在制品发布后从 Maven 仓库拉取。

### 从源码构建

```bash
git clone https://gitcode.com/openJiuwen/agent-runtime-java.git
cd agent-runtime-java
mvn clean install -DskipTests
```

### 运行测试

在 `service` 目录执行：

```bash
cd service
mvn clean test
```

### 运行 Demo

默认 **mock** handler，端口 **8090**：

```bash
cd service
mvn -pl agent-service-demo -am spring-boot:run
```

非流式请求：

```bash
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1","message":"hello","stream":false}'
```

mock 模式下预期：`{"result":{"content":"demo:hello",...}}`。

流式请求（SSE）：

```bash
curl -N -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1","message":"hello","stream":true}'
```

Agent Card（A2A）：

```bash
curl -s http://localhost:8090/.well-known/agent-card.json
```

更多示例（LLM 模式、自定义 Handler、MCP/A2A 样例）见 [service/agent-service-demo/README.md](service/agent-service-demo/README.md)。

### 作为依赖使用

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

并提供 `@Bean AgentHandler` 或配置 `openjiuwen.service.agent-id` 使用默认 Core Handler。

## 架构设计

**Agent Runtime Java** 承载分布式 Agent 运行时的 Java 侧实现：左侧 **中间件**（Redis 等），中间 **Gateway + Agent Server**，右侧 **外部服务**（LLM、MCP、A2A 对等体、RAG 等）。

```text
中间件 (Redis…)  →  Agent Server（本仓：Spring Boot + Adapters + Core）  →  外部服务 (LLM, MCP, A2A…)
```

| 逻辑组件 | 本仓库 | 状态 |
|----------|--------|------|
| **Agent Core** | Maven `agent-core-java`（独立仓库） | ✅ 依赖 |
| **Agent Server** | `service/*` | ✅ |
| **进程内 A2A** | `agent-service-app`（`controller.a2a`） | ✅ |
| **Agent Runtime Manager** | 规划（`manager/*`） | ⏳ |
| **平台 A2A 网关** | 规划（独立服务） | ⏳ |

深入阅读：[逻辑架构](documents/zh/2.开发指南/逻辑架构.md)、[架构概述](documents/zh/2.开发指南/架构概述.md)。

**HTTP 对话调用链**：

```text
HTTP Controller → ServeOrchestrator → AgentHandler → Core Runner
```

**A2A 调用链**（启用时）：

```text
A2A Client → Agent Card / JSON-RPC → A2AProtocolAdapter → ServeOrchestrator → AgentHandler → Runner
```

Controller **禁止**绕过 Orchestrator 直连 `Runner`。

## 功能特性

### Agent Service（HTTP Ingress）

- **Query**：`POST /v1/query`、`POST /query`（兼容）、`POST /v1/query/reactive`（WebFlux）。
- **会话重置**：`POST /v1/reset_conversation`、`POST /reset_conversation`。
- **健康探针**：`GET /health` — `process_up`、`agent_loaded`，可用于 liveness/readiness。
- **租户上下文**：`X-User-ID`、`X-Space-ID` 与 body 字段对齐 Python。

### A2A（进程内）

- **Agent Card**：`GET /.well-known/agent-card.json`（及 `/a2a` 下兼容路径）。
- **JSON-RPC**：`POST /a2a/` — `SendMessage`、`SendStreamingMessage`。
- **TaskStore**：内存或 Redis；增强 Orchestrator 支持远端 Agent 发现与委派。
- 详见 [A2A 开发指导](documents/zh/2.开发指南/A2A开发指导.md)。

### Adapters（执行后端与 egress）

Adapters 负责将编排层接到 **执行后端**，并把 **中间件** 与 **外部服务** 注册进运行时：

| 层次 | 模块 | 职责 |
|------|------|------|
| **共享层** | `adapters-common` | 与引擎无关的中间件客户端（Redis 等）、凭证解密、外部调用 DFX（超时、重试、熔断） |
| **Agent Core leaf** | `adapters-agentcore` | `JiuwenCoreAgentHandler`；将 Checkpointer/中间件写入 Core `RunnerConfig`；绑定 MCP、远端/A2A、Sandbox 等出站 SPI |

| Handler 选型 | 配置 | 说明 |
|--------------|------|------|
| **agentcore**（默认） | `openjiuwen.service.agent-id` | `JiuwenCoreAgentHandler` → Core `Runner` |
| **自定义** | `@Bean AgentHandler` | 覆盖默认装配（代理、远端引擎等） |

详见 [Adapters 与 Handler](documents/zh/2.开发指南/Adapters与Handler.md)。

### 生命周期

- Init / Shutdown Hook、就绪门控（`agent_loaded`）、流式活动流注册与进程内 interrupt（无独立 interrupt REST）。

## 范围与路线图

| 主题 | 本仓库 | 说明 |
|------|--------|------|
| HTTP Agent Service | ✅ | `service/*` |
| 进程内 A2A Server | ✅ | Agent Card、JSON-RPC、TaskStore |
| 平台 A2A 网关 | ❌ | 平台级多 Agent 路由 |
| Deploy Manager REST | ❌ | 规划中的控制面 |
| App 控制面 | ❌ | `/chat`、Session CRUD、Workspace 动态挂载 |

## 项目结构

```text
agent-runtime-java/
├── service/
│   ├── agent-service-spec/           # 路径、DTO、SPI（AgentHandler、Orchestrator）
│   ├── agent-service-adapters/       # common（中间件 + 外部 DFX）+ agentcore leaf
│   │   ├── agent-service-adapters-common   # Redis 等中间件客户端、凭证、外部调用 DFX
│   │   └── agent-service-adapters-agentcore # Handler + Core 中间件/外部服务注册
│   ├── agent-service-app/            # Controller、编排器、A2A、自动装配
│   ├── agent-service-demo/           # 可运行 Spring Boot 示例
│   └── agent-service-a2a-test/       # A2A 集成与场景测试
├── documents/zh/                     # 中文开发指南
│   └── SUMMARY.md
├── CONTRIBUTING.md
├── LICENSE
├── README.md
└── README.zh.md
```

## 完整文档

文档索引：[documents/zh/SUMMARY.md](documents/zh/SUMMARY.md)。

**按目标阅读**：

| 目标 | 入口 |
|------|------|
| 本地跑 Demo | [快速开始](documents/zh/2.开发指南/快速开始.md) · [demo README](service/agent-service-demo/README.md) |
| HTTP API 与 SSE | [HTTP 对话面](documents/zh/2.开发指南/HTTP对话面.md) |
| A2A Server / Client | [A2A 开发指导](documents/zh/2.开发指南/A2A开发指导.md) · [A2A 与平台边界](documents/zh/2.开发指南/A2A与平台边界.md) |
| 自定义 Handler | [Adapters 与 Handler](documents/zh/2.开发指南/Adapters与Handler.md) |
| 生命周期与探针 | [生命周期与探针](documents/zh/2.开发指南/生命周期与探针.md) |
| 全局理解 | [开发指南总入口](documents/zh/2.开发指南/README.md) · [逻辑架构](documents/zh/2.开发指南/逻辑架构.md) |
| 模块说明 | [service/README.md](service/README.md) |

**Agent Core**（Agent、工作流、Runner）：[agent-core-java 文档](https://gitcode.com/openJiuwen/agent-core-java/tree/0.1.12/documents/zh/SUMMARY.md)。

详细开发指南目前为中文；README 提供中英双语，英文详细文档规划中。

## 参与贡献

欢迎提交 Issue、改进文档、贡献代码与分享使用经验。详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 开源许可证

本项目依据 [Apache License 2.0](LICENSE) 授权。
