# A2A Multi-Agent 示例

本示例演示四个独立 Agent 通过 A2A 协议协作，并在需要用户确认或审批时中断和恢复任务。所有业务请求都发送到 Agent A 的 `/a2a/` 端点。

## 使用 smoke 脚本快速验证

smoke 脚本会自动启动 Agent D、C、B、A，检查健康状态和 Agent Card，执行全部端到端场景，并在成功后关闭服务。脚本可以从任意工作目录运行。

运行前需要准备 JDK 17、Maven、可访问的大模型配置。Bash 脚本还需要 `curl` 和 Python 3；PowerShell 脚本需要 PowerShell 7 或 Windows PowerShell。

可以在仓库根目录复制模型配置模板并填写 `API_KEY`、`API_BASE` 和 `MODEL_NAME`：

```bash
cp service/agent-service-demo/apiconfig_example.json \
   service/agent-service-demo/apiconfig.json
```

也可以通过绝对路径指定其他配置文件：

```bash
export OPENJIUWEN_API_CONFIG=/path/to/apiconfig.json
```

```powershell
$env:OPENJIUWEN_API_CONFIG = "C:\path\to\apiconfig.json"
```

未设置 `OPENJIUWEN_API_CONFIG` 时，脚本会自动使用仓库内已有的 `service/agent-service-demo/apiconfig.json`。

Linux 或 Git Bash：

```bash
bash /path/to/agent-runtime-java/service/agent-service-demo/example/a2a/smoke-a2a.sh
```

PowerShell：

```powershell
& "C:\path\to\agent-runtime-java\service\agent-service-demo\example\a2a\smoke-a2a.ps1"
```

脚本覆盖以下场景：

| 场景 | User 调用 Agent A | Agent B 后续处理 |
|---|---|---|
| `A -> B -> calc` 计算确认与恢复 | `SendMessage` | 本地 `calc` 工具 |
| `A -> B -> C` 流式确认与恢复 | `SendStreamingMessage` | `agentc-streaming` |
| `A -> B -> C` 非流式确认与恢复 | `SendMessage` | `agentc-nonstreaming` |
| `A -> B -> D` 流式审批与恢复 | `SendStreamingMessage` | `agentd-streaming` |
| `A -> B -> D` 非流式审批与恢复 | `SendMessage` | `agentd-nonstreaming` |

每个中断场景都使用原始 `contextId` 和 `taskId` 恢复任务。Agent C 的最终结果包含 `Kung Pao chicken`；Agent D 的最终结果包含费用单号和 `llm_report`。失败时脚本会输出并保留日志目录。

## 示例中的 Agent

| Agent | 端口 | 类型和职责 |
|---|---:|---|
| Agent A | 18090 | ReActAgent，接收用户请求并转交给 Agent B |
| Agent B | 18091 | ReActAgent，调用本地计算工具，或将餐饮和费用请求转交给 Agent C/D |
| Agent C | 18092 | DeepAgent，在返回餐饮推荐前请求用户确认 |
| Agent D | 18093 | WorkflowAgent，检查费用政策、执行自动或人工审批，并在人工审批后调用 LLM 生成最终报告 |

Agent B 为 Agent C 和 Agent D 分别配置了流式和非流式远端路由。Agent 间调用只有在远端路由配置 `streaming: true`，并且当前用户请求为 `SendStreamingMessage` 时才使用流式调用；任何一个条件不满足都使用非流式调用。该规则会逐跳传递到 `A -> B -> C/D`：非流式用户请求不会在下游重新开启流式调用，流式下游进度则会作为 SSE 事件透传给用户。

### 计算场景

用户只访问 Agent A。Agent A 将请求委派给 Agent B，Agent B 调用本地 `calc` 工具并中断等待确认；用户再次调用 Agent A 恢复同一个外层任务。

```text
User -> Agent A -> Agent B -> calc -> confirmation
User -> Agent A -------- resume task --------> Agent B -> result -> Agent A -> User
```

### Agent C 餐饮推荐场景

Agent A 将餐饮请求交给 Agent B，Agent B 根据用户指定的模式选择 Agent C 的流式或非流式路由。Agent C 的 `food_recommend` 工具中断等待用户确认；用户通过 Agent A 恢复原任务后，结果沿 `C -> B -> A` 返回。

```text
User -> Agent A -> Agent B -> Agent C -> food_recommend -> confirmation
User -> Agent A -> Agent B -> Agent C (resume) -> recommendation -> Agent B -> Agent A -> User
```

### Agent D 费用审核场景

Agent D 不是用户直接调用的独立工作流。完整场景从 Agent A 开始：Agent A 委派给 Agent B，Agent B 根据请求选择 Agent D 的流式或非流式路由，并把结构化费用信息交给 Agent D。

Agent D 首先按费用类别检查单价。合规费用自动审批并沿 `D -> B -> A` 返回；超标费用中断等待人工审批。用户使用最初调用 Agent A 获得的 `contextId` 和 `taskId` 再次调用 Agent A，Runtime 恢复嵌套的 `A -> B -> D` 调用，Agent D 在审批后调用真实大模型生成最终报告，再将结果逐层返回用户。

```text
User -> Agent A -> Agent B -> Agent D -> policy check
                                      |-- compliant -> auto approve -> Agent B -> Agent A -> User
                                      `-- over limit -> manual approval -> INPUT_REQUIRED -> Agent B -> Agent A -> User

User -> Agent A -> Agent B -> Agent D (resume) -> final LLM report -> Agent B -> Agent A -> User
```

示例政策单价上限为：酒店 `600 CNY/night`、餐饮 `300 CNY`、交通 `1000 CNY`、其他 `1000 CNY`。

## 其他大模型配置方式

除 `apiconfig.json` 外，也可以在仓库的 `service` 目录复制 Spring 本地配置：

```bash
cp agent-service-demo/example/config/application-base_local.example.yml \
   agent-service-demo/example/config/application-base_local.yml
```

填写 `openjiuwen.service.llm` 下的 `api-key`、`api-base` 和 `model-name`。也可以使用环境变量：

```bash
export OPENJIUWEN_SERVICE_LLM_API_KEY=...
export OPENJIUWEN_SERVICE_LLM_API_BASE=...
export OPENJIUWEN_SERVICE_LLM_MODEL_NAME=...
```

Agent A、Agent B、Agent C，以及 Agent D 超标审批分支的最终报告节点都会访问真实大模型。

## 手工启动服务

如需逐步验证，在仓库的 `service` 目录打开四个终端，并按以下顺序启动。四个终端需要使用相同的模型配置。

Agent D：

```bash
mvn -pl agent-service-demo/example/a2a -am spring-boot:run \
  -Dspring-boot.run.main-class=com.openjiuwen.service.demo.example.a2a.A2aAgentDDemoApplication
```

Agent C：

```bash
mvn -pl agent-service-demo/example/a2a -am spring-boot:run \
  -Dspring-boot.run.main-class=com.openjiuwen.service.demo.example.a2a.A2aAgentCDemoApplication
```

Agent B：

```bash
mvn -pl agent-service-demo/example/a2a -am spring-boot:run \
  -Dspring-boot.run.main-class=com.openjiuwen.service.demo.example.a2a.A2aAgentBDemoApplication
```

Agent A：

```bash
mvn -pl agent-service-demo/example/a2a -am spring-boot:run \
  -Dspring-boot.run.main-class=com.openjiuwen.service.demo.example.a2a.A2aAgentADemoApplication
```

检查四个服务和 Agent Card：

```bash
curl -sS http://localhost:18090/health | python3 -m json.tool
curl -sS http://localhost:18091/health | python3 -m json.tool
curl -sS http://localhost:18092/health | python3 -m json.tool
curl -sS http://localhost:18093/health | python3 -m json.tool

curl -sS http://localhost:18090/.well-known/agent-card.json | python3 -m json.tool
curl -sS http://localhost:18091/.well-known/agent-card.json | python3 -m json.tool
curl -sS http://localhost:18092/.well-known/agent-card.json | python3 -m json.tool
curl -sS http://localhost:18093/.well-known/agent-card.json | python3 -m json.tool
```

## 手工发送完整 A2A 请求

以下命令使用 Bash 或 Git Bash。所有业务报文都发送到 Agent A，不直接调用 Agent B、C 或 D。

非流式响应的任务 ID 位于 `result.task.id`，任务状态位于 `result.task.status.state`，响应不会包含远端 Agent 的中间流式进度。流式响应由多行 `data:` 事件组成，任务 ID 位于 `result.statusUpdate.taskId`，任务状态位于 `result.statusUpdate.status.state`；其中带有 `_remote_invocation` metadata 的 artifact 是逐跳透传的远端 Agent 进度。

首轮中断后，从响应中复制 `taskId`，将恢复报文中的 `TASK_ID_FROM_FIRST_RESPONSE` 替换为该值。中断首轮应出现 `TASK_STATE_INPUT_REQUIRED`，恢复后应出现 `TASK_STATE_COMPLETED`，并且两轮的 `taskId` 必须相同。

### 场景 1：A -> B 计算确认与恢复

首轮使用非流式 A2A 请求触发 `calc` 工具：

```bash
curl -sS -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  --data-binary @- <<'JSON' | tee /tmp/a2a-calc-1.json | python3 -m json.tool
{
  "jsonrpc": "2.0",
  "id": "calc-1",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-calc-001",
      "parts": [
        {"text": "Calculate 1+1 through Agent B. Use the calc tool and ask for confirmation."}
      ]
    }
  }
}
JSON
```

使用相同的 `contextId` 和返回的 `taskId` 恢复任务：

```bash
curl -sS -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  --data-binary @- <<'JSON' | tee /tmp/a2a-calc-2.json | python3 -m json.tool
{
  "jsonrpc": "2.0",
  "id": "calc-2",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-calc-001",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [
        {"text": "2"}
      ]
    }
  }
}
JSON
```

最终状态应为 `TASK_STATE_COMPLETED`，结果应包含 `2`。

### 场景 2：A -> B -> C 流式确认与恢复

首轮使用 SSE 调用 Agent A，并在用户消息中要求 Agent B 选择 Agent C 的流式路由：

```bash
curl -sS -N -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee /tmp/a2a-c-stream-1.txt
{
  "jsonrpc": "2.0",
  "id": "c-stream-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-c-stream-001",
      "parts": [
        {"text": "Recommend a team lunch dish through Agent C in streaming mode. Agent C must ask for confirmation."}
      ]
    }
  }
}
JSON
```

批准并恢复同一个 SSE 任务：

```bash
curl -sS -N -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee /tmp/a2a-c-stream-2.txt
{
  "jsonrpc": "2.0",
  "id": "c-stream-2",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-c-stream-001",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [
        {"text": "approved"}
      ]
    }
  }
}
JSON
```

首轮应包含 Agent C 的确认信息；恢复后应完成并包含 `Kung Pao chicken`。

### 场景 3：A -> B -> C 非流式确认与恢复

首轮请求：

```bash
curl -sS -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  --data-binary @- <<'JSON' | tee /tmp/a2a-c-nonstream-1.json | python3 -m json.tool
{
  "jsonrpc": "2.0",
  "id": "c-nonstream-1",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-c-nonstream-001",
      "parts": [
        {"text": "Recommend a team lunch dish through Agent C in non-streaming mode. Agent C must ask for confirmation."}
      ]
    }
  }
}
JSON
```

恢复请求：

```bash
curl -sS -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  --data-binary @- <<'JSON' | tee /tmp/a2a-c-nonstream-2.json | python3 -m json.tool
{
  "jsonrpc": "2.0",
  "id": "c-nonstream-2",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-c-nonstream-001",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [
        {"text": "approved"}
      ]
    }
  }
}
JSON
```

首轮应进入 `TASK_STATE_INPUT_REQUIRED`；恢复后应进入 `TASK_STATE_COMPLETED` 并包含 `Kung Pao chicken`。

### 场景 4：A -> B -> D 流式人工审批与恢复

首轮提交酒店单价高于 `600 CNY/night` 的费用，Agent D 应中断等待人工审批：

```bash
curl -sS -N -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee /tmp/a2a-d-stream-1.txt
{
  "jsonrpc": "2.0",
  "id": "d-stream-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-d-stream-001",
      "parts": [
        {"text": "Review expense claim WF-STREAM-001 through Agent D in streaming mode: category hotel, 3 nights, unit_price 1000 CNY, total 3000 CNY, currency CNY. Preserve every value exactly."}
      ]
    }
  }
}
JSON
```

批准费用并恢复同一个 SSE 任务：

```bash
curl -sS -N -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee /tmp/a2a-d-stream-2.txt
{
  "jsonrpc": "2.0",
  "id": "d-stream-2",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-d-stream-001",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [
        {"text": "approved"}
      ]
    }
  }
}
JSON
```

恢复后应进入 `TASK_STATE_COMPLETED`，结果应包含 `Agent D expense review completed`、`WF-STREAM-001`、`OVER_LIMIT`、`approved` 和 `llm_report=`。

### 场景 5：A -> B -> D 非流式人工审批与恢复

首轮请求：

```bash
curl -sS -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  --data-binary @- <<'JSON' | tee /tmp/a2a-d-nonstream-1.json | python3 -m json.tool
{
  "jsonrpc": "2.0",
  "id": "d-nonstream-1",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-d-nonstream-001",
      "parts": [
        {"text": "Review expense claim WF-NONSTREAM-001 through Agent D in non-streaming mode: category hotel, 3 nights, unit_price 1000 CNY, total 3000 CNY, currency CNY. Preserve every value exactly."}
      ]
    }
  }
}
JSON
```

恢复请求：

```bash
curl -sS -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  --data-binary @- <<'JSON' | tee /tmp/a2a-d-nonstream-2.json | python3 -m json.tool
{
  "jsonrpc": "2.0",
  "id": "d-nonstream-2",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-d-nonstream-001",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [
        {"text": "approved"}
      ]
    }
  }
}
JSON
```

恢复后应进入 `TASK_STATE_COMPLETED`，结果应包含 `Agent D expense review completed`、`WF-NONSTREAM-001`、`OVER_LIMIT`、`approved` 和 `llm_report=`。

### 场景 6：A -> B -> D 合规费用自动审批

该请求覆盖 Agent D 无需人工审批的分支。交通单价 `800 CNY` 未超过 `1000 CNY` 上限，因此一次调用应直接完成：

```bash
curl -sS -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  --data-binary @- <<'JSON' | tee /tmp/a2a-d-auto.json | python3 -m json.tool
{
  "jsonrpc": "2.0",
  "id": "d-auto-1",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-d-auto-001",
      "parts": [
        {"text": "Review expense claim WF-AUTO-001 through Agent D in non-streaming mode: category transport, 1 item, unit_price 800 CNY, total 800 CNY, currency CNY. Preserve every value exactly."}
      ]
    }
  }
}
JSON
```

结果应直接进入 `TASK_STATE_COMPLETED`，并包含 `WF-AUTO-001`、`COMPLIANT` 和 `auto-approved`，不应出现人工审批中断。

## 可选：使用 Redis 保存会话

默认使用内存存储。若希望使用 Redis 保存会话和任务状态，可创建本地配置：

```bash
cp agent-service-demo/example/a2a/application-a2a-redis.example.yml \
   agent-service-demo/example/a2a/application-a2a-redis.local.yml
```

编辑 `application-a2a-redis.local.yml`，配置单机 Redis 的 `host`、`port` 和密码，或配置 Redis Cluster 的 `type: cluster` 与 `nodes`。四个 Agent 启动时都会加载该本地配置。
