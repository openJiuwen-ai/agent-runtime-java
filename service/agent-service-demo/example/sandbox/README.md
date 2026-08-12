# Sandbox Demo

`agent-service-demo-sandbox` 演示用户请求经过大模型后，由 Agent 调用真实 JiuwenBox 中的
`executeCmd`、`readFile` 和 `executeCode` 工具。

runtime 不再实现独立的 sandbox HTTP provider。sandbox 后端协议由 agent-core-java 的 provider 负责，标准后端入口是 `/api/v1/sandboxes`。runtime 这一层负责读取外部服务配置、创建 core client，并统一套用 timeout、retry、circuit breaker、audit 等外部调用策略。

HTTPS + Bearer 出站安全配置见 [`application-sandbox-outbound-security.example.yml`](application-sandbox-outbound-security.example.yml)；
可运行 E2E（无需 LLM / Spring Boot）见 [`../outbound-security/README.md`](../outbound-security/README.md)。

## 实现组成

当前模块只保留完整 Agent 链路：

- `SandboxDemoApplication`：启动 Agent Service，创建 `ReActAgent`；
- `DecoratedSandboxToolRegistrar`：把 `readFile`、`executeCmd`、`executeCode` 注册为 Agent 工具；
- `AgentCoreSandboxClientFactory`：根据 Runtime 配置创建 Core `SandboxClient`；
- `DecoratingSandboxClient`：统一执行 timeout、retry、circuit breaker 和 audit；
- `SandboxAgentExternalEndToEndTest`：使用确定性 mock LLM，对真实 JiuwenBox 验证完整链路。

Runtime 仓库不再提供 Adapter 直连示例或本地 mock Sandbox 后端。只验证 client 连通性时，也应通过
`AgentCoreSandboxClientFactory` 或完整 E2E，避免维护第二套协议示例。

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
→ ReActAgent
→ DecoratingSandboxClient
→ agent-core-java JiuwenBox provider
→ 独立 JiuwenBox 服务
→ 工具结果回填 Agent
```

本模块不实现 Sandbox 后端，也不会在 Java Demo 所在宿主机执行模型生成的命令。

## 1. 启动 JiuwenBox

JiuwenBox 原生运行需要 Linux、Python 3.11+ 和 `bubblewrap`。先按照
[JiuwenBox 官方文档](https://gitcode.com/openJiuwen/jiuwenswarm/blob/develop/jiuwenbox/README_CN.md)
完成安装，然后在 JiuwenBox 工程中启动服务：

```bash
sudo ./.venv/bin/jiuwenbox-server
```

默认地址为 `http://127.0.0.1:8321`。确认服务可用：

```bash
curl -s http://127.0.0.1:8321/health
```

macOS、Windows 可以连接运行在 Linux 或 Docker 中的 JiuwenBox，并通过
`OPENJIUWEN_SANDBOX_SERVICE_URL` 指定地址。

## 2. 启动 Java Agent Demo

从 `agent-runtime-java/service` 目录运行：

```bash
OPENJIUWEN_SERVICE_LLM_API_KEY=xxx \
OPENJIUWEN_SERVICE_LLM_API_BASE=https://your-llm-endpoint.example.com \
OPENJIUWEN_SERVICE_LLM_MODEL_NAME=your-model-name \
OPENJIUWEN_SANDBOX_SERVICE_URL=http://127.0.0.1:8321 \
mvn -pl agent-service-demo/example/sandbox -am spring-boot:run
```

Agent Service 默认监听 `http://127.0.0.1:8093`。

## 3. 手工验证完整链路

先让 Agent 在 Sandbox 中创建文件：

```bash
curl -s http://127.0.0.1:8093/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"sandbox-cmd","message":"请调用 executeCmd 工具执行：printf sandbox-file-ok > /tmp/openjiuwen-demo.txt && cat /tmp/openjiuwen-demo.txt","stream":false}'
```

再读取同一个文件：

```bash
curl -s http://127.0.0.1:8093/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"sandbox-read","message":"请调用 readFile 工具读取 /tmp/openjiuwen-demo.txt","stream":false}'
```

最后执行 Python：

```bash
curl -s http://127.0.0.1:8093/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"sandbox-code","message":"请调用 executeCode 工具，用 python 执行 print(\"sandbox-code-ok\")","stream":false}'
```

预期三次回答分别包含命令输出、`sandbox-file-ok` 和 `sandbox-code-ok`。工具是否被选择仍由真实大模型决定，
因此提示词中显式指定了工具名。

## 一键 Smoke

JiuwenBox 已启动后，从 `agent-runtime-java/service` 运行：

```bash
./agent-service-demo/example/sandbox/smoke-sandbox.sh
```

连接其他 JiuwenBox：

```bash
OPENJIUWEN_SANDBOX_SERVICE_URL=http://your-jiuwenbox:8321 \
./agent-service-demo/example/sandbox/smoke-sandbox.sh
```

脚本使用确定性内存 mock LLM，但所有 Sandbox 操作都经过真实网络和独立 JiuwenBox。它会验证：

- Agent 能看到并调用三个 Sandbox 工具；
- `executeCmd` 创建的文件可被 `readFile` 读取；
- `executeCode` 返回真实 Python 输出；
- `sleep` 触发 Runtime timeout，下一次同类调用触发 circuit breaker；
- `EXTERNAL_CALL_AUDIT` 实际写入独立日志目录。

外部集成测试默认跳过，不影响普通 `mvn test`。也可以直接运行：

```bash
mvn -pl agent-service-demo/example/sandbox -am \
  -Dtest=SandboxAgentExternalEndToEndTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddemo.sandbox.e2e.service-url=http://127.0.0.1:8321 \
  test
```

该测试带有 `integration` 标签，并由 `demo.sandbox.e2e.service-url` 控制启用。仅执行 JUnit tag 而不提供该属性时，
测试会被跳过；用于环境冒烟时应优先运行 `smoke-sandbox.sh`，让脚本先检查 JiuwenBox 健康状态并在不可用时失败。

## 配置与边界

主要配置位于 `application-sandbox.yml`：

```yaml
openjiuwen.service.external.sandbox:
  enabled: true
  timeout-ms: 30000
  servers:
    - server-id: default
      service-url: http://127.0.0.1:8321
      sandbox-type: jiuwenbox
      launcher-type: pre_deploy
```

- 本 Demo 不修改 `agent-core-java`，协议由 Core 的 JiuwenBox provider 决定。
- 当前 Core Client 没有为 JiuwenBox 注入 Token 请求头，本 Demo 只验证受信网络中的无 Token 服务。
- `executeCmd`、`executeCode` 有副作用，Runtime 不对它们重试；`readFile` 可以按配置重试。
- Runtime 超时只表示调用方停止等待，已发送到 JiuwenBox 的命令可能继续执行一小段时间。
- `pre_deploy` 下不依赖 `on-stop=delete` 自动清理；外部 E2E 会显式调用 Core 生命周期清理方法。

## 设计取舍

这里选择官方 JiuwenBox，而不是在 Runtime 仓库中实现一个本地 Java 后端。后者如果执行真实命令，会把模型命令
直接运行在宿主机；如果只返回固定结果，又只能证明 mock 协议。回滚本改动只需恢复原测试、配置和文档，不涉及
REST API 或数据迁移。
