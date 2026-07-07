# API 文档

本章节按 **`com.openjiuwen.service`** 包结构组织 Service 层公开 API 导航。与 Agent Core（`com.openjiuwen.core`）文档分离维护。

## 说明

- 结论以 `service/agent-service-spec` 与 `service/agent-service-app` 源码及集成测试为准。
- 叙事式教程见同级 [开发指南](../README.md) 各栏目。
- Agent Core API 见 [agent-core-java · API 文档](https://gitcode.com/openJiuwen/agent-core-java/tree/feature/630/documents/zh/2.开发指南/API文档/README.md)（独立仓库）。
- SPI 与 DTO 细节见 [spec 包说明](com.openjiuwen.service/spec.README.md)；`app` / `adapters` 见开发指南各栏目。

## 模块入口

| 包 / 模块 | 说明 | 文档 |
| --- | --- | --- |
| `com.openjiuwen.service.spec` | DTO、路径常量、SPI、Lifecycle 接口 | [spec.README.md](com.openjiuwen.service/spec.README.md) |
| `com.openjiuwen.service.app` | Controller、Orchestrator、Lifecycle 实现 | 见 [架构概述](../架构概述.md)、[HTTP 对话面](../HTTP对话面.md) |
| `com.openjiuwen.service.adapters.*` | Handler、中间件、外部 egress | 见 [Adapters 与 Handler](../Adapters与Handler.md) |

## HTTP 契约

REST 路径与请求体不单独拆类型页，统一见 [HTTP 对话面](../HTTP对话面.md) 与 `AgentServicePaths`。
