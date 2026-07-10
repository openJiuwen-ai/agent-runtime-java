# adapters

`com.openjiuwen.service.adapters` 是 Agent Service 的适配层命名空间，用于把 `AgentHandler` 接到具体执行后端，并为 middleware / external services 提供共享治理能力。

## 子模块

| 模块 | 包 | 说明 |
| --- | --- | --- |
| `agent-service-adapters-common` | [`adapters.common`](adapters/common.README.md) | 凭据解密、外部调用治理、middleware 配置和 Redis 连接装配。 |
| `agent-service-adapters-agentcore` | [`adapters.agentcore`](adapters/agentcore.README.md) | `JiuwenCoreAgentHandler`、Agent Core middleware 配置、MCP/A2A Remote/Sandbox 外部服务适配。 |

## 依赖方向

```text
agent-service-adapters-agentcore
  -> agent-service-adapters-common
      -> agent-service-spec
```

`agent-service-app` 不硬依赖某一个 adapter leaf。业务镜像可以选择默认 `agentcore` adapter，也可以提供自己的 `@Bean AgentHandler`。

## 延伸阅读

- [Adapters 与 Handler](../../Adapters与Handler.md)
- [外部服务](../../开发与扩展/外部服务.md)
