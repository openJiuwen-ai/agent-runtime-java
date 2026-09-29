# DeepAgent 示例

默认使用内存 Checkpointer，Todo 工具默认关闭。设置 `demo.deepagent.todo-enabled=true` 可开启 Todo 工具。
示例只构造 Agent 并交给 Handler，不指定 Todo 存储；当 Runtime 配置 Redis Checkpointer 时，Todo 自动复用该 Redis 和有效 TTL。

## Redis 与真实模型端到端验证

先安装包含本特性的 Core 0.1.16。本测试启动真实 HTTP 服务，调用真实模型生成 Todo，随后通过 SSE 查询，并从 Redis 检查 Todo、检查点数据及 TTL。不会使用模拟模型替代，也不会在 Redis 不可用时跳过。

在仓库根目录运行：

```bash
export OPENJIUWEN_API_CONFIG=/absolute/path/to/apiconfig.json
export OPENJIUWEN_TODO_REDIS_E2E=true
# 可选：OPENJIUWEN_SERVICE_LLM_MODEL_NAME 覆盖配置文件中的模型名。
# Redis 默认 127.0.0.1:6379；可设置 REDIS_IP、REDIS_PORT、REDIS_PASSWORD。
mvn -pl service/agent-service-demo/example/deepagent -am \
  -Dtest=DeepAgentRedisTodoIT -Dsurefire.failIfNoSpecifiedTests=false test
```

模型需要支持工具调用。测试使用唯一会话标识，只清理本次会话的数据。不设置 `OPENJIUWEN_TODO_REDIS_E2E` 时不会执行外部集成测试。

A2A 示例的 DeepAgent 默认关闭 Todo，因此 A2A 使用 Redis 可验证检查点链路，但不能代替本用例。
