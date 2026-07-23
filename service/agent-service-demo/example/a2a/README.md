# A2A Multi-Agent 示例

本示例演示三个独立 Agent 如何通过 A2A 协议协作完成一次用户请求，并在需要用户确认时完成中断与恢复。

- **Agent A**：用户入口。所有问题都先转交给 Agent B。
- **Agent B**：任务路由。计算类问题在本地处理；餐饮类问题转交给 Agent C。
- **Agent C**：餐饮助手。给出餐饮推荐前先请求用户确认。

| Agent   | 端口    | 角色                          |
|---------|-------|-----------------------------|
| Agent A | 18090 | 接收用户请求并转交 Agent B           |
| Agent B | 18091 | 区分问题类型：计算走本地工具，餐饮转交 Agent C |
| Agent C | 18092 | 餐饮推荐助手，返回推荐前触发确认            |

> 端到端 smoke 需要真实大模型 API，因为示例依赖 Agent A 和 Agent B 自主选择工具调用。单元测试与编译不需要真实 LLM。

## 场景设计

### 场景一：计算问题，只触发 Agent A -> Agent B

用户问：

```text
What is 1+1? Use Agent B's ordinary calc path.
```

预期行为：

```mermaid
sequenceDiagram
    participant U as User
    participant A as Agent A
    participant B as Agent B

    U->>A: 计算问题
    A->>B: 转交给 Agent B
    B-->>U: 请确认计算
    U->>A: 2
    A->>B: 恢复 Agent B
    B-->>A: 计算结果
    A-->>U: 最终答案
```

这一场景只验证 A 到 B 的原有能力：Agent B 本地触发确认，确认后返回结果。

### 场景二：餐饮问题，触发 Agent A -> Agent B -> Agent C

用户问：

```text
Recommend a dish for a team lunch. Let Agent C provide the food recommendation after confirmation.
```

预期行为：

```mermaid
sequenceDiagram
    participant U as User
    participant A as Agent A
    participant B as Agent B
    participant C as Agent C

    U->>A: 餐饮推荐问题
    A->>B: 转交给 Agent B
    B->>C: 转交给 Agent C
    C-->>U: 请确认是否继续推荐
    U->>A: ok, confirmed
    A->>B: 恢复 Agent B
    B->>C: 恢复 Agent C
    C-->>B: 餐饮推荐结果
    B-->>A: Agent C 的结果
    A-->>U: 最终答案
```

这一场景展示三 Agent 协作：Agent B 识别餐饮问题后转交 Agent C，最终中断由 Agent C 触发。

## 快速开始

手工执行 Maven 启动命令时，需要位于仓库根目录下的 `service` Reactor 目录。smoke 脚本会自行定位该目录，可以从任意工作目录调用。

### 1. 配置大模型 API

任选一种方式配置三个 Agent 共享的大模型 API。

**方式 A：本地配置文件**

```bash
cp agent-service-demo/example/config/application-base_local.example.yml \
   agent-service-demo/example/config/application-base_local.yml
# 编辑 application-base_local.yml，填写 openjiuwen.service.llm 下的 api-key / api-base / model-name
```

**方式 B：apiconfig.json**

```bash
export OPENJIUWEN_API_CONFIG=/path/to/apiconfig.json
```

PowerShell：

```powershell
$env:OPENJIUWEN_API_CONFIG="C:\path\to\apiconfig.json"
```

**方式 C：环境变量**

```bash
export OPENJIUWEN_SERVICE_LLM_API_KEY=...
export OPENJIUWEN_SERVICE_LLM_API_BASE=...
export OPENJIUWEN_SERVICE_LLM_MODEL_NAME=...
```

### 2. 启动三个 Agent

建议按 **Agent C -> Agent B -> Agent A** 的顺序启动。

终端 1：

```bash
mvn -pl agent-service-demo/example/a2a -am spring-boot:run \
  -Dspring-boot.run.main-class=com.openjiuwen.service.demo.example.a2a.A2aAgentCDemoApplication
```

终端 2：

```bash
mvn -pl agent-service-demo/example/a2a -am spring-boot:run \
  -Dspring-boot.run.main-class=com.openjiuwen.service.demo.example.a2a.A2aAgentBDemoApplication
```

终端 3：

```bash
mvn -pl agent-service-demo/example/a2a -am spring-boot:run \
  -Dspring-boot.run.main-class=com.openjiuwen.service.demo.example.a2a.A2aAgentADemoApplication
```

启动后检查健康状态：

```bash
curl -s http://localhost:18092/health
curl -s http://localhost:18091/health
curl -s http://localhost:18090/health
```

### 3. 运行 smoke 脚本

Linux / Git Bash：

```bash
bash /path/to/agent-runtime-java/service/agent-service-demo/example/a2a/smoke-a2a.sh
```

PowerShell：

```powershell
& "C:\path\to\agent-runtime-java\service\agent-service-demo\example\a2a\smoke-a2a.ps1"
```

Linux / Git Bash 和 PowerShell 脚本都会按 Agent C -> Agent B -> Agent A 的顺序自动启动服务、等待健康状态、执行验证，并在成功后清理进程和临时文件。启动进程提前退出时，脚本会立即失败；验证失败时会保留日志和响应文件。

两个脚本的配置解析规则一致：显式设置的相对路径 `OPENJIUWEN_API_CONFIG` 相对于调用脚本时的工作目录解析；未设置时，如果存在 `service/agent-service-demo/apiconfig.json`，则自动使用该文件。脚本随后从 `service` Reactor 目录启动 Maven，因此不会因调用目录不同而加载不到配置。

smoke 覆盖两条用户路径：

1. 计算问题：Agent A -> Agent B，两轮确认后完成。
2. 餐饮问题：Agent A -> Agent B -> Agent C，两轮确认后完成。

### agent-core-java 730 兼容说明

当前 Runtime 使用本地安装的 `agent-core-java:0.1.13`，对应 Core `730` 分支。客户端到 Agent A、Agent A 到 Agent B 可以使用 SSE；Agent B 到 Agent C 必须使用同步 A2A，`BToCDelegateRail` 因此不设置 `_stream_mode`。

这是因为 Core `730` 的 DeepAgent 流式结束与会话状态回写存在时序问题，B -> C 使用 SSE 时可能在确认恢复后丢失原工具调用上下文。同步调用会在返回前完成状态回写。只有在 Core 修复该问题并通过真实的两轮 DeepAgent 恢复验证后，才应重新启用 B -> C SSE。

## 手动验证

### Agent Card

```bash
curl -s http://localhost:18090/.well-known/agent-card.json | python3 -m json.tool
curl -s http://localhost:18091/.well-known/agent-card.json | python3 -m json.tool
curl -s http://localhost:18092/.well-known/agent-card.json | python3 -m json.tool
```

### REST：计算问题（A -> B）

Round 1：

```bash
curl -s -X POST http://localhost:18090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "a2b-calc-001",
    "message": "What is 1+1? Use Agent B ordinary calc path.",
    "stream": false
  }' | python3 -m json.tool
```

预期：返回等待确认的响应。

Round 2：

```bash
curl -s -X POST http://localhost:18090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "a2b-calc-001",
    "message": "2",
    "stream": false
  }' | python3 -m json.tool
```

预期：返回计算结果。

### REST：餐饮问题（A -> B -> C）

Round 1：

```bash
curl -s -X POST http://localhost:18090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "a2b2c-food-001",
    "message": "Recommend a dish for a team lunch. Let Agent C provide the food recommendation after confirmation.",
    "stream": false
  }' | python3 -m json.tool
```

预期：返回 Agent C 发起的确认请求。

Round 2：

```bash
curl -s -X POST http://localhost:18090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "a2b2c-food-001",
    "message": "ok, confirmed",
    "stream": false
  }' | python3 -m json.tool
```

预期：返回 Agent C 的餐饮推荐，并由 Agent A 汇总给用户。

### A2A SSE：计算问题（A -> B）

Round 1：

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
        "parts": [{"text": "What is 1+1? Use Agent B ordinary calc path."}]
      }
    }
  }'
```

预期：SSE 事件流进入 Agent B 的计算确认中断后关闭，等待用户确认。

Round 2：

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

预期：恢复同一会话并返回计算结果。

### A2A SSE：餐饮问题（A -> B -> C）

以下请求使用 SSE 访问 Agent A；Agent 内部的 A -> B 保持流式，而 B -> C 按上述 Core `730` 兼容约束使用同步 A2A。

Round 1：

```bash
curl -s -N -X POST http://localhost:18090/a2a \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "jsonrpc": "2.0", "id": 1,
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER", "contextId": "food-sse-001",
        "parts": [{"text": "Recommend a dish for a team lunch. Let Agent C provide the food recommendation after confirmation."}]
      }
    }
  }'
```

预期：SSE 事件流进入 `INPUT_REQUIRED` 后关闭，等待用户确认。

Round 2：

```bash
curl -s -N -X POST http://localhost:18090/a2a \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "jsonrpc": "2.0", "id": 2,
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER", "contextId": "food-sse-001",
        "parts": [{"text": "ok, confirmed"}]
      }
    }
  }'
```

预期：恢复同一会话并返回最终餐饮推荐。

## 可选：使用 Redis 保存会话

默认使用内存存储。若希望跨进程保留会话和任务状态，可以启用 Redis。

创建本地 overlay：

```bash
cp agent-service-demo/example/a2a/application-a2a-redis.example.yml \
   agent-service-demo/example/a2a/application-a2a-redis.local.yml
```

编辑 `application-a2a-redis.local.yml`，按单机 Redis 填写 `host` / `port` / 密码，或按 Redis Cluster 填写 `type: cluster` 和 `nodes`。该文件为本地配置，不会提交。

启动后可用以下命令观察任务 key：

```bash
redis-cli KEYS "a2a:task:*"
```
