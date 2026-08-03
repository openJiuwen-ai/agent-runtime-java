# MCP Demo

这个示例验证用户实际使用 MCP Demo 的完整链路：配置 LLM 和 MCP 地址，启动 Java Agent Service，
通过 `/v1/query` 输入问题，由模型选择工具并调用独立运行的 Python FastMCP Server。

```text
用户请求 /v1/query
→ ReActAgent 将 FastMCP 工具列表传给 LLM
→ LLM 返回 tool_call
→ DecoratingMcpClient
→ 独立 FastMCP Server
→ tools/call 返回结果
→ 结果回填 ReActAgent
→ LLM 生成最终回答
```

## 实现组成

- Java `McpDemoApplication`：提供 `/v1/query`，负责创建 Agent、绑定 MCP 工具和注册装饰后的 Client；
- Python `server/fastmcp_server.py`：使用官方 MCP Python SDK 的 `FastMCP`；
- `server/requirements.txt`：固定官方 MCP Python SDK 版本；
- `smoke-mcp.sh`：启动独立 FastMCP 进程并验证协议、Agent 和治理完整链路；
- `smoke-mcp-sec.sh`：复用同一个 Java Agent，验证公网 SEC Filing MCP 完整链路。

FastMCP 以以下模式运行：

```python
FastMCP(
    stateless_http=True,
    json_response=True,
)
```

因此 endpoint 是 `http://127.0.0.1:18080/mcp`，响应是当前 Core 能解析的普通 JSON，不是 SSE body。

FastMCP 提供三个工具：

| 工具 | 用途 |
|---|---|
| `demo_echo(text)` | 返回 `demo_echo:<text>`，验证工具调用和结果回填 |
| `demo_delay(delay_ms)` | 延迟返回，验证超时和熔断 |
| `demo_fail()` | 抛出工具异常，展示 FastMCP 的 `isError=true` 边界 |

## 一键验证完整链路

要求 JDK 17、Maven、curl 和 Python 3.10 或更高版本。在仓库根目录执行：

```bash
./service/agent-service-demo/example/mcp/smoke-mcp.sh
```

脚本会：

1. 在 `example/mcp/target/fastmcp-venv` 创建隔离的 Python 环境；
2. 安装 `server/requirements.txt` 中固定版本的官方 MCP Python SDK；
3. 启动独立 FastMCP 进程；
4. 真实验证 `initialize`、`tools/list`、`tools/call` 和 JSON Content-Type；
5. 使用确定性 mock LLM 调用 Demo 的 `/v1/query`；
6. 验证模型获得 `demo_echo` 并调用 `demo_echo({"text":"hello"})`；
7. 验证 FastMCP 返回 `demo_echo:hello`，结果回填 Agent，并生成审计日志；
8. 使用 `demo_delay` 验证超时和熔断。

Smoke 不需要真实 LLM API Key。首次运行需要访问 Python 包仓库，之后会复用虚拟环境。常用覆盖参数：

```bash
DEMO_FASTMCP_PYTHON=python3.12 \
DEMO_FASTMCP_PORT=18081 \
DEMO_FASTMCP_VENV=/tmp/openjiuwen-fastmcp-venv \
./service/agent-service-demo/example/mcp/smoke-mcp.sh
```

Java E2E 使用 `integration` 标签，并由 `demo.mcp.e2e.server-path` 控制启用。单独执行 JUnit tag 而不提供地址时，
测试会被跳过；完整冒烟应使用 `smoke-mcp.sh`，由脚本启动 FastMCP、传入地址并强制校验协议和审计结果。

## 手工启动 FastMCP

在第一个终端执行：

```bash
cd service/agent-service-demo/example/mcp

PYTHON_BIN=python3
"$PYTHON_BIN" -m venv target/fastmcp-venv
target/fastmcp-venv/bin/python -m pip install -r server/requirements.txt

DEMO_FASTMCP_HOST=127.0.0.1 \
DEMO_FASTMCP_PORT=18080 \
target/fastmcp-venv/bin/python server/fastmcp_server.py
```

如果系统默认 `python3` 低于 3.10，请把 `PYTHON_BIN` 改为 `python3.10`、`python3.11` 或 `python3.12`。

启动成功后会看到：

```text
Starting FastMCP server endpoint=http://127.0.0.1:18080/mcp
```

## 手工验证 FastMCP 协议

保持 FastMCP 运行，在另一个终端执行：

```bash
MCP_URL=http://127.0.0.1:18080/mcp

curl -sS "$MCP_URL" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"openjiuwen-manual","version":"1.0"}}}'

curl -sS "$MCP_URL" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

curl -sS "$MCP_URL" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"demo_echo","arguments":{"text":"hello"}}}'
```

三个响应均应为普通 JSON：初始化结果包含 `openjiuwen-demo-fastmcp`，工具列表包含
`demo_echo`、`demo_delay`、`demo_fail`，最后一个结果包含 `demo_echo:hello`。

## 启动 Java Agent Service

保持 FastMCP 运行。先构建 Java Demo：

```bash
cd service
mvn -pl agent-service-demo/example/mcp -am -DskipTests package

MCP_DEMO_JAR="$(find agent-service-demo/example/mcp/target -maxdepth 1 \
  -name 'agent-service-demo-mcp-*.jar' ! -name '*.original' -print -quit)"
```

配置真实 LLM 和 FastMCP 地址后启动：

```bash
OPENJIUWEN_SERVICE_LLM_API_KEY='<your-api-key>' \
OPENJIUWEN_SERVICE_LLM_API_BASE='<your-openai-compatible-api-base>' \
OPENJIUWEN_SERVICE_LLM_MODEL_NAME='<your-model-name>' \
DEMO_MCP_SERVER_PATH='http://127.0.0.1:18080/mcp' \
DEMO_MCP_CLIENT_TYPE='streamable-http' \
java -jar "$MCP_DEMO_JAR"
```

Agent Service 默认监听 `http://127.0.0.1:8092`。启动日志应包含：

```text
Bound MCP server to agent ability manager, serverId=demo-mcp, serverName=demo-mcp-tools
Registered external MCP server, serverId=demo-mcp, serverName=demo-mcp-tools
```

第一条表示 FastMCP 工具已绑定给 Agent，第二条表示 MCP Client 已创建并注册到 Core Runner。

## 用用户输入触发 FastMCP 工具

```bash
curl -sS http://127.0.0.1:8092/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id":"mcp-demo-c1",
    "message":"请调用 demo_echo 工具，把 text 设置为 hello，并告诉我工具返回了什么",
    "stream":false
  }'
```

FastMCP 终端应出现：

```text
MCP_TOOL_CALL tool=demo_echo arguments={"text": "hello"}
```

`/v1/query` 的最终结果应包含 `demo_echo:hello`。手工运行使用真实模型，是否调用工具由模型决定，
所以提示词应明确指定工具名和参数；一键 Smoke 使用确定性 mock LLM，不受模型随机性影响。

## MCP 配置

默认配置位于 `application-mcp.yml`：

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
        audit:
          enabled: true
        servers:
          - server-id: demo-mcp
            server-name: demo-mcp-tools
            server-path: http://127.0.0.1:18080/mcp
            client-type: streamable-http
```

Runtime 会把 `streamable-http` 转换成 Core 使用的 `streamable_http`，并为该 Client 设置：

```http
Accept: application/json
```


## 公网 SEC Filing MCP（可选）

SEC MCP 已运行在公网，无需启动本地 Server。它和 FastMCP 复用同一个 Java
`McpDemoApplication`，只替换 MCP 地址和工具。

### 一键验证

```bash
./service/agent-service-demo/example/mcp/smoke-mcp-sec.sh
```

脚本使用 mock LLM，验证协议、Agent 工具调用、SEC 结果回填和审计，不需要 LLM API Key。

### 手工验证完整 Agent 链路

手工验证使用真实 LLM。先构建并启动 Java Agent：

```bash
cd service
mvn -pl agent-service-demo/example/mcp -am -DskipTests package

MCP_DEMO_JAR="$(find agent-service-demo/example/mcp/target -maxdepth 1 \
  -name 'agent-service-demo-mcp-*.jar' ! -name '*.original' -print -quit)"

OPENJIUWEN_SERVICE_LLM_API_KEY='<your-api-key>' \
OPENJIUWEN_SERVICE_LLM_API_BASE='<your-openai-compatible-api-base>' \
OPENJIUWEN_SERVICE_LLM_MODEL_NAME='<your-model-name>' \
DEMO_MCP_SERVER_PATH='https://api.data-apis.com/mcp' \
java -jar "$MCP_DEMO_JAR"
```

在另一个终端发送用户请求：

```bash
curl -sS http://127.0.0.1:8092/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id":"mcp-sec-manual-c1",
    "message":"必须调用 sec_demo_latest_filings 工具，limit 设置为 1，并根据工具结果回答公司名称、表单类型和提交时间。",
    "stream":false
  }'
```

验证成功时：

- `/v1/query` 的 `result.content` 包含真实 SEC Filing 信息；
- Java 日志包含 `EXTERNAL_CALL_AUDIT`、`method=mcp.tools/call` 和 `success=true`。

完整链路为：用户请求 → 真实 LLM → SEC 工具调用 → 公网 MCP → 结果回填 → 最终回答。
公网服务依赖第三方稳定性，因此不加入默认 CI。

## 能力边界与决策

- Agent Demo 和 Runtime 仍然是 Java，Python 只实现独立外部 MCP Server；
- 使用官方 MCP Python SDK，而不是手写 Java JSON-RPC Server，以验证真实 SDK 的工具 schema 和协议边界；
- 不修改 `agent-core-java`；
- 仅验证 `client-type: streamable-http` 和 `Content-Type: application/json`；
- 不验证 SSE body、旧版 HTTP+SSE 长连接、MCP Session、OAuth 或 TLS；
- FastMCP 工具抛异常会返回 `isError=true`，当前 Core 不会把它转换成 Java 异常；
- 熔断验证使用 `demo_delay` 制造调用超时，而不是使用 `demo_fail` 工具错误；
