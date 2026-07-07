# openJiuwen Agent Runtime Java

[中文版](README.zh.md) | [English Version](README.md)

## 简介

**openJiuwen Agent Runtime Java**（`agent-runtime-java`）是 **Agent Distributed Runtime 的 Java 实现仓库**（对应架构图中间 Runtime 大框）。当前 **已交付最多的是 `service/` 模块**，即图中的 **Agent Server**：Spring Boot HTTP 服务、进程内 A2A、Adapters 胶水，以及通过 Maven 依赖接入的 **Agent Core** 执行能力。

**Agent Core**（图执行、Agent、工作流）在独立仓库 [agent-core-java](https://gitcode.com/openJiuwen/agent-core-java)；**Agent Runtime Manager** 等将规划在 **本仓** `manager/*`。与 Python 为 **同级 Runtime 实现**（Python 侧 Agent Server 常用 FastAPI / Yuanrong FaaS）。

详见 [范围与路线图](#范围与路线图) 与 [逻辑架构](documents/zh/2.开发指南/逻辑架构.md)。

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
- **agent-core-java**：Maven 依赖见根 `pom.xml`（`agent-core.version` / `agent-core.git.branch`）。本地构建步骤见 [Agent Core 依赖](documents/zh/2.开发指南/Agent Core 依赖.md)。

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

**Agent Runtime Java** 对应架构图中的 **Agent Distributed Runtime（Java）**；**`service/`** 对应其中的 **Agent Server**。

```text
中间件 (Redis…)  →  Runtime（本仓）· Agent Server（service/）  →  外部服务 (LLM, MCP, A2A…)
```

| 逻辑组件 | `agent-runtime-java` 内 | 状态 |
|----------|---------------------------|------|
| **Agent Distributed Runtime** | **仓库根** | ✅ Java 载体（持续扩展） |
| **Agent Core** | Maven `agent-core-java` | ✅ 依赖 |
| **Agent Server** | `service/*` | ✅ 当前主交付 |
| **进程内 A2A** | `agent-service-app` | ✅ |
| **Agent Runtime Manager** | `manager/*` | ⏳ 规划于 **本仓** |
| **平台 A2A 网关** | `applications/*` 或机构侧 | ⏳ / 🔌 |

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

| 主题 | 本仓路径 | 说明 |
|------|----------|------|
| Agent Server（HTTP + A2A） | ✅ `service/*` | 当前主交付 |
| Agent Runtime Manager | ⏳ `manager/*` | 控制面，规划于 **本仓** |
| 平台 A2A 网关 | ⏳ `applications/*` 等 | 或机构侧独立服务 |
| App 控制面 | ❌ | `/chat`、Session CRUD 等不在 Server 范围 |

## 项目结构

```text
agent-runtime-java/                 # Agent Distributed Runtime（Java）
├── service/                          # Agent Server
│   ├── agent-service-spec/
│   ├── agent-service-adapters/
│   ├── agent-service-app/
│   └── agent-service-demo/
├── manager/（规划）                  # Agent Runtime Manager
├── applications/（规划）
├── documents/zh/
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

**Agent Core**（Agent、工作流、Runner）：[agent-core-java](https://gitcode.com/openJiuwen/agent-core-java)（版本见 [Agent Core 依赖](documents/zh/2.开发指南/Agent Core 依赖.md)）。

详细开发指南目前为中文；README 提供中英双语，英文详细文档规划中。

## 参与贡献

欢迎提交 Issue、改进文档、贡献代码与分享使用经验。详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 开源许可证

本项目依据 [Apache License 2.0](LICENSE) 授权。
