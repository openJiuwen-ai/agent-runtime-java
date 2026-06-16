# Agent Service Demo 示例

这是 Issue #12 的最小 Spring Boot 示例工程，目前串联的是 Issue #3 Query API 链路。

## 启动

从 `agent-runtime-java/service` 目录启动：

```bash
mvn -pl agent-service-demo -am spring-boot:run
```

默认端口是 `8090`。

默认情况下，demo 使用本地 mock handler，返回稳定的 `demo:<message>` 内容。如果能读取到大模型配置，demo 会切到正式 Core 链路：

```text
Query API -> ServeOrchestrator -> CoreAgentHandler -> Runner -> LlmAgent
```

在正式 Core 链路下，同一个 `conversation_id` 会走 Core 的 Session/Context 机制，支持多轮上下文。即使本地存在大模型配置，也可以通过 `openjiuwen.demo.llm.enabled=false` 强制使用 mock handler。

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
  "version": "0.1.0",
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

mock handler 下的预期响应形状：

```json
{
  "result": {
    "role": "assistant",
    "content": "demo:hello",
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

mock handler 下的预期响应：

```text
data: {"role":"assistant","content":"demo:stream hello","conversation_id":"demo-c1"}
```

## 真实大模型模式

demo 会从 `apiconfig.json` 读取模型配置，字段名和 agent-core 示例保持一致。可以复制 `apiconfig_example.json` 为本地 `apiconfig.json`，并填入自己的配置。`apiconfig.json` 会被 git 忽略。

配置文件查找顺序：

1. `openjiuwen.demo.llm.config-file`
2. `OPENJIUWEN_API_CONFIG`
3. 从当前工作目录向上查找 `apiconfig.json`

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

显式指定配置文件启动：

```bash
OPENJIUWEN_API_CONFIG=/path/to/apiconfig.json \
mvn -pl agent-service-demo -am spring-boot:run
```

也可以通过 Spring 配置覆盖模型参数，例如：

- `openjiuwen.demo.llm.api-base`
- `openjiuwen.demo.llm.api-key`
- `openjiuwen.demo.llm.model-name`
- `openjiuwen.demo.llm.provider`

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

WebFlux 端点是 `POST /v1/query/reactive`，它面向 Reactive/WebFlux 应用。Servlet 栈下的 SSE 流式请求应使用 `POST /v1/query`。

从仓库根目录运行现有 Flux 集成测试：

```bash
cd ./openjiuwen/agent-runtime-java

mvn -pl service/agent-service-app -am \
  -Dtest=QueryWebFluxIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

这个集成测试使用确定性的测试 handler，只验证 Flux SSE 传输链路，不会调用真实大模型。

手工测试 WebFlux mock 流式时，从 `agent-runtime-java/service` 启动 demo：

```bash
cd ./openjiuwen/agent-runtime-java/service

mvn -pl agent-service-demo -am spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=reactive --openjiuwen.service.query.webflux.enabled=true --server.port=8090 --openjiuwen.demo.llm.enabled=false"
```

然后请求 reactive 端点：

```bash
curl -N -i http://localhost:8090/v1/query/reactive \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"conversation_id":"demo-flux-1","message":"stream hello","stream":true}'
```

mock handler 下的预期输出：

```text
HTTP/1.1 200
Content-Type: text/event-stream;charset=UTF-8

data: {"role":"assistant","content":"demo:stream hello","conversation_id":"demo-flux-1"}
```

如果要用 WebFlux 流式测试真实大模型，需要提供 `apiconfig.json` 并开启 demo LLM handler：

```bash
cd ./openjiuwen/agent-runtime-java/service

OPENJIUWEN_API_CONFIG=./openjiuwen/agent-runtime-java/apiconfig.json \
mvn -pl agent-service-demo -am spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=reactive --openjiuwen.service.query.webflux.enabled=true --openjiuwen.demo.llm.enabled=true --server.port=8090"
```

然后请求：

```bash
curl -N -i http://localhost:8090/v1/query/reactive \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"conversation_id":"demo-flux-llm","message":"我叫小明","stream":true}'
```

真实模型模式会输出 Core Runner stream 事件，常见形态是多个 `llm_output` chunk，最后跟一个 `answer` 事件。如果响应仍是 `demo:<message>`，说明当前仍在使用 mock handler。

## Smoke Test

demo 启动后执行：

```bash
agent-service-demo/scripts/smoke-query.sh
```

脚本覆盖：

- `POST /v1/query`，`stream=false`
- `POST /v1/query`，`stream=true`
- 兼容路径 `POST /query`
- `messages[]` 归一化为最新 user message
- 省略 `stream` 时默认走 SSE
- 缺少 `conversation_id` 时返回固定错误 JSON

这个脚本期望使用默认 mock handler。如果本地存在 `apiconfig.json`，运行脚本前请用 `OPENJIUWEN_DEMO_LLM_ENABLED=false` 启动 demo。真实大模型模式下响应内容不可预测，建议用 curl 手工检查，或写 provider-specific 测试。

示例：

```bash
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1","message":"hello","stream":false}'
```

测试不同 host 或端口时可以使用 `BASE_URL`：

```bash
BASE_URL=http://localhost:18090 agent-service-demo/scripts/smoke-query.sh
```

## Example

`example` 目录提供面向转测的最小可执行样例：

- `example/health`: Issue #8 Health 探针示例，包含最小启动类、L1 场景启动类、运行说明、smoke 脚本和 L1 脚本。
- `example/query`: Issue #3 Query REST 示例，包含最小 `AgentHandler`、L1 场景启动类、运行说明、smoke 脚本和 L1 脚本。

这些 example 只用于验证 REST 服务接入，不依赖真实大模型，也不包含正式测试设计文档。

## 配置示例

```yaml
server:
  port: 8090

openjiuwen:
  demo:
    llm:
      auto-discover: true
  service:
    enabled: true
    query:
      enabled: true
      mvc:
        enabled: true
      webflux:
        enabled: true
      legacy-path-enabled: true
```
