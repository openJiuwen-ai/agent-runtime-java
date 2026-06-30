# app

`com.openjiuwen.service.app` 是 `agent-service-app` 模块的根包，提供 Spring Boot 形态的 Agent Service 数据面实现。

## 子包

| Package | Description |
| --- | --- |
| [`autoconfigure`](app/autoconfigure.README.md) | Spring Boot 自动装配，注册生命周期、Controller、A2A SDK 组件和默认 Orchestrator。 |
| [`config`](app/config.README.md) | `openjiuwen.service.*` 配置绑定对象和默认服务身份。 |
| [`controller`](app/controller.README.md) | HTTP Query、reset、health、A2A AgentCard / JSON-RPC 控制器。 |
| [`lifecycle`](app/lifecycle.README.md) | Init / Shutdown / Readiness / active stream interrupt 管理。 |
| [`orchestrator`](app/orchestrator.README.md) | `ServeOrchestrator` 默认实现和 A2A 中断增强实现。 |

## 主要职责

```text
Controller
  -> ServeOrchestrator
      -> AgentHandler
          -> Agent Core Runner / 自定义后端
```

`app` 包只依赖 `spec` 契约，不固定某一个 adapter leaf；业务镜像通过依赖和 Spring Bean 选择具体 `AgentHandler`。

## 关键类型

| Type | Description |
| --- | --- |
| [`AgentServiceAutoConfiguration`](app/autoconfigure.README.md#agentserviceautoconfiguration) | 注册 Service 基础 Bean、生命周期和 Controller component scan。 |
| [`A2AAutoConfiguration`](app/autoconfigure.README.md#a2aautoconfiguration) | 注册 A2A AgentCard、JSON-RPC、TaskStore、远端 Agent 发现和增强 Orchestrator。 |
| [`ServiceProperties`](app/config.README.md#serviceproperties) | `openjiuwen.service` 根配置。 |
| [`QueryProperties`](app/config.README.md#queryproperties) | Query MVC / WebFlux / legacy path 配置。 |
| [`A2AProperties`](app/config.README.md#a2aproperties) | AgentCard、A2A 路径、skills、remote-agents 配置。 |
| [`DefaultServeOrchestrator`](app/orchestrator.README.md#defaultserveorchestrator) | 默认 Query 编排实现。 |
| [`A2AEnabledServeOrchestrator`](app/orchestrator.README.md#a2aenabledserveorchestrator) | 支持 A2A delegate 和 shadow task resume 的增强编排器。 |

## 源码路径

`service/agent-service-app/src/main/java/com/openjiuwen/service/app/`
