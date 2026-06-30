# adapters.agentcore

`com.openjiuwen.service.adapters.agentcore` 是默认 Agent Core adapter leaf，将 Agent Service 接到 `agent-core-java`。

## 子包

| Package | Description |
| --- | --- |
| [`agentfw`](agentcore/agentfw.README.md) | `JiuwenCoreAgentHandler`，把 `AgentHandler` SPI 接到 Core `Runner`。 |
| [`autoconfigure`](agentcore/autoconfigure.README.md) | Agent Core adapter 自动装配。 |
| [`middleware`](agentcore/middleware.README.md) | Core Runner middleware 配置接线。 |
| [`external`](agentcore/external.README.md) | MCP、A2A Remote、Sandbox 外部服务接线。 |

## 典型启动链路

```text
AgentLifecycleManager.init
  -> JiuwenCoreAgentHandler.start
      -> MiddlewareAdapterRegistrar.applyToRunnerConfig
      -> ExternalSvcAdapterRegistrar.registerToRunner
      -> Runner.start
```

## 源码路径

`service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/`
