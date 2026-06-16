# Query REST Example

这个目录提供 Issue #3 Query REST 服务的最小可执行示例。示例注册一个固定响应的 `AgentHandler`，用于验证 `/v1/query`、`/query`、`/v1/query/reactive`、非流式 JSON、流式 SSE、Flux SSE 和异常请求，不依赖真实大模型，也不读取 `apiconfig.json`。

## 文件说明

- `QueryRestExample.java`: 示例入口，启动 Spring Boot Agent Service，并注册一个固定响应的 `AgentHandler`。
- `QueryL1RestExample.java`: L1 场景入口，支持通过启动参数切换 MVC、Flux、readiness、流式延迟等测试状态，并回显请求归一化结果。
- `smoke-query.sh`: 覆盖 Query REST 基础转测场景的 curl 脚本，默认执行 MVC 模式，也支持 `MODE=flux` 执行 WebFlux/Flux 模式。
- `l1-query.sh`: 覆盖 Query REST L1 正常功能和异常功能场景的 curl 脚本，会自动启动和停止多个 `QueryL1RestExample` 进程。

## 运行前提

以下命令假设当前目录是 `agent-runtime-java/service`。

## 编译示例

```bash
mvn -pl agent-service-demo -am -DskipTests clean install
mvn -pl agent-service-app dependency:build-classpath \
  -Dmdep.outputFile=target/app.classpath
mvn -pl agent-service-demo dependency:build-classpath \
  -Dmdep.outputFile=target/example.classpath

rm -rf agent-service-demo/target/example-classes
mkdir -p agent-service-demo/target/example-classes
EXAMPLE_CP="agent-service-demo/target/classes:agent-service-app/target/classes:agent-service-adapters/target/classes:agent-service-spec/target/classes:$(cat agent-service-app/target/app.classpath):$(cat agent-service-demo/target/example.classpath)"

javac -d agent-service-demo/target/example-classes \
  -cp "$EXAMPLE_CP" \
  agent-service-demo/example/query/QueryRestExample.java \
  agent-service-demo/example/query/QueryL1RestExample.java
```

## 启动 MVC 示例

```bash
EXAMPLE_CP="agent-service-demo/target/classes:agent-service-app/target/classes:agent-service-adapters/target/classes:agent-service-spec/target/classes:$(cat agent-service-app/target/app.classpath):$(cat agent-service-demo/target/example.classpath)"

java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.query.QueryRestExample
```

默认端口是 `8090`。可以通过 Spring Boot 参数覆盖端口：

```bash
EXAMPLE_CP="agent-service-demo/target/classes:agent-service-app/target/classes:agent-service-adapters/target/classes:agent-service-spec/target/classes:$(cat agent-service-app/target/app.classpath):$(cat agent-service-demo/target/example.classpath)"

java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.query.QueryRestExample \
  --server.port=18090
```

## 启动 Flux 示例

Flux 用例需要启用 WebFlux 入口，并打开 query WebFlux controller：

```bash
EXAMPLE_CP="agent-service-demo/target/classes:agent-service-app/target/classes:agent-service-adapters/target/classes:agent-service-spec/target/classes:$(cat agent-service-app/target/app.classpath):$(cat agent-service-demo/target/example.classpath)"

java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.query.QueryRestExample \
  --server.port=18091 \
  --spring.main.web-application-type=reactive \
  --openjiuwen.service.query.webflux.enabled=true
```

## 手工验证

非流式 JSON：

```bash
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"query-c1","message":"hello","stream":false}'
```

预期响应：

```json
{
  "result": {
    "role": "assistant",
    "content": "query-example:hello",
    "conversation_id": "query-c1"
  },
  "conversation_id": "query-c1"
}
```

流式 SSE：

```bash
curl -N http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"query-c2","message":"stream hello","stream":true}'
```

预期响应：

```text
data: {"role":"assistant","content":"query-example:stream hello","conversation_id":"query-c2"}
```

Flux SSE：

```bash
curl -N http://localhost:18091/v1/query/reactive \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"conversation_id":"query-flux-1","message":"flux hello","stream":true}'
```

预期响应：

```text
data: {"role":"assistant","content":"query-example:flux hello","conversation_id":"query-flux-1"}
```

Flux 非流式 JSON：

```bash
curl -s http://localhost:18091/v1/query/reactive \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"conversation_id":"query-flux-2","message":"flux json","stream":false}'
```

兼容路径：

```bash
curl -s http://localhost:8090/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"query-c3","message":"legacy","stream":false}'
```

`messages[]` 输入：

```bash
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"query-c4","messages":[{"role":"user","content":"first"},{"role":"assistant","content":"ignored"},{"role":"user","content":"latest"}],"stream":false}'
```

异常请求：

```bash
curl -s -i http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"message":"missing id","stream":false}'
```

## 运行脚本

MVC 示例启动后执行：

```bash
MODE=mvc agent-service-demo/example/query/smoke-query.sh
```

测试不同端口时使用 `BASE_URL`：

```bash
MODE=mvc BASE_URL=http://localhost:18090 agent-service-demo/example/query/smoke-query.sh
```

Flux 示例启动后执行：

```bash
MODE=flux BASE_URL=http://localhost:18091 agent-service-demo/example/query/smoke-query.sh
```

L1 脚本会自动启动不同配置的示例服务，不需要提前手工启动：

```bash
agent-service-demo/example/query/l1-query.sh
```

如默认端口段被占用，可以指定 `BASE_PORT`：

```bash
BASE_PORT=19000 agent-service-demo/example/query/l1-query.sh
```

脚本覆盖：

- `POST /v1/query`，`stream=false`
- `POST /v1/query`，`stream=true`
- 省略 `stream` 时默认 SSE
- 兼容路径 `POST /query`
- `messages[]` 归一化为最新 user message
- 中文输入
- 未知字段忽略
- 缺少 `conversation_id` 返回 400
- 空 `conversation_id` 返回 400
- `GET /v1/query` 返回 405
- 错误路径返回 404
- Flux 模式 `POST /v1/query/reactive`，`stream=true`
- Flux 模式 `POST /v1/query/reactive`，`stream=false`
- Flux 模式缺少 `conversation_id` 返回 400

L1 脚本额外覆盖：

- `message` 简写、`messages` 优先级、最新 user message、无 user 角色兜底和空消息归一化
- 默认 user/space/tenant、请求头覆盖 body、空请求头不覆盖 body
- 非流式 JSON 响应 header、MVC SSE header 和 payload
- 兼容路径 `/query` 默认可用，以及 `legacy-path-enabled=false` 后返回 404
- WebFlux 路径默认关闭、开启后与 MVC/legacy 路径共存
- Flux 非流式 JSON、Flux SSE、Flux 默认 stream、Flux 中文输入和 Flux 错误请求
- MVC/Flux `agent_loaded=false` 时返回 503
- 无 `ServeOrchestrator` 时返回 `no agent handler configured`
- GET、错误路径、非 JSON body、非法 JSON 等异常场景
- 流式请求客户端超时中断后，后续请求仍可正常处理
