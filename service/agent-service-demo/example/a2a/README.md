# A2A Multi-Agent 示例

本示例演示四个独立 Agent 通过 A2A 协议协作，并在需要用户确认或审批时中断和恢复任务。

| Agent | 端口 | 角色 |
|---|---:|---|
| Agent A | 18090 | 用户入口，将请求转交给 Agent B |
| Agent B | 18091 | 使用本地计算工具，或将餐饮和费用请求转交给 Agent C/D |
| Agent C | 18092 | DeepAgent，在返回餐饮推荐前请求用户确认 |
| Agent D | 18093 | WorkflowAgent，执行费用政策检查、人工审批和最终 LLM 报告 |

Agent B 为 Agent C 和 Agent D 分别配置了流式和非流式远端路由。所有业务请求均发送到 Agent A 的 `/a2a/` 端点。

## 处理流程

计算请求只经过 Agent A 和 Agent B：

```text
User -> Agent A -> Agent B -> calc
```

餐饮请求由 Agent C 触发确认，恢复后返回固定的餐饮推荐：

```text
User -> Agent A -> Agent B -> Agent C -> confirmation
User -> Agent A -> Agent B -> Agent C -> recommendation
```

费用请求由 Agent D 执行政策检查。合规费用自动审批；超标费用进入人工审批，恢复后调用真实大模型生成最终报告：

```text
policy check
  |-- compliant -> auto approve -> end
  `-- over limit -> manual approval -> final LLM report -> end
```

示例政策单价上限为：酒店 `600 CNY/night`、餐饮 `300 CNY`、交通 `1000 CNY`、其他 `1000 CNY`。

## 配置大模型

Agent A、Agent B、Agent C 以及 Agent D 的最终报告节点都会访问真实大模型。可以使用以下任一种配置方式。

### apiconfig.json

设置配置文件的绝对路径：

```bash
export OPENJIUWEN_API_CONFIG=/path/to/apiconfig.json
```

PowerShell：

```powershell
$env:OPENJIUWEN_API_CONFIG = "C:\path\to\apiconfig.json"
```

未设置 `OPENJIUWEN_API_CONFIG` 时，smoke 脚本会自动使用仓库内已有的 `service/agent-service-demo/apiconfig.json`。

### Spring 本地配置

在仓库的 `service` 目录执行：

```bash
cp agent-service-demo/example/config/application-base_local.example.yml \
   agent-service-demo/example/config/application-base_local.yml
```

然后填写 `openjiuwen.service.llm` 下的 `api-key`、`api-base` 和 `model-name`。

### 环境变量

```bash
export OPENJIUWEN_SERVICE_LLM_API_KEY=...
export OPENJIUWEN_SERVICE_LLM_API_BASE=...
export OPENJIUWEN_SERVICE_LLM_MODEL_NAME=...
```

## 运行 smoke 验证

脚本会按 Agent D、Agent C、Agent B、Agent A 的顺序启动服务，检查健康状态和 Agent Card，运行测试后关闭服务。脚本可以从任意工作目录调用。

Linux 或 Git Bash：

```bash
bash /path/to/agent-runtime-java/service/agent-service-demo/example/a2a/smoke-a2a.sh
```

PowerShell：

```powershell
& "C:\path\to\agent-runtime-java\service\agent-service-demo\example\a2a\smoke-a2a.ps1"
```

脚本验证以下场景：

| 场景 | 调用 Agent A 的方法 | Agent B 的远端路由 |
|---|---|---|
| 计算确认与恢复 | `SendMessage` | 本地 `calc` |
| Agent C 流式确认与恢复 | `SendStreamingMessage` | `agentc-streaming` |
| Agent C 非流式确认与恢复 | `SendMessage` | `agentc-nonstreaming` |
| Agent D 流式审批与恢复 | `SendStreamingMessage` | `agentd-streaming` |
| Agent D 非流式审批与恢复 | `SendMessage` | `agentd-nonstreaming` |

每个中断场景都会使用原始 `contextId` 和 `taskId` 恢复任务。Agent C 的最终结果包含 `Kung Pao chicken`；Agent D 的最终结果包含费用单号和 `llm_report`。

验证成功后，脚本会清理进程和临时文件。验证失败时会输出并保留日志目录。

## 手动启动

如需逐步调试，请在仓库的 `service` 目录打开四个终端，并按以下顺序执行。

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

检查健康状态和 Agent Card：

```bash
curl -s http://localhost:18090/health
curl -s http://localhost:18091/health
curl -s http://localhost:18092/health
curl -s http://localhost:18093/health
curl -s http://localhost:18090/.well-known/agent-card.json | python3 -m json.tool
```

## 手动发送 A2A 请求

以下请求通过非流式 A2A 调用 Agent A，并触发 Agent B 的计算确认：

```bash
curl -sS -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": "calc-1",
    "method": "SendMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "contextId": "manual-calc-001",
        "parts": [{"text": "Calculate 1+1 through Agent B. Use the calc tool and ask for confirmation."}]
      }
    }
  }' | tee /tmp/a2a-calc-1.json | python3 -m json.tool
```

从响应的 `result.task.id` 取得任务 ID，然后使用相同的 `contextId` 和 `taskId` 恢复：

```bash
TASK_ID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["result"]["task"]["id"])' \
  /tmp/a2a-calc-1.json)

curl -sS -X POST http://localhost:18090/a2a/ \
  -H 'Content-Type: application/json' \
  -d "{
    \"jsonrpc\": \"2.0\",
    \"id\": \"calc-2\",
    \"method\": \"SendMessage\",
    \"params\": {
      \"message\": {
        \"role\": \"ROLE_USER\",
        \"contextId\": \"manual-calc-001\",
        \"taskId\": \"$TASK_ID\",
        \"parts\": [{\"text\": \"2\"}]
      }
    }
  }" | python3 -m json.tool
```

流式请求使用同一个 `/a2a/` 端点，将方法改为 `SendStreamingMessage`，并增加请求头 `Accept: text/event-stream`。

## 可选：使用 Redis 保存会话

默认使用内存存储。若希望使用 Redis 保存会话和任务状态，可创建本地配置：

```bash
cp agent-service-demo/example/a2a/application-a2a-redis.example.yml \
   agent-service-demo/example/a2a/application-a2a-redis.local.yml
```

编辑 `application-a2a-redis.local.yml`，配置单机 Redis 的 `host`、`port` 和密码，或配置 Redis Cluster 的 `type: cluster` 与 `nodes`。四个 Agent 启动时都会加载该本地配置。
