# com.openjiuwen.service

`com.openjiuwen.service` 是 Agent Runtime Java 的 Service 层 API 根命名空间，用于把 Agent Core 或其他执行后端封装成可部署的 Agent Service。

## 模块

| 模块 | Maven artifact | 说明 |
| --- | --- | --- |
| [`spec`](./com.openjiuwen.service/spec.README.md) | `agent-service-spec` | 纯 Java 契约层，包含 DTO、路径常量、SPI 和 Lifecycle 接口。 |
| [`app`](./com.openjiuwen.service/app.README.md) | `agent-service-app` | Spring Boot Agent Service 实现，包含 Controller、Orchestrator、Lifecycle 和 A2A 自动装配。 |
| [`adapters`](./com.openjiuwen.service/adapters.README.md) | `agent-service-adapters-*` | 执行后端和外部服务适配层，包含通用策略、middleware、Agent Core Handler 和 external adapters。 |

## 推荐阅读顺序

1. 先读 [`spec`](./com.openjiuwen.service/spec.README.md)，理解 Service 层的稳定契约。
2. 再读 [`app`](./com.openjiuwen.service/app.README.md)，理解 HTTP / A2A Ingress、生命周期和默认编排。
3. 最后读 [`adapters`](./com.openjiuwen.service/adapters.README.md)，选择默认 Agent Core Handler 或自定义 Handler / 外部服务接入方式。

## 与教程的关系

- 概念和上手路径见 [AaaS](../AaaS.md)。
- HTTP 字段和调用示例见 [HTTP 对话面](../HTTP对话面.md)。
- Handler 选型见 [Adapters 与 Handler](../Adapters与Handler.md)。
- MCP、A2A Remote、Sandbox 出站能力见 [外部服务](../开发与扩展/外部服务.md)。

## 源码路径

```text
service/
├── agent-service-spec
├── agent-service-app
└── agent-service-adapters
```
