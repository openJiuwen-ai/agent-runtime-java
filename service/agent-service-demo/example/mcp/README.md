# MCP External Service Example

这个目录演示 Issue #11 的外部 MCP 出站适配能力。示例包含一个本地 JSON-RPC MCP 服务，以及 demo 应用里的 `mcp` profile 配置。

## 文件说明

- `MockMcpServerExample.java`: 本地 MCP server，监听 `/mcp`，提供 `demo_echo` 工具。
- `src/main/resources/application-mcp.yml`: Agent Service demo 的 MCP profile 配置，展示 `server-path`、`client-type`、超时、重试和熔断参数。

## 运行前提

以下命令假设当前目录是 `agent-runtime-java/service`。

## 编译 demo

```bash
mvn -pl agent-service-demo -am -DskipTests clean install
mvn -pl agent-service-demo dependency:build-classpath \
  -Dmdep.outputFile=target/example.classpath

rm -rf agent-service-demo/target/example-classes
mkdir -p agent-service-demo/target/example-classes
EXAMPLE_CP="agent-service-demo/target/classes:$(cat agent-service-demo/target/example.classpath)"

javac -d agent-service-demo/target/example-classes \
  -cp "$EXAMPLE_CP" \
  agent-service-demo/example/mcp/MockMcpServerExample.java
```

## 启动本地 MCP 服务

```bash
EXAMPLE_CP="agent-service-demo/target/classes:$(cat agent-service-demo/target/example.classpath)"

java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.mcp.MockMcpServerExample \
  --port=18080
```

启动后可以直接验证 MCP 服务：

```bash
curl -s 'http://localhost:18080/mcp' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

预期响应包含 `demo_echo`。

## 启动 Agent Service demo 并挂载 MCP

另开一个终端，提供真实大模型配置后启动 demo。MCP 配置来自 `application-mcp.yml`。

```bash
OPENJIUWEN_API_CONFIG=/path/to/apiconfig.json \
DEMO_MCP_SERVER_PATH=http://localhost:18080/mcp \
mvn -pl agent-service-demo -am spring-boot:run \
  -Dspring-boot.run.profiles=mcp \
  -Dspring-boot.run.arguments="--server.port=8090 --openjiuwen.demo.llm.enabled=true"
```

启动日志中应能看到类似内容：

```text
Registered external MCP server, serverId=demo-mcp, serverName=demo-mcp-tools
```

然后通过 Query API 使用正式 Core 链路：

```bash
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"mcp-demo-c1","message":"请调用 demo_echo 工具，输入 text=hello","stream":false}'
```

是否会自动调用工具取决于当前模型和 prompt 策略；这个示例的重点是展示如何在 demo 服务里挂载外部 MCP，并让 Core Runner 在启动时注册 MCP 工具。

## 配置项

`application-mcp.yml` 中的关键参数：

```yaml
openjiuwen:
  service:
    external:
      mcp:
        timeout-ms: 30000
        retry-tool-calls: false
        retry:
          max: 1
          backoff-ms: 200
        circuit-breaker:
          enabled: true
          failure-threshold: 3
          reset-timeout-ms: 30000
        servers:
          - server-id: demo-mcp
            server-name: demo-mcp-tools
            server-path: http://localhost:18080/mcp
            client-type: streamable-http
```

线上接入时通常只需要替换 `server-path`，治理参数按服务稳定性要求调整。`tools/call` 默认不自动重试，只有确认工具调用幂等时才开启 `retry-tool-calls`。
