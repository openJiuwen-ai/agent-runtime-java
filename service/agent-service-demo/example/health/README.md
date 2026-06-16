# Health Probe Example

这个目录提供 Issue #8 Health 探针的最小可执行示例。示例只注册一个轻量 `AgentHandler`，用于让 Agent Service 自动装配完成并暴露 `GET /health`，不依赖真实大模型，也不读取 `apiconfig.json`。

## 文件说明

- `HealthProbeExample.java`: L0 冒烟示例入口，启动 Spring Boot Agent Service，并注册一个最小 `AgentHandler`。
- `HealthL1ProbeExample.java`: L1 场景示例入口，可通过启动参数切换 app/version、handler、readiness 状态。
- `smoke-health.sh`: 覆盖 Health 探针基础转测场景的 curl 脚本。
- `l1-health.sh`: 覆盖 Health L1 场景的脚本，会自动拉起多个本地示例实例并完成断言。

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
  agent-service-demo/example/health/HealthProbeExample.java \
  agent-service-demo/example/health/HealthL1ProbeExample.java
```

## 启动示例

```bash
EXAMPLE_CP="agent-service-demo/target/classes:agent-service-app/target/classes:agent-service-adapters/target/classes:agent-service-spec/target/classes:$(cat agent-service-app/target/app.classpath):$(cat agent-service-demo/target/example.classpath)"

java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.health.HealthProbeExample
```

默认端口是 `8090`。可以通过 Spring Boot 参数覆盖端口：

```bash
EXAMPLE_CP="agent-service-demo/target/classes:agent-service-app/target/classes:agent-service-adapters/target/classes:agent-service-spec/target/classes:$(cat agent-service-app/target/app.classpath):$(cat agent-service-demo/target/example.classpath)"

java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.health.HealthProbeExample \
  --server.port=18090
```

## 手工验证

```bash
curl -s http://localhost:8090/health
```

预期响应包含以下字段：

```json
{
  "status": "healthy",
  "app": "health-probe-example",
  "version": "0.1.0",
  "process_up": true,
  "agent_loaded": true
}
```

`app` 字段可能被 classpath 中的 Spring 配置覆盖；转测时重点校验字段存在、`status=healthy`、`process_up=true`、`agent_loaded=true`。

## 运行脚本

示例启动后执行：

```bash
agent-service-demo/example/health/smoke-health.sh
```

测试不同端口时使用 `BASE_URL`：

```bash
BASE_URL=http://localhost:18090 agent-service-demo/example/health/smoke-health.sh
```

脚本覆盖：

- `GET /health` 返回 HTTP 200
- 响应为 JSON，且包含 `status`、`app`、`version`、`process_up`、`agent_loaded`
- `process_up == true`
- `agent_loaded == true`
- 错误路径 `/v1/health` 返回 404
- `POST /health` 不作为成功探针请求

## 运行 L1 脚本

L1 脚本会自动启动和停止多个 `HealthL1ProbeExample` 场景实例。执行前需要先完成上面的编译步骤。

```bash
agent-service-demo/example/health/l1-health.sh
```

默认从 `18101` 开始使用本地端口。端口被占用时可以通过 `BASE_PORT` 调整：

```bash
BASE_PORT=18200 agent-service-demo/example/health/l1-health.sh
```

L1 脚本覆盖：

- 配置 `spring.application.name` 后 `app` 正确返回
- `AgentServiceIdentity` 为空白时 `app` 回退到 `agent-service`
- 配置 `openjiuwen.service.version` 后 `version` 正确返回
- 未配置 `openjiuwen.service.version` 时返回默认版本 `0.1.0`
- `status=healthy`
- JSON 字段使用 `process_up`、`agent_loaded`，不使用 camelCase
- `Content-Type` 兼容 `application/json`
- lifecycle init 禁用时 `agent_loaded=false`
- 有可用 `AgentHandler` 时 `agent_loaded=true`
- 无已加载 handler 时 `/health` 仍返回 HTTP 200，且 `agent_loaded=false`
- handler 初始化失败且 `init-fail-fast=false` 时 `/health` 仍返回 HTTP 200，且 `agent_loaded=false`
- `/health` 不调用 `query` 或 `streamQuery`
- shutting down 状态下 `process_up=false`、`agent_loaded=false`
- process down 状态下 `process_up=false`、`agent_loaded=false`
- 连续请求 `/health` 响应稳定
- `/health?verbose=true` 不影响响应结构
- `/v1/health` 返回 404
- `POST /health`、`PUT /health`、`DELETE /health` 不作为成功探针请求
