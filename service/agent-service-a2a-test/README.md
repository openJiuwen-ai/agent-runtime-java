# A2A Agent Service — 端到端验证指南

两个 ReAct LLM Agent（A 与 B）通过 A2A 协议互联，验证 agent card 发现、SendMessage 远程调用等能力。

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
mvn install -Dmaven.test.skip=true -pl service/agent-service-app -am
```

`agent-service-a2a-test` 依赖 `agent-service-app` 的 JAR，修改 `agent-service-app` 代码后必须先 `install` 到本地仓库，否则 a2a-test 使用的是旧 JAR。

## 3. 配置 LLM

编辑两个 YAML 文件，填入你的 LLM 端点信息。每个 Agent 使用独立的前缀，避免混淆：

**Agent A**：`service/agent-service-a2a-test/src/main/resources/application-agent-a.yml`

```yaml
openjiuwen:
  agenta:
    llm:
      api-key: "sk-your-key"          # ← 替换为真实 API Key
      api-base: "http://127.0.0.1:4000/v1"  # ← 替换为真实地址
      model-name: "gpt-5.4-mini"      # ← 替换为真实模型名
      system-prompt: "You are a helpful assistant. Answer concisely and accurately."
```

**Agent B**：`service/agent-service-a2a-test/src/main/resources/application-agent-b.yml`

```yaml
openjiuwen:
  agentb:
    llm:
      api-key: "sk-your-key"
      api-base: "http://127.0.0.1:4000/v1"
      model-name: "gpt-5.4-mini"
      system-prompt: "You are a helpful assistant. Answer concisely and accurately."
```

也可以通过命令行覆盖（无需改文件）：

```bash
# Agent A 覆盖
--openjiuwen.agenta.llm.api-key=sk-real-key
--openjiuwen.agenta.llm.api-base=https://api.openai.com/v1
--openjiuwen.agenta.llm.model-name=gpt-4o

# Agent B 覆盖
--openjiuwen.agentb.llm.api-key=sk-real-key
--openjiuwen.agentb.llm.api-base=https://api.openai.com/v1
--openjiuwen.agentb.llm.model-name=gpt-4o
```

配置前缀一览：

| YAML 路径 | 用途 | Properties 类 |
|-----------|------|---------------|
| `openjiuwen.service.a2a.*` | A2A 协议（agent card、skills、remote-agents） | `A2AProperties`（来自 agent-service-app） |
| `openjiuwen.agenta.llm.*` | Agent A 的 LLM 连接参数 | `AgentALlmProperties` |
| `openjiuwen.agentb.llm.*` | Agent B 的 LLM 连接参数 | `AgentBLlmProperties` |

## 4. 启动 Agent 服务

> **顺序要求：先启动 Agent B，再启动 Agent A。** Agent A 启动时会获取 Agent B 的 agent card。

启动命令由两部分组成：

| 参数 | 作用 | 示例 |
|------|------|------|
| `-Dspring-boot.run.main-class=...` | 指定运行哪个 `@SpringBootApplication` 类 | `...AgentBApp` 或 `...AgentAApp` |
| `-Dspring-boot.run.profiles=...` | 激活对应的 `application-{profile}.yml` 配置文件 | `agent-b` 或 `agent-a` |

**原理：**

- **`main-class`** — 模块内有 `AgentAApp` 和 `AgentBApp` 两个 `@SpringBootApplication` 入口，此参数决定启动哪一个。
- **`profiles`** — Spring Boot 按固定命名规则加载配置文件：

  ```
  spring.profiles.active = agent-b
         │                    │
         └─ 属性名             └─ profile 值
                                │
                    ┌───────────┘
                    ▼
              application-agent-b.yml    ← 加载此文件
  ```

  规则是 `application-{profile}.yml`，由 Spring Boot 在 classpath 下自动查找。两者解耦——同一个 `AgentAApp` 可以搭配不同 profile（比如新增 `application-agent-a-prod.yml`，通过 `-Dspring-boot.run.profiles=agent-a-prod` 切换生产配置），无需改代码。

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
# Agent A 的 agent card（包含 skills、remote-agents 信息）
curl -s http://localhost:18090/.well-known/agent-card.json | python3 -m json.tool

# Agent B 的 agent card
curl -s http://localhost:18091/.well-known/agent-card.json | python3 -m json.tool
```

**预期结果：** 返回 JSON，包含 `name`、`description`、`skills`、`url` 等字段。

### 5.2 流式调用 Agent B

```bash
curl -s -N -X POST http://localhost:18091/a2a/ \
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
        "parts": [{"text": "Hello! What can you do?"}]
      }
    }
  }'
```

`-N` 禁用 curl 输出缓冲，`SendStreamingMessage` 返回 SSE（Server-Sent Events）流式响应。

**预期结果：** 逐 token 输出 SSE 事件：

```
data: {"type":"chunk","payload":{"role":"assistant","content":"Hello"}}

data: {"type":"chunk","payload":{"role":"assistant","content":"!"}}

data: {"type":"task","payload":{"taskState":"TASK_STATE_COMPLETED"}}
```

### 5.3 流式调用 Agent A

```bash
curl -s -N -X POST http://localhost:18090/a2a/ \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "contextId": "test-002",
        "parts": [{"text": "Hello!"}]
      }
    }
  }'
```

### 5.4 Agent A 委托给 Agent B（计算器中断-恢复场景）

Agent A 将 `1+1=?` 委托给 Agent B。Agent B 的 `calc` 工具（Mock Rail）第一次调用触发 `ask_user` 中断 → `INPUT_REQUIRED`。用户第二次输入后恢复，Agent B 返回 `2` → Agent A LLM 给出最终答案。

**步骤 1 — 发起计算请求，触发 INPUT_REQUIRED：**

```bash
curl -s -N -X POST http://localhost:18090/a2a/ \
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

**预期结果：** 流程走到 Agent B 的 `calc` 工具触发中断，SSE 流关闭前输出 `TASK_STATE_INPUT_REQUIRED`。

```
WORKING → [Agent B calc 中断] → INPUT_REQUIRED → COMPLETED
```

**步骤 2 — 使用相同的 contextId 发送恢复消息：**

```bash
curl -s -N -X POST http://localhost:18090/a2a/ \
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

**预期结果：** Agent A 的编排器发现 `calc-001` 有挂起的远程任务，携带 Agent B 的 taskId 恢复。Agent B 的 `calc` 工具返回 `2`。最终 artifact 带 `metadata: {"answer": true}`。

```
WORKING → artifact("2") → artifact("2", answer:true) → COMPLETED
```

### 5.5 Agent A 委托给 Agent B（酒店搜索，流式透传）

Agent A 将酒店搜索委托给 Agent B。Agent B 直接处理并流式返回，Agent A 将 Agent B 的流式输出逐 token 透传给客户端，`answer` artifact 作为最终工具结果。

```bash
curl -s -N -X POST http://localhost:18090/a2a/ \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "contextId": "test-003",
        "parts": [{"text": "Find hotels in Beijing"}]
      }
    }
  }'
```

**预期结果：** Agent A → Agent B 流式调用。Agent B 的 LLM 逐 token 流式输出酒店推荐，其中 `metadata: {"answer": true}` 的 artifact 作为工具结果。Agent A 恢复后将结果注入 LLM 上下文，输出最终答案。

### 5.6 GetTask — 查询任务状态

通过 `GetTask` 按 `taskId` 查询任务当前状态，验证非流式 JSON-RPC 响应的正确性。

```bash
curl -s -X POST http://localhost:18091/a2a/ \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "GetTask",
    "params": {
      "id": "<从 5.4 步骤 1 响应中提取的 taskId>"
    }
  }' | python3 -m json.tool
```

**预期结果：** 返回完整 Task JSON，`result` 直接包含 Task 字段（`id`、`contextId`、`status`、`artifacts`、`history`），不含额外的 `"task"` 嵌套包装。

```
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": {
        "id": "xxx",
        "contextId": "calc-001",
        "status": {
            "state": "TASK_STATE_INPUT_REQUIRED",
            "message": { ... },
            "timestamp": "..."
        },
        "artifacts": [],
        "history": []
    }
}
```

## 6. 运行集成测试

```bash
mvn test -pl service/agent-service-a2a-test -am
```

测试会自动启动 Agent B 和 Agent A（使用 YAML 中的 LLM 配置），然后验证：

| 测试 | 验证点 |
|------|--------|
| `agentACardIsReachable` | Agent A 的 `/.well-known/agent-card.json` 可访问 |
| `agentBCardIsReachable` | Agent B 的 `/.well-known/agent-card.json` 可访问 |
| `agentARespondsToSendMessage` | Agent A 响应 `SendMessage` JSON-RPC 请求 |
| `agentBRespondsToSendMessage` | Agent B 响应 `SendMessage` JSON-RPC 请求 |

## 7. 架构概览

```
┌─────────────┐     A2A Protocol      ┌─────────────┐
│   Agent A   │ ◄──────────────────►  │   Agent B   │
│  (18090)    │   agent-card.json     │  (18091)    │
│             │   SendMessage         │             │
│  ReActAgent │                       │  ReActAgent │
│  + LLM      │                       │  + LLM      │
└─────────────┘                       └─────────────┘
       │                                      │
       ▼                                      ▼
  gpt-5.4-mini                           gpt-5.4-mini
  (127.0.0.1:4000)                      (127.0.0.1:4000)
```

## 8. 常见问题

**Q: 启动时提示 `api-key is required`？**  
检查 YAML 中对应的 LLM 配置前缀：Agent A 为 `openjiuwen.agenta.llm.api-key`，Agent B 为 `openjiuwen.agentb.llm.api-key`。

**Q: 调用 `/a2a/` 返回 404？**  
确认 A2A 相关的依赖已在 classpath 上（`agent-service-app` 模块已包含 A2A SDK）。

**Q: LLM 调用超时？**  
增加对应 agent 的 `timeout` 值（默认 60s）：
- Agent A: `openjiuwen.agenta.llm.timeout: 120s`
- Agent B: `openjiuwen.agentb.llm.timeout: 120s`

**Q: Agent A 无法发现 Agent B？**  
检查启动顺序（先 B 后 A），确认 Agent B 的 agent card 可访问：`curl http://localhost:18091/.well-known/agent-card.json`。
