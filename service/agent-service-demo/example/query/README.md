# Query API Example

演示如何通过 **HTTP Query** 调用 Agent Service（基于主工程 `DemoAgentApplication`）。

主 demo 固定走 **Core 链路**：`JiuwenCoreAgentHandler` + `ReActAgent`，启动前必须配置大模型（`openjiuwen.demo.llm`）。

## 启动服务

在 `agent-runtime-java/service` 目录：

**方式 A — 本地 yml（推荐）**

```bash
cp agent-service-demo/example/config/application-base_local.example.yml \
   agent-service-demo/example/config/application-base_local.yml
# 编辑 application-base_local.yml，填写 openjiuwen.demo.llm 下的 api-key / api-base / model-name
# 建议设置 auto-discover: false
```

**方式 B — apiconfig.json**

```bash
export OPENJIUWEN_API_CONFIG=/path/to/apiconfig.json   # Linux / Git Bash
# $env:OPENJIUWEN_API_CONFIG="C:\path\to\apiconfig.json"  # PowerShell
```

然后启动：

```bash
mvn -pl agent-service-demo -am spring-boot:run
```

默认端口 **8090**。未配置 `api-key` / `api-base` / `model-name` 时，进程会在启动阶段失败。

## 手工请求

非流式：

```bash
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"dev-c1","message":"hello","stream":false}'
```

流式 SSE（Core Runner 会输出多个 chunk，常见为 `llm_output` + `answer`）：

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

先完成 LLM 配置并启动服务，再执行：

```bash
cd agent-runtime-java/service
bash agent-service-demo/example/query/smoke-query.sh
```

可选环境变量：

- `BASE_URL`：默认 `http://localhost:8090`
- `EXPECTED_APP`：默认 `demo-agent-service`（通过 `/health` 校验是否连对服务）
- `MODE=mvc`（默认）或 `MODE=flux`（需以 WebFlux 模式启动 demo）

脚本对成功响应做**结构断言**（HTTP 200、`role=assistant`、非空 content、`conversation_id` 匹配），不依赖固定 echo
文本，因此适用于真实大模型。

WebFlux 模式启动示例：

```bash
mvn -pl agent-service-demo -am spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=reactive --openjiuwen.service.query.webflux.enabled=true --server.port=8090"

MODE=flux bash agent-service-demo/example/query/smoke-query.sh
```

同一 `conversation_id` 可验证 Core Session / Checkpointer 多轮能力。Redis 外置 checkpointer
见 [../redis/README.md](../redis/README.md)。
