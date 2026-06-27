# A2A Agent Service — 端到端验证指南

两个 ReAct LLM Agent（A 与 B）通过 A2A 协议互联，验证 agent card 发现、SendMessage 远程调用、中断-恢复链等能力。

## 1. 环境要求

| 依赖 | 版本要求 |
|------|---------|
| JDK | 17+ |
| Maven | 3.8+ |
| LLM API | OpenAI 兼容端点（例：`http://127.0.0.1:4000/v1`） |
| 可用端口 | `18090`（Agent A）、`18091`（Agent B） |

## 2. 编译项目

在仓库根目录执行：

```bash
mvn install -Dmaven.test.skip=true -pl service/agent-service-app,service/agent-service-adapters/agent-service-adapters-agentcore -am
```

`agent-service-a2a-test` 依赖 `agent-service-app` 的 JAR，修改代码后必须先 `install` 到本地仓库。

## 3. 配置 LLM

编辑两个 YAML 文件，填入 LLM 端点信息：

**Agent A**：`src/main/resources/application-agent-a.yml`

```yaml
openjiuwen:
  agenta:
    llm:
      api-key: "sk-your-key"
      api-base: "http://127.0.0.1:4000/v1"
      model-name: "gpt-5.4-mini"
```

**Agent B**：`src/main/resources/application-agent-b.yml`

```yaml
openjiuwen:
  agentb:
    llm:
      api-key: "sk-your-key"
      api-base: "http://127.0.0.1:4000/v1"
      model-name: "gpt-5.4-mini"
```

可通过命令行覆盖：

```bash
--openjiuwen.agenta.llm.api-key=sk-real-key
--openjiuwen.agenta.llm.api-base=https://api.openai.com/v1
```

配置前缀一览：

| YAML 路径 | 用途 |
|-----------|------|
| `openjiuwen.service.a2a.*` | A2A 协议（agent card、skills、remote-agents） |
| `openjiuwen.agenta.llm.*` | Agent A 的 LLM 连接参数 |
| `openjiuwen.agentb.llm.*` | Agent B 的 LLM 连接参数 |

## 4. 启动 Agent 服务

> **顺序要求：先启动 Agent B，再启动 Agent A。**

**终端 1 — 启动 Agent B（端口 18091）：**

```bash
mvn -pl service/agent-service-a2a-test spring-boot:run \
    -Dspring-boot.run.main-class=com.openjiuwen.a2a.AgentBApp \
    -Dspring-boot.run.profiles=agent-b
```

看到 `Started AgentBApp in X seconds` 表示启动成功。

**终端 2 — 启动 Agent A（端口 18090）：**

```bash
mvn -pl service/agent-service-a2a-test spring-boot:run \
    -Dspring-boot.run.main-class=com.openjiuwen.a2a.AgentAApp \
    -Dspring-boot.run.profiles=agent-a
```

看到 `Started AgentAApp in X seconds` 表示启动成功。

## 5. 手动验证

### 5.1 获取 Agent Card

```bash
curl -s http://localhost:18090/.well-known/agent-card.json | python3 -m json.tool
curl -s http://localhost:18091/.well-known/agent-card.json | python3 -m json.tool
```

**预期结果：** 返回 JSON，包含 `name`、`description`、`skills`、`url`（`/a2a`，不含尾部斜杠）等字段。

### 5.2 流式 SSE 调用

```bash
curl -s -N -X POST http://localhost:18090/a2a \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "contextId": "test-001",
        "parts": [{"text": "Hello!"}]
      }
    }
  }'
```

**预期结果：** SSE 流式输出，event name 为 `jsonrpc`，状态序列 `SUBMITTED → WORKING → ...`。

### 5.3 非流式 Sync 调用

```bash
curl -s -X POST http://localhost:18090/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "SendMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "contextId": "test-002",
        "parts": [{"text": "Hello!"}]
      }
    }
  }' | python3 -m json.tool
```

**预期结果：** 单次 JSON 响应，`result` 包含完整 Task 状态。

### 5.4 计算器中断-恢复场景（SSE 流式）

Agent A 将 `1+1=?` 委托给 Agent B。Agent B 的 `calc` 工具触发 `ask_user` 中断 → `INPUT_REQUIRED`。用户第二次输入后恢复，Agent B 返回结果。

**步骤 1 — 发起计算请求：**

```bash
curl -s -N -X POST http://localhost:18090/a2a \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "contextId": "calc-001",
        "parts": [{"text": "What is 1+1?"}]
      }
    }
  }'
```

**预期结果：** `SUBMITTED → WORKING → INPUT_REQUIRED`，SSE 流在 INPUT_REQUIRED 后关闭。

**步骤 2 — 使用相同 contextId 恢复：**

```bash
curl -s -N -X POST http://localhost:18090/a2a \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "contextId": "calc-001",
        "parts": [{"text": "ok"}]
      }
    }
  }'
```

**预期结果：** Agent A 检测到影子 Task → 恢复 Agent B → Agent B 返回 `2` → artifact 带 `answer:true` → `COMPLETED`。

### 5.5 计算器中断-恢复场景（Sync 非流式）

同一场景使用 `SendMessage`（非流式）。两轮请求都用 `POST` + 单次 JSON 响应。

```bash
# Round 1
curl -s -X POST http://localhost:18090/a2a \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{"role":"ROLE_USER","contextId":"calc-sync","parts":[{"text":"What is 1+1?"}]}}}' \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['result']['task']['status']['state'])"

# Round 2
curl -s -X POST http://localhost:18090/a2a \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"SendMessage","params":{"message":{"role":"ROLE_USER","contextId":"calc-sync","parts":[{"text":"ok"}]}}}' \
  | python3 -c "import json,sys; d=json.load(sys.stdin); t=d['result']['task']; print(t['status']['state'], t['artifacts'][0]['parts'][0]['text'] if t.get('artifacts') else '')"
```

**预期结果：** Round 1 → `TASK_STATE_INPUT_REQUIRED`，Round 2 → `TASK_STATE_COMPLETED 2`。

### 5.6 GetTask — 查询任务状态

通过 `GetTask` 查询 Agent A 的任务，验证中断前后状态变化。

```bash
# Round 1 后查询 INPUT_REQUIRED 状态
curl -s -X POST http://localhost:18090/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 10,
    "method": "GetTask",
    "params": {
      "id": "<Round 1 返回的 taskId>"
    }
  }' | python3 -m json.tool

# Round 2 后查询 COMPLETED 状态（含 artifact）
curl -s -X POST http://localhost:18090/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 11,
    "method": "GetTask",
    "params": {
      "id": "<Round 2 返回的 taskId>"
    }
  }' | python3 -m json.tool
```

**预期结果：** Round 1 后 `status.state = TASK_STATE_INPUT_REQUIRED`；Round 2 后 `status.state = TASK_STATE_COMPLETED`，`artifacts` 含计算结果。

### 5.7 REST API 调用

同一场景通过 REST `/v1/query` 端点：

**流式：**

```bash
# Round 1
curl -s -N -X POST http://localhost:18090/v1/query \
  -H "Content-Type: application/json" \
  -d '{"message":"What is 1+1?","conversation_id":"calc-rest","stream":true}'

# Round 2
curl -s -N -X POST http://localhost:18090/v1/query \
  -H "Content-Type: application/json" \
  -d '{"message":"ok","conversation_id":"calc-rest","stream":true}'
```

**非流式：**

```bash
# Round 1
curl -s -X POST http://localhost:18090/v1/query \
  -H "Content-Type: application/json" \
  -d '{"message":"What is 1+1?","conversation_id":"calc-rest2","stream":false}'

# Round 2
curl -s -X POST http://localhost:18090/v1/query \
  -H "Content-Type: application/json" \
  -d '{"message":"ok","conversation_id":"calc-rest2","stream":false}'
```

## 6. 运行集成测试

**单元测试（无需 LLM、无需启动 Agent）：**

```bash
mvn test -pl service/agent-service-app -am
```

**集成测试（需要 LLM、自动启动 Agent）：**

```bash
mvn verify -pl service/agent-service-a2a-test -am
```

集成测试使用 stub handler 替代 LLM，无需真实 LLM 即可验证 A2A 协议交互。

测试覆盖：

| 测试类 | 验证点 |
|--------|--------|
| `A2aInterruptScenarioTest` | AgentCard 可达、SendMessage 返回正确 JSON-RPC |
| `A2AClientBestPracticeTest` | SDK Client 模式：sendMessage、GetTask、流式响应 |

## 7. 架构概览

```
┌─────────────┐     A2A Protocol      ┌─────────────┐
│   Agent A   │ ◄──────────────────►  │   Agent B   │
│  (18090)    │   agent-card.json     │  (18091)    │
│             │   SendMessage         │             │
│  ReActAgent │   SendStreamingMessage│  ReActAgent │
│  + LLM      │                       │  + LLM      │
└─────────────┘                       └─────────────┘
       │                                      │
       ▼                                      ▼
  gpt-5.4-mini                           gpt-5.4-mini
  (127.0.0.1:4000)                      (127.0.0.1:4000)
```

## 8. 常见问题

**Q: 启动时提示 `api-key is required`？**
检查 YAML 配置前缀：Agent A 为 `openjiuwen.agenta.llm.api-key`，Agent B 为 `openjiuwen.agentb.llm.api-key`。

**Q: 调用返回 404？**
确认 A2A 相关的依赖已在 classpath 上。

**Q: LLM 调用超时？**
增加对应 agent 的 `timeout` 值：
- Agent A: `openjiuwen.agenta.llm.timeout: 120s`
- Agent B: `openjiuwen.agentb.llm.timeout: 120s`

**Q: Agent A 无法发现 Agent B？**
检查启动顺序（先 B 后 A），确认 Agent B 的 agent card 可访问：
`curl http://localhost:18091/.well-known/agent-card.json`
