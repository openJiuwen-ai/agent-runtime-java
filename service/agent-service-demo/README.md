# Agent Service Demo 示例

上级文档：[service/README.md](../README.md) · [开发指南](../../documents/zh/2.开发指南/README.md) · [HTTP 对话面](../../documents/zh/2.开发指南/HTTP对话面.md)

这是 Issue #12 的最小 Spring Boot 示例工程，串联 Query API（#3）、Middleware Checkpointer（#9）与 AgentCore Handler（#10）等链路。

## 启动

从 `agent-runtime-java/service` 目录启动：

```bash
mvn -pl agent-service-demo -am spring-boot:run
```

默认端口是 `8090`。

主 demo **固定**走 Core 链路，启动时会校验 LLM 配置：

```text
Query API -> ServeOrchestrator -> JiuwenCoreAgentHandler -> Runner -> ReActAgent
```

必须提供 `openjiuwen.service.llm` 的 `api-key`、`api-base`、`model-name`（通过 `application-base_local.yml` 或
`apiconfig.json`）。未配置时进程无法启动。

配置方式见 [example/query/README.md](example/query/README.md)。

## Handler 样例（Issue #10）

| 类                                                     | 用途                                              |
|-------------------------------------------------------|-------------------------------------------------|
| `DemoAgentApplication`                                | 装配 `ReActAgent` + `JiuwenCoreAgentHandler`      |
| `examples/EchoProxyAgentHandler`                      | 最小自定义 `AgentHandler` 模板（前缀回显，可复制改为 HTTP 代理等）    |
| `it/AgentCoreHandlerAutoConfigurationIntegrationTest` | AC1：仅 `agent-id`、无 `@Bean`，验证 agentcore 自动装配全链路 |

详见 [Adapters 与 Handler](../../documents/zh/2.开发指南/Adapters与Handler.md#自定义-handler-模板)。

## 特性示例（example/）

面向开发者的按需演示，索引见 [example/README.md](example/README.md)：

| 目录                        | 内容                                                           |
|---------------------------|--------------------------------------------------------------|
| `example/query`           | HTTP Query、SSE、`/health`（主模块 `agent-service-demo`）           |
| `example/redis`           | Redis Checkpointer + Core Session（8091）                       |
| `example/mcp`             | 独立 FastMCP、Agent 工具调用与治理（8092）                              |
| `example/sandbox`         | 独立 JiuwenBox、Agent 沙箱工具调用与治理（8093）                          |
| `example/memory`          | Runtime `MemoryStore`、mem0/Jiuwen Memory、生命周期与工具调用（8094） |
| `example/a2a`             | 多 Agent A2A 调用与中断恢复                                         |
| `example/security`        | TLS 与细粒度鉴权（8095）                                             |
| `example/outbound-security` | MCP/Sandbox 出站 HTTPS 与 Bearer 验证                              |

A2A Remote 出站与 Health L1 矩阵等**内部验收**代码在 `src/test/`（含 `src/test/resources/scripts/`）。
`DemoAgentApplicationTest` 在测试内注册确定性 echo 模型，仅用于 JUnit，不影响 live server。

## 接口

当前 demo 接入 Agent Service 基础端点：

- `GET /health`
- `POST /v1/query`
- `POST /query`
- `POST /v1/query/reactive`，仅在应用以 WebFlux 模式启动时使用
- `POST /v1/reset_conversation`
- `POST /reset_conversation`

`GET /health` 返回轻量进程和 Agent 就绪状态：

```json
{
  "status": "healthy",
  "app": "demo-agent-service",
  "version": "0.1.1",
  "process_up": true,
  "agent_loaded": true
}
```

其中 `version` 来自显式配置项 `openjiuwen.service.version`。

K8s 探针建议：

- liveness：HTTP 200 且 `process_up == true`
- readiness：HTTP 200 且 `agent_loaded == true`

当前 demo 不暴露 A2A 端点，因为该能力不属于当前实现范围。

## 基础示例

非流式 JSON 请求：

```bash
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1","message":"hello","stream":false}'
```

典型响应形状（`content` 由大模型生成，非固定文本）：

```json
{
  "result": {
    "role": "assistant",
    "content": "...",
    "conversation_id": "demo-c1"
  },
  "conversation_id": "demo-c1"
}
```

Servlet 栈下的流式 SSE 请求使用 `/v1/query`：

```bash
curl -N http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1","message":"stream hello","stream":true}'
```

流式输出为 Core Runner 的 SSE chunk，常见形态：

```text
data: {"type":"llm_output","index":0,"payload":{"content":"..."}}
data: {"type":"answer","index":1,"payload":{"output":"..."}}
```

## 大模型配置

demo 会从 `apiconfig.json` 读取模型配置，字段名和 agent-core 示例保持一致。可以复制 `apiconfig_example.json` 为本地
`apiconfig.json`，并填入自己的配置。`apiconfig.json` 会被 git 忽略。

`ApiConfigLoader` 按下面的顺序选择一个配置文件：

1. `openjiuwen.service.llm.config-file`
2. `OPENJIUWEN_API_CONFIG`
3. 当 `openjiuwen.service.llm.auto-discover=true` 时，从当前工作目录开始，最多向上查找 6 层
   `apiconfig.json`

`auto-discover` 只控制第 3 项；即使它是 `false`，显式路径和 `OPENJIUWEN_API_CONFIG` 仍然有效。显式路径或环境变量
指向的文件不存在、不是普通文件或不可读时，启动会直接失败，不会静默回退到下一种来源。

文件选定后，`LlmConfigResolver` 再按字段合并：

1. 非空的 `openjiuwen.service.llm.*` Spring 配置
2. `apiconfig.json` 对应字段
3. Runtime 默认值

因此，文件来源优先级与字段值优先级是两个不同概念。Spring 配置适合配置中心或环境变量注入；
`apiconfig.json` 主要补充 Spring 中未设置的模型连接字段。

`apiconfig.json` 示例：

```json
{
  "API_BASE": "https://api.example.com/v1",
  "API_KEY": "replace-with-your-api-key",
  "MODEL_PROVIDER": "OpenAI",
  "MODEL_NAME": "replace-with-your-model-name",
  "LLM_SSL_VERIFY": "true"
}
```

通过环境变量指定配置文件：

```bash
OPENJIUWEN_API_CONFIG=/path/to/apiconfig.json \
mvn -pl agent-service-demo -am spring-boot:run
```

通过 Spring 属性显式指定配置文件：

```bash
mvn -pl agent-service-demo -am spring-boot:run \
  -Dspring-boot.run.arguments="--openjiuwen.service.llm.config-file=/path/to/apiconfig.json"
```

也可以直接通过 Spring 配置模型参数，例如：

- `openjiuwen.service.llm.api-base`
- `openjiuwen.service.llm.api-key`
- `openjiuwen.service.llm.model-name`
- `openjiuwen.service.llm.provider`
- `openjiuwen.service.llm.ssl-verify`

行为说明：

- `stream=false` 返回聚合后的 assistant JSON。
- `stream=true` 输出来自 Core Runner stream 的 Query SSE chunk。

多轮上下文检查：

```bash
curl -s http://localhost:8090/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1","message":"我叫小明","stream":false}'

curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1","message":"我叫什么","stream":false}'
```

如果第二次能回答出名字，说明已经走正式 Core Session 链路。

## WebFlux 流式测试

WebFlux 端点是 `POST /v1/query/reactive`，它面向 Reactive/WebFlux 应用。Servlet 栈下的 SSE 流式请求应使用
`POST /v1/query`。

从仓库根目录运行现有 Flux 集成测试：

```bash
cd ./openjiuwen/agent-runtime-java

mvn -pl service/agent-service-app -am \
  -Dtest=QueryWebFluxIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

这个集成测试使用确定性的测试 handler，只验证 Flux SSE 传输链路，不会调用真实大模型。

手工测试 WebFlux 流式时，从 `agent-runtime-java/service` 启动 demo：

```bash
cd ./openjiuwen/agent-runtime-java/service

mvn -pl agent-service-demo -am spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=reactive --openjiuwen.service.query.webflux.enabled=true --server.port=8090"
```

然后请求 reactive 端点：

```bash
curl -N -i http://localhost:8090/v1/query/reactive \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"conversation_id":"demo-flux-1","message":"stream hello","stream":true}'
```

真实模型模式下会输出 Core Runner stream 事件（多个 chunk + `answer`）。

## Smoke Test

完成 LLM 配置并启动 demo 后执行：

```bash
bash agent-service-demo/example/query/smoke-query.sh
```

脚本覆盖：

- `GET /health`（校验 `app=demo-agent-service`）
- `POST /v1/query`，`stream=false` / `stream=true`
- 兼容路径 `POST /query`
- `messages[]` 归一化为最新 user message
- 省略 `stream` 时默认走 SSE
- 缺少 `conversation_id` 时返回固定错误 JSON

脚本对成功响应做结构断言（非空 assistant content），适用于真实大模型。测试不同 host 或端口：

```bash
BASE_URL=http://localhost:18090 bash agent-service-demo/example/query/smoke-query.sh
```

WebFlux 模式：

```bash
MODE=flux bash agent-service-demo/example/query/smoke-query.sh
```

## Example

开发者特性演示见 [example/README.md](example/README.md)。redis / mcp / sandbox 为**独立 Maven 子模块**（`ReActAgent` +
`JiuwenCoreAgentHandler`，与主 demo 共用 `example/config/application-base.yml` 中 `openjiuwen.service.llm`）；query 使用主模块
`agent-service-demo`。内部 L1 转测脚本见 `src/test/resources/scripts/`。

## 外部 MCP 示例

独立模块 `agent-service-demo-mcp`，配置见 `example/mcp/application-mcp.yml`
。完整步骤见 [example/mcp/README.md](example/mcp/README.md)。

## Redis Checkpointer 示例

独立模块 `agent-service-demo-redis`，需 **JiuwenCoreAgentHandler** + LLM 配置 +
Redis。见 [example/redis/README.md](example/redis/README.md)。

## 配置示例

共用基础配置：`example/config/application-base.yml`（import 进主 demo 与各特性模块）。主 demo 默认
`checkpointer.type=in_memory`。Redis 等特性见对应子模块的 `application-*.yml`。
