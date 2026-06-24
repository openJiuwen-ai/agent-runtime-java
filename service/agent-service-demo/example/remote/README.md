# A2A Remote External Service Example

这个目录演示 Issue #11 的 A2A Remote 出站适配能力。示例重点是展示 Service adapter 如何通过 `remote.clients[]` 声明对端 A2A 服务，并创建带有超时、重试、熔断和审计逻辑的 Core `RemoteClient`。

## 文件说明

- `A2ARemoteAdapterExample.java`: 最小可运行示例，通过配置化 remote client 创建 A2A remote client。
- `MockA2ARemoteServerExample.java`: 本地 Mock A2A JSON-RPC server，用于验证 `RemoteClient.invoke(...)` 调用链路。
- `src/main/resources/application-a2a-remote.yml`: Agent Service demo 的 A2A Remote profile 配置，展示治理参数。

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
  agent-service-demo/example/remote/A2ARemoteAdapterExample.java \
  agent-service-demo/example/remote/MockA2ARemoteServerExample.java
```

## 启动本地 Mock A2A server

```bash
EXAMPLE_CP="agent-service-demo/target/classes:$(cat agent-service-demo/target/example.classpath)"

java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.remote.MockA2ARemoteServerExample \
  --port=18082
```

Mock server 会监听 `http://127.0.0.1:18082/a2a/jsonrpc`，实现 Core `A2AClient` 当前使用的最小 `SendMessage` JSON-RPC 协议。

## 运行调用示例

```bash
EXAMPLE_CP="agent-service-demo/target/classes:$(cat agent-service-demo/target/example.classpath)"

java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.remote.A2ARemoteAdapterExample \
  --client-id=demo-a2a-remote \
  --url=http://127.0.0.1:18082 \
  --timeout-ms=3000 \
  --retry-invoke=false \
  --retry-max=1 \
  --retry-backoff-ms=200 \
  --operation=invoke \
  --message="hello remote" \
  --conversation-id=demo-session
```

预期输出中的 client 类型应是 `DecoratingRemoteClient`，并且可以看到 mock server 返回的结果：

```text
Created client: com.openjiuwen.service.adapters.agentcore.external.DecoratingRemoteClient
remote result status: completed
remote result session: demo-session
remote result text: mock a2a response: hello remote
```

传 `null` timeout 时会使用 `openjiuwen.service.external.remote.timeout-ms`；调用方传入正数 timeout 时调用方优先。`invoke` 默认不自动重试，只有确认远端调用幂等时才开启 `retry-invoke`。

## 在 Agent Service demo 中启用配置

```bash
mvn -pl agent-service-demo -am spring-boot:run \
  -Dspring-boot.run.profiles=a2a-remote \
  -Dspring-boot.run.arguments="--server.port=8090 --openjiuwen.demo.llm.enabled=true"
```

在正式服务启动链路中，`JiuwenCoreAgentHandler.start()` 会调用 `ExternalSvcAdapterRegistrar.registerToRunner()`，完成 `A2A` provider 注册。后续 Core 在创建 `RemoteAgent` 或直接使用 `RemoteClientFactory.create(...)` 时，会拿到已经装饰过的 A2A remote client。

如果业务希望直接消费配置化 remote client，可以注入 `AgentCoreRemoteClientFactory`，再按 `remote.clients[].id` 创建：

```java
RemoteClient client = agentCoreRemoteClientFactory.create("demo-a2a-remote");
```

## 配置项

```yaml
openjiuwen:
  service:
    external:
      remote:
        clients:
          - id: demo-a2a-remote
            name: Demo A2A Remote
            protocol: A2A
            url: http://127.0.0.1:18082
        timeout-ms: 3000
        retry-invoke: false
        retry:
          max: 1
          backoff-ms: 200
        circuit-breaker:
          enabled: true
          failure-threshold: 3
          reset-timeout-ms: 30000
        audit:
          enabled: true
```
