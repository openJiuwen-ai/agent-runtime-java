# Agent Service Demo — 特性示例

面向开发者的**按需演示**。每个特性是**独立可运行的 Maven 子模块**（redis / mcp / sandbox），与主开箱 demo 共用
`example/config/` 下的基础配置：

- Agent：`ReActAgent`（由 `example/support` 工厂装配）
- Handler：`JiuwenCoreAgentHandler`
- 模型配置：主 demo 与各特性模块共用 `openjiuwen.demo.llm`（`example/config/application-base.yml`）

| 目录                           | Maven 模块                     | 默认端口     | 演示内容                              |
|------------------------------|------------------------------|----------|-----------------------------------|
| [query](query/README.md)     | `agent-service-demo`（主开箱）    | **8090** | HTTP `/v1/query`、流式 SSE、`/health` |
| [redis](redis/README.md)     | `agent-service-demo-redis`   | **8091** | Redis Checkpointer + Core Session |
| [mcp](mcp/README.md)         | `agent-service-demo-mcp`     | **8092** | 外部 MCP 出站、Tool 注册                 |
| [sandbox](sandbox/README.md) | `agent-service-demo-sandbox` | **8093** | Sandbox 外置服务                      |
| [memory](memory/README.md)   | `agent-service-demo-memory`  | **8094** | mem0 长期记忆                         |

各特性在自身 `application.yml` 里用 `server.port` 覆盖 base 的 8090，可与主 demo **同时运行**。

## 配置分层

```
example/config/application-base.yml     ← openjiuwen.demo.llm + service 默认
example/support/                        ← DemoLlmProperties、ReActAgent 工厂
example/<feature>/application.yml       ← import base + 激活特性 profile
example/<feature>/application-*.yml     ← 特性增量
```

## 模型 API 配置

默认模板：`example/config/application-base.yml`（占位符，可提交）。

**本地密钥**：复制为 `application-base_local.yml` 并填入真实 API（已加入 `.gitignore`，勿提交）。可参考
`application-base_local.example.yml`。

各模块 `application.yml` 会依次 import：

```yaml
spring:
  config:
    import:
      - optional:classpath:application-base.yml
      - optional:classpath:application-base_local.yml
```

`local` 中非空项覆盖 `base`；本地文件建议设 `auto-discover: false`，避免被 `apiconfig.json` 覆盖。

## 启动

在 `agent-runtime-java/service` 下：

```bash
# 主开箱 demo（8090，openjiuwen.demo.llm，需配置 LLM）
mvn -pl agent-service-demo -am spring-boot:run

# Redis 示例（8091）
mvn -pl agent-service-demo/example/redis -am spring-boot:run

# MCP / Sandbox / Memory：见各子目录 README
```
