# Memory ReActAgent Demo

`example/memory` 只保留一个使用用例：启动一个真实 LLM 驱动的 `ReActAgent`，通过 Runtime `MemoryStore` 接入 mem0 长期记忆。

这个 demo 同时验证两条路径：

```text
请求生命周期:
POST /v1/query
  -> MemoryProvider.prefetch(...)
  -> MemoryStore.search(...)
  -> 将 <memory-context> 注入本轮请求
  -> ReActAgent + 真实 LLM
  -> MemoryProvider.syncTurn(...)
  -> MemoryStore.add(...)

模型主动工具调用:
ReActAgent
  -> memory_search / memory_add / memory_get / memory_delete
  -> MemoryStore.search/add/get/delete
```

`MemoryProvider` 的实际实现由 Runtime 自动配置为 `MemoryStoreMemoryProvider`。它只负责请求前 prefetch 和请求后 syncTurn；LLM 工具仍由 `MemoryToolRegistrar` 注册，二者职责分离。

## 目录

```text
example/memory/
  application.yml
  application-memory.yml
  application-mem0.yml
  src/main/java/.../MemoryDemoApplication.java
  src/main/java/.../MemoryLifecycleAgentHandler.java
  src/test/java/.../MemoryAgentEndToEndTest.java
```

## 配置

以下命令默认在 `agent-runtime-java` 仓库根目录执行。

### 1. 配置真实 LLM

方式一：复制本地配置文件。

```bash
cp service/agent-service-demo/example/config/application-base_local.example.yml \
   service/agent-service-demo/example/config/application-base_local.yml
```

编辑 `service/agent-service-demo/example/config/application-base_local.yml`：

```yaml
openjiuwen:
  service:
    llm:
      auto-discover: false
      provider: OpenAI
      api-key: your-api-key
      api-base: https://your-llm-endpoint/v1
      model-name: your-model
      ssl-verify: true
```

方式二：用环境变量覆盖。

```bash
export OPENJIUWEN_SERVICE_LLM_AUTO_DISCOVER=false
export OPENJIUWEN_SERVICE_LLM_PROVIDER=OpenAI
export OPENJIUWEN_SERVICE_LLM_API_KEY=your-api-key
export OPENJIUWEN_SERVICE_LLM_API_BASE=https://your-llm-endpoint/v1
export OPENJIUWEN_SERVICE_LLM_MODEL_NAME=your-model
```

### 2. 配置 mem0

`application-mem0.yml` 默认读取下面的环境变量：

```bash
export MEM0_ENDPOINT=https://api.mem0.ai
export MEM0_API_KEY=your-mem0-api-key
```

如果你使用本地或自建 mem0 兼容服务，把 `MEM0_ENDPOINT` 改成对应地址即可。
用户维度不通过配置兜底，所有请求都需要在请求体里显式传 `user_id`。

`application-memory.yml` 默认打开 request-scoped Core session：

```yaml
openjiuwen:
  service:
    middleware:
      memory:
        request-scoped-session: true
```

打开后，demo 会给 ReActAgent 传入携带 `user_id` / `space_id` / `tenant_id` 的 `AgentSessionApi`，让 `memory_search` 等工具按请求用户访问长期记忆。关闭时，card-backed agent 保持旧的 String session 行为。

## 启动

```bash
MEM0_ENDPOINT=https://api.mem0.ai \
MEM0_API_KEY=your-mem0-api-key \
mvn -pl service/agent-service-demo/example/memory -am spring-boot:run
```

服务默认监听：

```text
http://localhost:8094
```

## 手工请求

建议所有请求都显式传 `user_id`。同一个 `user_id` 会共享长期记忆；`conversation_id` 只表示本次会话。
mem0 Cloud 的用户记忆建议先按 `user_id` 维度检索；这个 demo 不提供默认 `agent_id` 配置，因此不会把 agent filter 加到 mem0 search 请求里。

### 1. 请求后自动写入记忆

这条请求即使模型没有主动调用 `memory_add`，demo 也会在响应后通过 `MemoryProvider.syncTurn` 写入本轮 user/assistant 对话。

```bash
curl -s http://localhost:8094/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "memory-demo-c1",
    "user_id": "alice",
    "message": "请记住：我喜欢喝拿铁，回答时简单确认。",
    "stream": false
  }'
```

### 2. 请求前自动 prefetch

这条请求进入 Agent 前会先调用 `MemoryProvider.prefetch`，底层进入 `MemoryStore.search`，然后把结果作为 `<memory-context>` 注入本轮 user message。

```bash
curl -s http://localhost:8094/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "memory-demo-c2",
    "user_id": "alice",
    "message": "我喜欢喝什么？请根据你知道的长期记忆回答。",
    "stream": false
  }'
```

### 3. 让模型主动搜索记忆

```bash
curl -s http://localhost:8094/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "memory-demo-c3",
    "user_id": "alice",
    "message": "请使用 memory_search 查询我的咖啡偏好。",
    "stream": false
  }'
```

### 4. 让模型主动新增记忆

```bash
curl -s http://localhost:8094/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "memory-demo-c4",
    "user_id": "alice",
    "message": "请使用 memory_add 记录：我周末喜欢喝手冲咖啡。",
    "stream": false
  }'
```

### 5. 查询单条记忆

先通过 `memory_search` 的结果拿到 `memory_id`，再请求：

```bash
curl -s http://localhost:8094/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "memory-demo-c5",
    "user_id": "alice",
    "message": "请使用 memory_get 查看 memory_id 为 YOUR_MEMORY_ID 的记录。",
    "stream": false
  }'
```

### 6. 删除单条记忆

```bash
curl -s http://localhost:8094/v1/query \
  -H 'Content-Type: application/json' \
  -d '{
    "conversation_id": "memory-demo-c6",
    "user_id": "alice",
    "message": "请使用 memory_delete 删除 memory_id 为 YOUR_MEMORY_ID 的记录。",
    "stream": false
  }'
```

## 验证点

- 请求前自动调用 `MemoryProvider.prefetch`，最终进入 mem0 `search`
- prefetch 结果被注入为 `<memory-context>`
- 请求后自动调用 `MemoryProvider.syncTurn`，最终进入 mem0 `add`
- LLM 可主动调用 `memory_search`
- LLM 可主动调用 `memory_add`
- LLM 可主动调用 `memory_get`
- LLM 可主动调用 `memory_delete`

## 自动化测试

E2E 测试使用 mock LLM 和 mock mem0，不依赖真实外部服务：

```bash
mvn -pl service/agent-service-demo/example/memory -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=MemoryAgentEndToEndTest \
  test
```

测试覆盖：

- 请求前 prefetch 是否调用 mem0 search
- 模型收到的 user message 是否包含 `<memory-context>`
- 请求后 syncTurn 是否调用 mem0 add
- 四个工具函数是否分别触发 mem0 search/add/get/delete
