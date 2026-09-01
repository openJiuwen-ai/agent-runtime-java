# 开发指南

本章节汇总 **openJiuwen Agent Runtime Java**（**Java Distributed Runtime 仓库**）的开发指南与 API 导航。日常开发多落在 **`service/`（Agent Server）**；Manager 等模块将随版本进入 **同一仓库**。

## 推荐阅读路径

| 步骤 | 文档 | 目的 |
| --- | --- | --- |
| 1 | [快速开始](快速开始.md) | 构建、跑 demo、第一条 Query |
| 2 | [AaaS](AaaS.md) | Agent Service 概念边界与模块组成（可选，建立全貌） |
| 3 | [架构概述](架构概述.md) | 本仓已交付模块（`service/` 等） |
| 4 | [HTTP 对话面](HTTP对话面.md) → [对话接口输入与输出](对话接口输入与输出.md) | 端点、REST/A2A 报文与中断恢复契约 |
| 5 | [开发 Agent Service](开发Agent Service.md) → [Adapters 与 Handler](Adapters与Handler.md) → [生命周期与探针](生命周期与探针.md) | 定制业务镜像 |
| 6 | [外部服务](开发与扩展/外部服务.md) | MCP、A2A Remote、Sandbox 出站（需要时） |
| 7 | [A2A 与平台边界](A2A/平台边界.md) → [A2A 开发指导](A2A/开发指导.md) | 需要 A2A 时 |
| 8 | [逻辑架构](逻辑架构.md) | 全局图景（Gateway、Manager、外部服务映射） |
| 9 | [API 文档](API文档/README.md) → [spec](API文档/com.openjiuwen.service/spec.README.md) | 下钻 SPI 与 DTO |

**Agent Core**（模型、工作流、Runner、Session 等）请参阅 [agent-core-java](https://gitcode.com/openJiuwen/agent-core-java) 仓内 `documents/zh/`（版本对齐见 [Agent Core 依赖](Agent Core 依赖.md)）。

## 章节定位

- 教程按 **Runtime 仓内模块** 组织：`service/`（Agent Server）为主，Manager 等规划中。
- 页面内容以当前仓库 **`service/` 模块源码**、`agent-service-demo` 与测试为准。
- 本仓 **已交付进程内 A2A**（Agent Card、JSON-RPC）；**平台级**多 Agent 路由与独立 `a2a_service` 进程仍属规划，见 [A2A 与平台边界](A2A/平台边界.md)。

## 栏目入口

| 栏目 | 导读 | 核心页面 |
| --- | --- | --- |
| 入门 | — | [快速开始](快速开始.md)、[Agent Core 依赖](Agent Core 依赖.md)、[AaaS](AaaS.md) |
| [架构](架构/README.md) | 本仓模块 + 目标全景 | [架构概述](架构概述.md)、[逻辑架构](逻辑架构.md) |
| HTTP 数据面 | Ingress 契约 | [HTTP 对话面](HTTP对话面.md)、[对话接口输入与输出](对话接口输入与输出.md) |
| [开发与扩展](开发与扩展/README.md) | 镜像、Handler、生命周期、外部 egress | [开发 Agent Service](开发Agent Service.md)、[Adapters 与 Handler](Adapters与Handler.md)、[生命周期与探针](生命周期与探针.md)、[外部服务](开发与扩展/外部服务.md) |
| [A2A](A2A/README.md) | 进程内 vs 平台 | [A2A 与平台边界](A2A/平台边界.md)、[A2A 开发指导](A2A/开发指导.md) |
| API | SPI 与 DTO | [API 文档](API文档/README.md) |

## 文档说明

- Agent Runtime Java 与 Agent Core Java **分仓维护**（依赖版本见 [Agent Core 依赖](Agent Core 依赖.md)）。
- 文档索引：[documents/zh/SUMMARY.md](../SUMMARY.md)。

## Service 模块专篇

更细的 Maven 模块说明见仓库内 [service/README.md](../../../service/README.md)。
