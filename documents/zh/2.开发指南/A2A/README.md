# A2A

本栏目说明 **进程内 A2A**（Agent Card、JSON-RPC、TaskStore、远端委派）与 **平台级 A2A 服务** 的边界。

## 页面映射

| 页面 | 适合什么问题 | 主要依据 |
| --- | --- | --- |
| [A2A 与平台边界](../A2A与平台边界.md) | 进程内 A2A vs 平台 A2A、E2E 路径、与 Python 对照 | 架构定案、`agent-service-app.controller.a2a` |
| [A2A 开发指导](../A2A开发指导.md) | 配置、JSON-RPC、中断-恢复、Cancel、远端 Client、TaskStore | `A2AEnabledServeOrchestrator`、`A2AAgentExecutor` |

## 阅读提示

- 进程内 A2A 与 **平台级** 多 Agent 路由的边界见 [A2A 与平台边界](A2A与平台边界.md)。
- HTTP `/v1/query` 与 A2A JSON-RPC 共用 Orchestrator，详见 [A2A 开发指导](A2A开发指导.md)。
