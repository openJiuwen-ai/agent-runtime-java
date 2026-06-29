# Sandbox Adapter Example

这个目录展示 Service external adapters 如何通过配置创建 Core `SandboxClient`。

runtime 不再实现独立的 sandbox HTTP provider。sandbox 后端协议由 agent-core-java 的 provider 负责，
标准后端入口是 `/api/v1/sandboxes`。runtime 这一层只负责读取外部服务配置、创建 core client，
并统一套用 timeout、retry、circuit breaker、audit 等外部调用策略。

## 配置

可以使用 `application-sandbox.yml`：

```bash
OPENJIUWEN_SANDBOX_SERVICE_URL=http://localhost:18090 \
mvn -pl agent-service-demo -am spring-boot:run \
  -Dspring-boot.run.profiles=sandbox
```

关键配置：

```yaml
openjiuwen:
  service:
    external:
      sandbox:
        enabled: true
        timeout-ms: 30000
        retry:
          max: 1
          backoff-ms: 200
        circuit-breaker:
          enabled: true
          failure-threshold: 3
          reset-timeout-ms: 30000
        audit:
          enabled: true
        servers:
          - server-id: default
            service-url: http://localhost:18090
            sandbox-type: jiuwenbox
            launcher-type: pre_deploy
            on-stop: delete
            root-path: .
```

`enabled=true` 时，`service-url` 必须是合法的 `http` 或 `https` URL，否则应用启动会失败。
该地址应指向兼容 core `jiuwenbox` provider 的 sandbox 服务，例如服务基地址为
`http://localhost:18090` 时，core provider 会使用该服务下的 `/api/v1/sandboxes` 标准接口。

## 运行示例

从 `agent-runtime-java/service` 目录生成 classpath 并编译示例：

```bash
mvn -pl agent-service-demo dependency:build-classpath \
  -Dmdep.outputFile=target/example.classpath

mkdir -p agent-service-demo/target/example-classes

EXAMPLE_CP="agent-service-demo/target/classes:$(cat agent-service-demo/target/example.classpath)"

javac -d agent-service-demo/target/example-classes \
  -cp "$EXAMPLE_CP" \
  agent-service-demo/example/sandbox/SandboxAdapterExample.java
```

运行 adapter 示例。默认只展示配置、校验和 client 创建：

```bash
java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.sandbox.SandboxAdapterExample \
  --url=http://localhost:18090
```

传入 `--operation` 后会通过 core `SandboxClient` 调用配置的 sandbox 服务：

```bash
java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.sandbox.SandboxAdapterExample \
  --url=http://localhost:18090 \
  --operation=read-file \
  --path=/tmp/demo.txt

java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.sandbox.SandboxAdapterExample \
  --url=http://localhost:18090 \
  --operation=shell \
  --command="echo sandbox"

java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.sandbox.SandboxAdapterExample \
  --url=http://localhost:18090 \
  --operation=code \
  --language=python \
  --code="print('sandbox')"
```
