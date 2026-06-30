# A2A Multi-Agent 示例

独立 Maven 模块 `agent-service-demo-a2a`，演示两个 ReAct LLM Agent 通过 **A2A 协议**（Agent-to-Agent）进行远程协作与中断/恢复。

| 项 | 值 |
| --- | --- |
| 目录 | `example/a2a/` |
| Agent A 端口 | **18090** |
| Agent B 端口 | **18091** |
| Agent | `ReActAgent`（`ExampleReActAgentFactory`） |
| Handler | `JiuwenCoreAgentHandler`（走 Core Runner） |
| 协议 | A2A JSON-RPC（SendMessage / SendStreamingMessage / GetTask） |

## 这个示例解决什么问题

单 Agent 服务只能处理自身能力范围内的请求。当需要多个 Agent 协作（如一个 Agent 委托计算任务给另一个 Agent），且需要用户确认时，就涉及 A2A 协议下的**远程调用**与**中断-恢复**：

1. **Agent Card 发现**：Agent A 启动时通过 Agent Card 发现 Agent B 的能力和端点。
2. **A2A 委托**：Agent A 的 LLM 调用 `delegate_to_agentb` 工具 → Rail 拦截 → 触发 A2A 中断 → 编排器调用 Agent B。
3. **中断-恢复**：Agent B 的 `calc` 工具触发 `ask_user` 中断 → 用户确认 → 恢复执行 → 结果返回 Agent A。

> 必须使用真实大模型 API，两个 Agent 共享同一套 LLM 配置。

## 快速开始

在仓库根目录下的 **`agent-runtime-java/service`** 中执行。

### 1. 配置大模型 API

本模块两个 Agent 共享 LLM 配置，任选一种方式（详见 [../README.md](../README.md) 的「模型 API 配置」）：

**方式 A — 本地 yml（推荐）**

```bash
cp ../config/application-base_local.example.yml ../config/application-base_local.yml
# 编辑 application-base_local.yml，填写 openjiuwen.demo.llm 下的 api-key / api-base / model-name
```

**方式 B — apiconfig.json**

```bash
# 工作目录或 OPENJIUWEN_API_CONFIG 指向的 apiconfig.json
export OPENJIUWEN_API_CONFIG=/path/to/apiconfig.json   # Linux / Git Bash
$env:OPENJIUWEN_API_CONFIG="C:\path\to\apiconfig.json"  # PowerShell
```

**方式 C — 环境变量**

`application-base.yml` 支持占位符：`OPENJIUWEN_DEMO_LLM_API_KEY`、`OPENJIUWEN_DEMO_LLM_API_BASE`、`OPENJIUWEN_DEMO_LLM_MODEL_NAME`。

### 2. （可选）切换为 Redis Checkpointer + Task Store

默认使用 **in-memory** 存储（进程退出后会话和 A2A 任务丢失）。切换到 Redis 可获得：

- **跨进程恢复**：停止 Agent 后重启，同一 `conversation_id` 仍可恢复会话
- **持久化 Task Store**：A2A shadow task、`GetTask` 查询在进程重启后仍可用
- **双 Agent 共享存储**：Agent A 和 Agent B 使用同一 Redis，shadow task 按 agentId 命名空间隔离

> A2A task store 自动跟随 `checkpointer.type` 配置，无需额外设置。

**方式 A — 添加到 application-base_local.yml（推荐）**

编辑 `../config/application-base_local.yml`，添加 checkpointer 和 Redis 连接配置：

```yaml
openjiuwen:
  demo:
    llm:
      auto-discover: true       # 已有
  service:
    middleware:
      checkpointer:
        type: redis
        redis-ref: default
      redis:
        default:
          host: 127.0.0.1
          port: 6379
          database: 0
          timeout-ms: 3000
          encrypted-password: ""
```

由于 `application-base_local.yml` 已在 import 链中（base → base_local → feature），添加后即生效。

**方式 B — 独立 Redis overlay 文件**

入口类已预置 `optional:classpath:application-a2a-redis.yml`（import 链末尾），只需创建文件：

```bash
cp application-a2a-redis.example.yml application-a2a-redis.yml
# 编辑 host / port / password
```

此文件在 `.gitignore` 中，不会被提交。

**验证 Redis 已生效**

启动后检查日志，应出现：

```
Succeed to initializing checkpointer with type: redis
```

通过 A2A 发起一次中断-恢复流程后，查看 Redis 中的 key：

```bash
redis-cli KEYS "a2a:task:*"
redis-cli KEYS "*:agent:demo-a2a-agent*:agent_state_blobs"
```

### 3. 启动服务

> **顺序要求：先启动 Agent B，再启动 Agent A。**

**终端 1 — 启动 Agent B（端口 18091）：**

```bash
mvn -pl agent-service-demo/example/a2a -am spring-boot:run \
    -Dspring-boot.run.main-class=com.openjiuwen.service.demo.example.a2a.A2aAgentBDemoApplication
```

启动成功后 `/health` 返回 `app=demo-a2a-agent-service`。

**终端 2 — 启动 Agent A（端口 18090）：**

```bash
mvn -pl agent-service-demo/example/a2a -am spring-boot:run \
    -Dspring-boot.run.main-class=com.openjiuwen.service.demo.example.a2a.A2aAgentADemoApplication
```

> Agent A 启动时会通过 Agent Card 发现 Agent B。若 Agent B 未就绪，Agent A 仍可启动但委托调用会失败。

### 4. 运行 smoke 脚本

> 先确保两个 Agent 均已启动。

**Linux / Git Bash：**

```bash
bash agent-service-demo/example/a2a/smoke-a2a.sh
# 可选: BASE_URL_A=http://127.0.0.1:18090 BASE_URL_B=http://127.0.0.1:18091 CONV_ID=my-c1 bash ...
```

smoke 脚本会自动启动两个 Agent 进程，运行测试，然后清理。

**Windows（推荐）：**

```powershell
cd agent-service-demo\example\a2a
.\smoke-a2a.ps1
# 可选: .\smoke-a2a.ps1 -BaseUrlA http://127.0.0.1:18090 -BaseUrlB http://127.0.0.1:18091 -ConvId my-c1
```

smoke 脚本流程：

1. 启动 Agent B（后台）→ 等待健康检查通过
2. 启动 Agent A（后台）→ 等待健康检查通过
3. `GET /.well-known/agent-card.json` — 验证两个 Agent Card 可达
4. Round 1：向 Agent A 发送 `What is 1+1?` → 触发 `delegate_to_agentb` → Agent B `calc` 中断 → `INPUT_REQUIRED`
5. Round 2：相同 `conversation_id` 发送 `ok` → 恢复 → Agent B 返回结果 → 完成

## 手动验证

### Agent Card 发现

```bash
curl -s http://localhost:18090/.well-known/agent-card.json | python3 -m json.tool
curl -s http://localhost:18091/.well-known/agent-card.json | python3 -m json.tool
```

### 流式 SSE 调用

```bash
curl -s -N -X POST http://localhost:18090/a2a \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "jsonrpc": "2.0", "id": 1,
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER", "contextId": "sse-001",
        "parts": [{"text": "Hello!"}]
      }
    }
  }'
```

预期：SSE 流式输出，event name 为 `jsonrpc`，状态序列 `SUBMITTED → WORKING → ...`。

### 非流式 Sync 调用

```bash
curl -s -X POST http://localhost:18090/a2a \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0", "id": 1,
    "method": "SendMessage",
    "params": {
      "message": {
        "role": "ROLE_USER", "contextId": "sync-001",
        "parts": [{"text": "Hello!"}]
      }
    }
  }' | python3 -m json.tool
```

### 计算器中断-恢复场景（SSE）

**步骤 1 — 发起计算请求：**

```bash
curl -s -N -X POST http://localhost:18090/a2a \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "jsonrpc": "2.0", "id": 1,
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER", "contextId": "calc-sse-001",
        "parts": [{"text": "What is 1+1?"}]
      }
    }
  }'
```

预期：`SUBMITTED → WORKING → INPUT_REQUIRED`，SSE 流在 `INPUT_REQUIRED` 后关闭。

**步骤 2 — 相同 contextId 恢复：**

```bash
curl -s -N -X POST http://localhost:18090/a2a \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "jsonrpc": "2.0", "id": 2,
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER", "contextId": "calc-sse-001",
        "parts": [{"text": "ok"}]
      }
    }
  }'
```

预期：Agent A 检测到影子 Task → 恢复 Agent B → Agent B 返回计算结果 → `COMPLETED`。

### 计算器中断-恢复（Sync）

```bash
# Round 1
curl -s -X POST http://localhost:18090/a2a \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{"role":"ROLE_USER","contextId":"calc-sync","parts":[{"text":"What is 1+1?"}]}}}' \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['result']['task']['status']['state'])"

# Round 2
curl -s -X POST http://localhost:18090/a2a \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"SendMessage","params":{"message":{"role":"ROLE_USER","contextId":"calc-sync","parts":[{"text":"ok"}]}}}' \
  | python3 -c "import json,sys; d=json.load(sys.stdin); t=d['result']['task']; print(t['status']['state'], t['artifacts'][0]['parts'][0]['text'] if t.get('artifacts') else '')"
```

预期：Round 1 → `TASK_STATE_INPUT_REQUIRED`，Round 2 → `TASK_STATE_COMPLETED` + 结果。

### REST API 调用

同一场景可通过 `/v1/query` 端点：

```bash
# Round 1 — 流式
curl -s -N -X POST http://localhost:18090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"message":"What is 1+1?","conversation_id":"calc-rest","stream":true}'

# Round 2 — 流式
curl -s -N -X POST http://localhost:18090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"message":"ok","conversation_id":"calc-rest","stream":true}'

# 非流式
curl -s -X POST http://localhost:18090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"message":"What is 1+1?","conversation_id":"calc-rest2","stream":false}'
curl -s -X POST http://localhost:18090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"message":"ok","conversation_id":"calc-rest2","stream":false}'
```

## 配置说明

本模块通过 `SpringApplicationBuilder` 编程式指定 `spring.config.import` 分层加载配置（后加载的文件覆盖先加载的）：

```
application-base.yml               → 共享 LLM、Redis 连接、中间件默认值
application-base_local.yml         → 本地密钥覆盖（gitignore）
application-a2a-agent-a.yml        → Agent A：端口 18090 + A2A server + 远程 agent
application-a2a-agent-b.yml        → Agent B：端口 18091 + A2A server + 技能
```

| 文件 | 作用 |
| --- | --- |
| `../config/application-base.yml` | `openjiuwen.demo.llm`、Redis 连接、`checkpointer.type: in_memory`（默认） |
| `../config/application-base_local.yml` | 本地 API 覆盖（勿提交）、Redis 地址覆盖 |
| `application-a2a-agent-a.yml` | **`server.port: 18090`**、A2A server agent card、远程 Agent B 注册、Agent A 技能 |
| `application-a2a-agent-b.yml` | **`server.port: 18091`**、A2A server agent card、Agent B 技能 |

> 两个 Agent 共享同一套 LLM 配置（`openjiuwen.demo.llm`），各自的 `system-prompt` 在对应的 `application-a2a-agent-*.yml` 中覆盖。

## 架构概览

```
                    A2A Protocol
┌──────────────┐  (JSON-RPC 2.0)  ┌──────────────┐
│   Agent A    │ ◄──────────────► │   Agent B    │
│   (18090)    │                  │   (18091)    │
│              │  agent-card.json │              │
│  ReActAgent  │  SendMessage     │  ReActAgent  │
│  + LLM       │  SendStreaming   │  + LLM       │
│  + A2aDelegateRail             │  + CalcInterruptRail
│              │  GetTask         │              │
└──────────────┘                  └──────────────┘

中断流程:
  User → Agent A → delegate_to_agentb → Rail 中断
  → Orchestrator → Agent B → calc → ask_user 中断
  → User 确认 → 恢复 Agent B → 结果 → Agent A → User
```

## 常见问题

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 启动报 `api-key` / `api-base` / `model-name` 未配置 | LLM 未填 | 配置 `application-base_local.yml` 或 `apiconfig.json` |
| Agent A 无法发现 Agent B | Agent B 未启动或端口不对 | 先启动 Agent B，`curl http://localhost:18091/.well-known/agent-card.json` 确认可达 |
| A2A 调用返回 404 | A2A 路径不正确 | 检查 `application-a2a-agent-a.yml` 中 `remote.agents.agentb.url` 和 `path` |
| LLM 调用超时 | 模型响应慢 | 增加 `application-base.yml` 中 `openjiuwen.demo.llm.timeout` 值 |
| 中断后无法恢复 | conversation_id / contextId 不一致 | 两轮使用相同 ID；确认 checkpointer 配置正确 |
| 连错 Agent | 端口混淆 | 确认 Agent A 为 18090，Agent B 为 18091 |

## 相关代码

- 入口：`src/main/java/.../a2a/A2aAgentADemoApplication.java`、`A2aAgentBDemoApplication.java`
- Rail：`A2aDelegateRail.java`（Agent A 的委托拦截）、`CalcInterruptRail.java`（Agent B 的计算中断）
- 共享工厂与 LLM 配置：`../support/`、`../config/`
- A2A SDK 集成：`agent-service-app` 中的 `A2AServerAutoConfiguration`

更多特性示例见 [../README.md](../README.md)。
