# A2A

本栏目说明 **进程内 A2A**（Agent Card、JSON-RPC、TaskStore、远端委派）与 **平台级 A2A 服务** 的边界，以及出站委派的透传与结果抽取规则。

## 页面映射

| 页面 | 适合什么问题 |
| --- | --- |
| [A2A 开发指导](开发指导.md) | 配置、Agent Card 与 JSON-RPC 端点、请求字段映射、Cancel、委派与 TaskStore |
| [出站委派与流式规则](出站委派与流式规则.md) | 委派远端 Agent 时 SSE 透传 vs 非 SSE、最终答案如何识别与回喂模型、中断恢复 |
| [A2A 与平台边界](平台边界.md) | 进程内 A2A vs 平台级 A2A、E2E 路径、与 Python 对照 |

## 阅读提示

- 先看 [A2A 开发指导](开发指导.md) 了解配置与调用链；委派时消息如何透传、哪条喂 LLM 看 [出站委派与流式规则](出站委派与流式规则.md)。
- 进程内 A2A 与 **平台级** 多 Agent 路由的边界见 [A2A 与平台边界](平台边界.md)。
- HTTP `/v1/query` 与 A2A JSON-RPC 共用 Orchestrator。
