# 开发指南

本章节汇总 **openJiuwen Agent Runtime Java** 的叙事式开发指南与 API 导航，面向需要把 **Agent Core** 能力封装为 **HTTP Agent Service** 的 Java 开发者。

## 章节定位

- 教程按 **② Agent Service** 能力组织：快速上手、架构、HTTP 对话面、自定义服务、Adapters、生命周期、A2A 边界。
- 页面内容以当前仓库中的 **`service/` 模块源码**、`agent-service-demo` 与测试为准。
- **Agent Core**（模型、工作流、Runner 等）请参阅 [agent-core-java 开发指南](https://gitcode.com/openJiuwen/agent-core-java/tree/0.1.12/documents/zh/2.开发指南/README.md)（独立仓库，Maven 依赖 `0.1.12`）。

## 入口

### 教程栏目

| 栏目 | 适合什么问题 | 主要依据 |
| --- | --- | --- |
| [快速开始](快速开始.md) | 如何克隆、构建、跑 demo、发第一条 Query | `agent-service-demo`、`service/pom.xml` |
| [逻辑架构](逻辑架构.md) | 中间件、Gateway、Manager、Session、Agent Server、外部服务总图 | 目标架构 + 本仓映射 |
| [架构概述](架构概述.md) | 当前仓库模块、数据面调用链 | `agent-service-spec` / `app` / `adapters` |
| [HTTP 对话面](HTTP对话面.md) | `/query`、`/health`、`/reset_conversation` 契约 | `AgentServicePaths`、Controller 测试 |
| [开发 Agent Service](开发Agent Service.md) | 如何写 Spring Boot 镜像、覆盖 Handler | `AgentServiceAutoConfiguration`、demo |
| [Adapters 与 Handler](Adapters与Handler.md) | agentcore 与自定义 Handler | `agent-service-adapters-*` |
| [生命周期与探针](生命周期与探针.md) | init、就绪、interrupt、流式 cancel | `DefaultAgentLifecycleManager` |
| [A2A 与平台边界](A2A与平台边界.md) | 为何不做进程内 A2A、平台 A2A 怎么走 | 架构定案、Issue #4 |

### API 文档

- [API 文档入口](API文档/README.md)

## 文档说明

- Agent Runtime Java 与 Agent Core Java **分仓维护**（本仓通过 Maven 依赖 `agent-core-java`，版本由根 `pom.xml` 的 `agent-core.version` 管理）。
- Service 层文档 **不以** Python `a2a_service` 进程内逻辑作为 Agent 镜像默认能力。
- 推荐路径：先读 [快速开始](快速开始.md) 与 [架构概述](架构概述.md)，再按场景阅读 HTTP / Adapters / 生命周期栏目。

## Service 模块专篇

更细的 Maven 模块说明见仓库内 [service/README.md](../../service/README.md) 与 [service/documents/zh/SUMMARY.md](../../service/documents/zh/SUMMARY.md)。
