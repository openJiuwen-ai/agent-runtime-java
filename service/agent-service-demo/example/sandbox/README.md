# Sandbox Adapter Example

这个目录展示 Service external adapters 如何通过配置创建 Core `SandboxClient`。

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

## 沙箱 HTTP 协议

Service adapter 会把 Core Java 的 `SandboxClient.fs()/shell()/code()` 调用转成 HTTP：

```http
POST {service-url}/invoke
Content-Type: application/json
```

请求体示例：

```json
{
  "opType": "fs",
  "method": "readFile",
  "params": {
    "path": "/tmp/demo.txt",
    "mode": "text",
    "encoding": "UTF-8"
  },
  "isolationKey": "session-1",
  "sandboxId": "session-1"
}
```

响应体使用 Core Java 的 result 结构：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "path": "/tmp/demo.txt",
    "content": "hello",
    "mode": "text"
  }
}
```

如果沙箱服务不是 `/invoke`，可以用 `--invoke-path=/your/path` 覆盖。

## 运行示例

从 `agent-runtime-java/service` 目录生成 classpath 并编译示例：

```bash
mvn -pl agent-service-demo dependency:build-classpath \
  -Dmdep.outputFile=target/example.classpath

mkdir -p agent-service-demo/target/example-classes

EXAMPLE_CP="agent-service-demo/target/classes:$(cat agent-service-demo/target/example.classpath)"

javac -d agent-service-demo/target/example-classes \
  -cp "$EXAMPLE_CP" \
  agent-service-demo/example/sandbox/MockSandboxServerExample.java \
  agent-service-demo/example/sandbox/SandboxAdapterExample.java
```

启动一个最小本地 mock 沙箱服务：

```bash
java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.sandbox.MockSandboxServerExample \
  --port=18090
```

这个 mock 服务默认不会执行真实 shell/code，也不提供进程隔离；它只返回符合 Core Java result 结构的响应，用来验证 Service sandbox adapter 的 HTTP 出站链路。

如果希望 `read-file` 从本地目录读取文件，可以指定根目录：

```bash
mkdir -p /tmp/openjiuwen-sandbox/tmp
echo "hello sandbox" > /tmp/openjiuwen-sandbox/tmp/demo.txt

java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.sandbox.MockSandboxServerExample \
  --port=18090 \
  --root-dir=/tmp/openjiuwen-sandbox
```

另开一个终端运行 adapter 示例。默认只展示配置、校验和 client 创建：

```bash

java -cp "agent-service-demo/target/example-classes:$EXAMPLE_CP" \
  com.openjiuwen.service.demo.example.sandbox.SandboxAdapterExample \
  --url=http://localhost:18090
```

传入 `--operation` 后会向本地 mock 沙箱服务发起真实 HTTP 调用：

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
