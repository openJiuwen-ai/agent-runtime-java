# openJiuwen Agent Runtime Java

[中文版](README.zh.md) | [English Version](README.md)

## 简介

**openJiuwen Agent Runtime Java**（`agent-runtime-java`）是面向 **Java 高码 Agent 服务化（AaaS）** 的运行时仓库。它在 **openJiuwen Agent Core Java** 之上提供 **Agent Service** 层：将单个 Agent 封装为可部署的 HTTP 服务（OCI 镜像），对外暴露与 Python `AgentApp` 对齐的对话面（C-013），并通过 **Adapters** 接入 Core `Runner`、远端 Versatile 等执行后端。

本仓库 **不包含** 平台 Deploy Manager 与独立 A2A Service 进程的实现；控制面部署与平台级 A2A 见架构文档与 `runtime-management` 规划。

## 为什么选择 Agent Runtime Java？

- **开箱即用的 Agent Service**：Spring Boot 自动装配 Controller、编排器、生命周期与探针，开发者主要实现或选择 `AgentHandler`。

- **与 Python Runtime 对齐的数据面**：`POST /query`（SSE）、`POST /reset_conversation`、`GET /health`，便于跨语言迁移与网关统一路由。

- **清晰的模块边界**：`spec`（契约与 SPI）→ `adapters`（引擎适配）→ `app`（Ingress + Orchestrator），依赖单向、便于定制镜像。

- **单一 Core Handler 后端**：默认 **Agent Core** 高码链路；其他执行后端通过自定义 `AgentHandler` 接入。

## 快速开始

### 环境要求

- **操作系统**：Windows、Linux、macOS。
- **Java 版本**：Java 17 或更高。
- **构建工具**：Maven 3.9+。
- **agent-core-java**：Maven 依赖 `com.openjiuwen:agent-core-java:0.1.12`（见根 `pom.xml`）。需先 clone [agent-core-java](https://gitcode.com/openJiuwen/agent-core-java) 并 checkout `0.1.12` 后 `mvn install`，或从机构私服拉取同版本制品。

### 从源码构建

```bash
git clone <repository-url>
cd agent-runtime-java
mvn clean install -DskipTests
```

### 运行 Demo

从 `service` 目录启动最小示例（默认 mock handler，端口 8090）：

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

更多示例见 [service/agent-service-demo/README.md](service/agent-service-demo/README.md)。

### 作为依赖使用

在业务 Maven 工程中引入 `agent-service-app` 与所需 adapters leaf：

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

**Agent Runtime Java** 承载 **Agent Distributed Runtime** 的 Java 实现：从左到右为 **中间件**、**Gateway + 分布式运行时**、**外部服务**（LLM、MCP、A2A、RAG 等）。当前仓库以 **Agent Server（`service/`）** 为主；**Agent-Core** 通过 Maven 依赖独立仓库 **agent-core-java**；**Agent Runtime Manager**、完整 Gateway 等将随 `manager/*` 等模块补充。

```text
中间件          Gateway · Manager · Session · Agent Server · Infra          外部服务
(Redis…)   →   (本仓: Agent-App + Core + Adapters / Spring Boot)   →   (LLM, MCP, A2A…)
```

完整逻辑架构图与模块映射见 [documents/zh/2.开发指南/逻辑架构.md](documents/zh/2.开发指南/逻辑架构.md)；当前仓库模块见 [架构概述](documents/zh/2.开发指南/架构概述.md)。

| 逻辑块 | 本仓库 | 状态 |
|--------|--------|------|
| **Agent-Core** | Maven `agent-core-java`（独立仓库） | ✅ |
| **Agent Server** | `service/*` | ✅ |
| **Agent Runtime Manager** | `manager/*`（规划） | ⏳ |
| **Agent Gateway** | 机构网关 + HTTP Query | 🔌 / 部分 |
| **平台 A2A** | `applications/a2a-service`（规划） | ⏳ |

**Agent Server 数据面调用链**：

```text
HTTP Controller → ServeOrchestrator → AgentHandler → Core Runner
```

Controller **禁止**绕过 Orchestrator 直连 Runner。

## 功能特性

### Agent Service（Ingress）

- **Query REST**：`POST /v1/query`、`POST /query`（兼容）、`POST /v1/query/reactive`（WebFlux）。
- **会话重置**：`POST /v1/reset_conversation`。
- **健康探针**：`GET /health`（`process_up` / `agent_loaded`）。
- **租户上下文**：支持 `X-User-ID`、`X-Space-ID` 与 body 字段对齐 Python。

### Adapters（执行后端）

| Handler 选型 | 配置 | 说明 |
|--------------|------|------|
| **agentcore**（默认） | `openjiuwen.service.agent-id` | `JiuwenCoreAgentHandler` → Core `Runner` |
| **自定义** | `@Bean AgentHandler` | 覆盖默认装配（中断、远端引擎等） |

### 生命周期

- Init / Shutdown Hook、就绪门控（`agent_loaded`）、流式活动流注册与 interrupt（进程内，无 REST）。

### 本期不包含

- 进程内 A2A Server（Agent Card、JSON-RPC）；平台 A2A 为独立服务。
- App 控制面 `/chat`、Session CRUD、Workspace 动态挂载。
- Deploy Manager REST（`runtime-management` 另模块）。

## 项目结构

```text
agent-runtime-java/
├── service/
│   ├── agent-service-spec/          # 契约：paths / dto / spi
│   ├── agent-service-adapters/      # 聚合：common / agentcore
│   ├── agent-service-app/           # Controller + Orchestrator + Lifecycle + AutoConfig
│   └── agent-service-demo/          # 可运行示例
├── documents/zh/                    # 中文开发指南
│   └── SUMMARY.md
├── README.md
└── README.zh.md
```

## 完整文档

文档索引：[documents/zh/SUMMARY.md](documents/zh/SUMMARY.md)。

建议阅读路径：

- [开发指南总入口](documents/zh/2.开发指南/README.md)
- [快速开始](documents/zh/2.开发指南/快速开始.md)
- [逻辑架构](documents/zh/2.开发指南/逻辑架构.md)
- [架构概述](documents/zh/2.开发指南/架构概述.md)
- [HTTP 对话面](documents/zh/2.开发指南/HTTP对话面.md)
- [开发 Agent Service](documents/zh/2.开发指南/开发Agent Service.md)
- [Adapters 与 Handler](documents/zh/2.开发指南/Adapters与Handler.md)
- [生命周期与探针](documents/zh/2.开发指南/生命周期与探针.md)
- [A2A 与平台边界](documents/zh/2.开发指南/A2A与平台边界.md)
- [Service 模块说明](service/README.md)

Agent Core 文档见 [agent-core-java](https://gitcode.com/openJiuwen/agent-core-java/tree/0.1.12/documents/zh/SUMMARY.md)（独立仓库）。

## 参与贡献

我们欢迎所有形式的贡献，包括但不限于：

- 提交问题和功能建议
- 改进文档
- 提交代码
- 分享使用经验

## 开源许可证

本项目依据 Apache-2.0 许可证授权（以各子模块 `LICENSE` 为准）。
