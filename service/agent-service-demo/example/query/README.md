# Query API Example

演示如何通过 **HTTP Query** 调用 Agent Service（基于主工程 `DemoAgentApplication`）。

## 启动服务

在 `agent-runtime-java/service` 目录：

```bash
mvn -pl agent-service-demo -am spring-boot:run \
  -Dspring-boot.run.arguments="--openjiuwen.demo.llm.enabled=false --server.port=8090"
```

默认使用 mock Handler，响应内容为 `demo:<你的消息>`，无需大模型配置。

## 手工请求

非流式：

```bash
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"dev-c1","message":"hello","stream":false}'
```

流式 SSE：

```bash
curl -N http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"dev-c1","message":"hello","stream":true}'
```

健康检查：

```bash
curl -s http://localhost:8090/health
```

## Smoke 脚本

先启动服务，再执行：

```bash
agent-service-demo/example/query/smoke-query.sh
```

可选环境变量：

- `BASE_URL`：默认 `http://localhost:8090`
- `MODE=mvc`（默认）或 `MODE=flux`（需以 WebFlux 模式启动 demo）

## 使用真实大模型

提供 `apiconfig.json` 后去掉 mock 限制：

```bash
OPENJIUWEN_API_CONFIG=/path/to/apiconfig.json \
mvn -pl agent-service-demo -am spring-boot:run
```

同一 `conversation_id` 可验证 Core Session / Checkpointer 多轮能力。Redis 外置 checkpointer 见 [../redis/README.md](../redis/README.md)。
