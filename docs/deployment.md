# 部署说明

本文只说明当前 Java 版 `agent-runtime-java` 的部署方式。

## 前置依赖

| 依赖 | 说明 |
|---|---|
| JDK | 当前 Maven 构建配置使用 Java 17 |
| Maven | 用于构建 `a2a_service` 和 `versatile_adapter` |
| Redis | 必需，用于会话、task、checkpoint |
| LLM 网关 | OpenAI 兼容接口 |
| Versatile 服务 | `versatile_adapter` 的真实上游 |
| MCP 服务 | 仅 MCP-first 理财推荐链路需要 |

## 初始化

初始化 submodule：

```bash
git submodule update --init --recursive
```

生成本地配置：

```bash
scripts/deploy.sh init-config
vim config/deploy.properties
```

至少需要替换这些配置：

```properties
REDIS_START_MODE=external
REDIS_HOST=<redis-host>
REDIS_PORT=6379
REDIS_DB=0
REDIS_PASSWORD=XXX

VERSATILE_URL_TEMPLATE=http://<versatile-host>/v1/<project_id>/agents/<agent_id>/conversations/{conversation_id}
VERSATILE_ADAPTER_URL=http://<versatile-adapter-host>:8081/
VA_WORKFLOW_RESULT_NODE=GXZQAResponseNode

LLM_PROVIDER=OpenAI
LLM_API_BASE=<openai-compatible-base-url>
LLM_MODEL_NAME=<model-name>
LLM_API_KEY=<api-key>
```

说明：

- `VERSATILE_ADAPTER_URL` 填 `a2a_service` 可访问到的 `versatile_adapter` 地址
- 旧工作流接口继续使用 `{conversation_id}` 路径模板；新 `agentConversationStream.htm` 接口直接把 `VERSATILE_URL_TEMPLATE` 配成固定 POST 地址
- `VA_WORKFLOW_RESULT_NODE` 默认是 `GXZQAResponseNode`，如果真实工作流节点名不同，需要改成实际值

## 构建与启动

构建：

```bash
scripts/deploy.sh build
```

启动：

```bash
scripts/deploy.sh start
```

查看状态：

```bash
scripts/deploy.sh status
```

停止：

```bash
scripts/deploy.sh stop
```

日志和 pid 位于：

```text
runtime/logs/
runtime/pids/
```

## 验证

健康检查：

```bash
scripts/deploy.sh probe
```

调用示例：

```bash
scripts/invoke.sh --query "帮我查一下账户余额"
scripts/invoke.sh --query "推荐两款低风险理财产品"
```

也可以直接调用北向入口：

```bash
curl -N -H 'Content-Type: application/json' \
  -X POST 'http://<a2a-host>:8080/v1/demo/agents/edp_agent/conversations/<conversation-id>' \
  -d '{
    "agent_id": "edp_agent",
    "conversation_id": "<conversation-id>",
    "stream": true,
    "custom_data": {
      "inputs": {
        "query": "帮我查一下账户余额"
      }
    }
  }'
```

## 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `scripts/deploy.sh start` 提示缺配置 | 占位值未替换 | 补齐 Redis、LLM、Versatile 相关配置 |
| Maven 构建报 submodule 缺失 | submodule 未初始化 | 执行 `git submodule update --init --recursive` |
| `a2a_service` 启动失败 | Redis 或 LLM 配置不可用 | 查看 `runtime/logs/a2a_service.log` |
| `versatile_adapter` 启动失败 | 端口占用或上游配置错误 | 查看 `runtime/logs/versatile_adapter.log` |
| 无 `workflow_result` | `VA_WORKFLOW_RESULT_NODE` 配错 | 用真实工作流返回确认 QA 节点名 |
| 分机部署后 a2a 调不到 VA | `VERSATILE_ADAPTER_URL` 不可达 | 改成 `a2a_service` 可访问的地址 |
