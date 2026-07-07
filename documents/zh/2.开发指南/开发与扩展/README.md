# 开发与扩展

本栏目面向 **定制 Agent Service 镜像**：依赖装配、Handler 选型、生命周期与探针。

## 页面映射

| 页面 | 适合什么问题 | 主要依据 |
| --- | --- | --- |
| [开发 Agent Service](../开发Agent Service.md) | 依赖装配、配置分层、Profile、打包 | `AgentServiceAutoConfiguration`、`agent-service-demo` |
| [Adapters 与 Handler](../Adapters与Handler.md) | Handler、中间件、外部 egress | `agent-service-adapters-*` |
| [生命周期与探针](../生命周期与探针.md) | 探针、lifecycle interrupt、A2A Cancel | `DefaultAgentLifecycleManager`、`A2AAgentExecutor` |
| [外部服务](外部服务.md) | MCP、A2A Remote、Sandbox 出站配置与验证 | `adapters/agentcore/external` |
## 阅读提示

- 先读 [HTTP 对话面](../HTTP对话面.md) 理解 Ingress 契约，再进入本栏目。
- Adapters 不仅包含 `JiuwenCoreAgentHandler`，还包含 **中间件**（Redis Checkpointer 等）与 **外部 egress**（MCP、出站 Remote/A2A、Sandbox）。
- 配置详解见 [Adapters 与 Handler](../Adapters与Handler.md) 与 [外部服务](外部服务.md)。
- 可运行样例见 [agent-service-demo/README.md](../../../../service/agent-service-demo/README.md)。
