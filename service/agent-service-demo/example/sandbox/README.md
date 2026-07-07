# Sandbox Examples

独立工程：`agent-service-demo-sandbox`（`example/sandbox/`）。**Agent Service 默认端口 8093**，外置 Sandbox 服务默认端口示例为 `18090`。

runtime 不再实现独立的 sandbox HTTP provider。sandbox 后端协议由 agent-core-java 的 provider 负责，标准后端入口是 `/api/v1/sandboxes`。runtime 这一层负责读取外部服务配置、创建 core client，并统一套用 timeout、retry、circuit breaker、audit 等外部调用策略。

## 两个示例的区别

| 示例 | 文件 | 是否经过 Agent | 用途 |
|------|------|---------------|------|
| Agent 调沙箱完整服务 | `src/main/java/com/openjiuwen/service/demo/example/sandbox/SandboxDemoApplication.java` | 是 | 启动 Agent Service，LLM 可选择 `readFile` / `executeCmd` / `executeCode` 工具，工具背后调用装饰后的 `SandboxClient` |
| Adapter 直连示例 | `SandboxAdapterExample.java` | 否 | 直接创建 core `SandboxClient` 并调用沙箱服务，用来验证 sandbox 配置、client 创建和基础操作 |

一般手工联调“大模型通过 agent 调用沙箱，并经过 runtime 装饰器”时，使用 `SandboxDemoApplication.java`。只想验证 sandbox adapter/client 是否能连通后端时，使用 `SandboxAdapterExample.java`。

## SandboxDemoApplication：Agent 调装饰后 Sandbox

`SandboxDemoApplication` 会创建一个 `ReActAgent`，然后在存在 `AgentCoreSandboxClientFactory` bean 时执行：

```java
DecoratedSandboxToolRegistrar.register(agent, factory)
```

这个注册过程会：

1. 通过 `factory.create()` 创建 core `SandboxClient`。
2. `DefaultAgentCoreSandboxClientFactory` 返回的是 `DecoratingSandboxClient`。
3. 把 `readFile`、`executeCmd`、`executeCode` 包成 `LocalFunction` / `ToolCard` 注册到 `ReActAgent`。
4. LLM 在 ReAct 流程中选择工具后，工具执行会进入 `DecoratingSandboxClient.fs()` / `shell()` / `code()`，再调用真实 sandbox 服务。

调用链：

```text
POST /v1/query
  -> SandboxDemoApplication 的 ReActAgent
  -> LLM 选择 readFile / executeCmd / executeCode
  -> ReActAgent 执行 LocalFunction
  -> DecoratingSandboxClient.fs()/shell()/code()
  -> agent-core-java jiuwenbox provider
  -> 外置 sandbox 服务 /api/v1/sandboxes
```

### 前置条件

1. 已启动兼容 core `jiuwenbox` provider 的 sandbox 服务。
2. 服务基地址可访问，例如 `http://localhost:18090`。
3. Sandbox 服务支持 `/api/v1/sandboxes` 标准接口。
4. 已准备可用的 LLM API 配置。

### 启动 Agent Service

从 `agent-runtime-java/service` 目录启动：

```bash
OPENJIUWEN_DEMO_LLM_API_KEY=xxx \
OPENJIUWEN_DEMO_LLM_API_BASE=https://your-llm-endpoint.example.com \
OPENJIUWEN_DEMO_LLM_MODEL_NAME=your-model-name \
OPENJIUWEN_SANDBOX_SERVICE_URL=http://localhost:18090 \
mvn -pl agent-service-demo/example/sandbox -am spring-boot:run
```

启动后服务地址：

```text
http://localhost:8093
```

如果使用非默认 sandbox 参数，可继续通过环境变量覆盖：

```bash
OPENJIUWEN_SANDBOX_ENABLED=true
OPENJIUWEN_SANDBOX_SERVER_ID=default
OPENJIUWEN_SANDBOX_TYPE=jiuwenbox
OPENJIUWEN_SANDBOX_LAUNCHER_TYPE=pre_deploy
OPENJIUWEN_SANDBOX_ON_STOP=delete
OPENJIUWEN_SANDBOX_ROOT_PATH=.
OPENJIUWEN_SANDBOX_TIMEOUT_MS=30000
OPENJIUWEN_SANDBOX_RETRY_MAX=1
OPENJIUWEN_SANDBOX_RETRY_BACKOFF_MS=200
OPENJIUWEN_SANDBOX_CIRCUIT_BREAKER_ENABLED=true
```

对应配置来自 `application-sandbox.yml`：

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

### 调用示例

非流式请求：

```bash
curl -s http://localhost:8093/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "sandbox-demo-c1",
    "message": "请读取沙箱里的 /tmp/demo.txt，并返回文件内容。",
    "stream": false
  }'
```

执行命令：

```bash
curl -s http://localhost:8093/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "sandbox-demo-c2",
    "message": "请在沙箱中执行命令：echo sandbox，并返回 stdout。",
    "stream": false
  }'
```

执行代码：

```bash
curl -s http://localhost:8093/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "sandbox-demo-c3",
    "message": "请在沙箱中用 python 执行 print(\"sandbox\")，并返回输出。",
    "stream": false
  }'
```

如果 LLM 没有主动选择工具，可以把提示词写得更明确，例如“请调用 readFile 工具读取 `/tmp/demo.txt`”。实际是否调用工具由模型根据 tool schema 和提示词决定。

### 验证

运行 sandbox agent 冒烟测试：

```bash
mvn -pl agent-service-demo/example/sandbox -am test \
  -Dgroups=smoke \
  -Dsurefire.failIfNoSpecifiedTests=false
```

这个测试会启动 mock LLM 和 mock jiuwenbox-compatible sandbox server，验证：

```text
LLM tool_call(readFile)
  -> ReActAgent 工具执行
  -> DecoratingSandboxClient
  -> mock sandbox server
  -> 返回文件内容
```

## SandboxAdapterExample：直接调用 SandboxClient

`SandboxAdapterExample.java` 不启动 Agent Service，也不经过 LLM。它只展示如何通过 Service external adapter 配置创建 core `SandboxClient`，并直接调用配置的 sandbox 服务。

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

默认只展示配置、校验和 client 创建：

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
