# 多 Agent 实例托管

本文说明如何仅使用 **Agent Runtime Java** 在一个 Spring Boot 进程中注册和托管多个 Agent 实例。本文只依赖 Runtime 的 `AgentHandler`、`HostedAgentDefinitions` 及其 HTTP/A2A 入口，不依赖任何业务 Solution 或特定 Agent 扩展实现。

## 适用场景

默认情况下，一个 Runtime 应用可以提供一个 `AgentHandler`。当一个进程需要同时承载多个相互独立的 Agent 执行入口时，可以启用托管模式：

- 启动期注册多个已有的 `AgentHandler`；
- 为每个 Handler 分配稳定的 `agentId`；
- 通过标准 REST body 或 A2A URL 选择目标实例；
- 为每个实例生成独立的 Agent Card 和运行时依赖；
- 由 Runtime 统一管理实例启动、停止、失败回滚和进程关闭。

托管模式是启动期固定的注册模式，不支持运行中动态新增、删除或替换实例。

## 最小依赖

业务应用只需要引入 Runtime 的应用模块，并提供 `AgentHandler` 实现：

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-app</artifactId>
    <version>${agent-runtime.version}</version>
</dependency>
```

`AgentHandler` 是 Runtime 的 SPI。Handler 可以是业务自定义实现，也可以来自其他执行适配模块；本文不要求某个具体执行引擎。

## 注册多个实例

将多个 Handler 注册到一个 `HostedAgentDefinitions` Bean 中：

```java
@Bean
HostedAgentDefinitions hostedAgents(
        @Qualifier("workflowHandler") AgentHandler workflow,
        @Qualifier("dataHandler") AgentHandler data,
        @Qualifier("assistantHandler") AgentHandler assistant) {
    return HostedAgentDefinitions.builder()
            .add("workflow", workflow)
            .add("data", data)
            .add("assistant", assistant)
            .defaultAgent("assistant")
            .build();
}
```

`HostedAgentDefinitions` 的规则如下：

| 规则 | 说明 |
|---|---|
| `agentId` | 只能包含字母、数字、下划线和连字符，且必须以字母或数字开头 |
| 注册顺序 | 保留注册顺序，用于实例清单展示和未显式指定默认实例时的默认选择 |
| 默认实例 | 可以通过 `defaultAgent` 显式指定；未指定时使用第一个注册实例 |
| 重复 ID | 同一个 ID 不能重复注册 |
| Handler 复用 | 同一个 Handler 对象不能注册为多个实例；Runtime 按对象身份校验 |
| 空集合 | 至少注册一个 Handler |
| 生命周期 | Runtime 借用已注册 Handler，不创建或复制 Handler 对象 |

Handler Bean 仍然可以单独提供：

```java
@Bean
AgentHandler assistantHandler() {
    return new MyAgentHandler();
}
```

只提供一个 `AgentHandler` 时，可以继续使用原单实例模式；如果使用 `HostedAgentDefinitions`，也可以只注册一个 Handler，但它会进入托管模式并使用注册 ID 作为实例标识。

## 默认实例与入口选择

默认实例只用于没有明确实例选择的入口。它不会改变具名实例的路由，也不会把不同实例的状态合并到默认实例。

### 标准 REST

标准 Query 请求通过 body 中的 `agent_id` 选择实例：

```json
{
  "agent_id": "data",
  "conversation_id": "conversation-1",
  "message": "查询数据",
  "stream": false
}
```

选择规则：

- 缺省 `agent_id` 或显式 `null`：使用默认实例；
- 合法且已注册的 `agent_id`：使用指定实例；
- 空字符串、空白字符串或未知 ID：返回请求错误，不回退默认实例；
- `agent_id` 为数字、布尔值、数组或对象：按严格 DTO 规则返回请求错误；
- `agent_id` 位于请求 body，不能用 query 参数覆盖 body 中的选择结果。

标准 REST 入口仍使用 Runtime 原有路径，例如 `/v1/query`、`/query` 和启用时的 reactive Query 入口。会话和任务必须继续使用目标实例原有的状态依赖。

### A2A

A2A 使用 URL 路径选择实例，不在 JSON-RPC 请求体中增加实例字段：

```text
POST /a2a/agents/data
```

实例路径承载原有的 A2A 方法，包括 `SendMessage`、`SendStreamingMessage`、`GetTask` 和订阅/恢复相关入口。未使用实例路径的旧 `/a2a` 入口继续指向默认实例。

## Agent Card 与实例清单

托管模式提供只读实例清单：

```text
GET /a2a/agents
```

响应包含已发布的注册 ID 和默认实例：

```json
{
  "agents": ["workflow", "data", "assistant"],
  "defaultAgent": "assistant"
}
```

具名实例的 Agent Card 使用实例路径：

```text
GET /a2a/agents/data/.well-known/agent-card.json
```

Card 中的 JSON-RPC 地址指向同一个实例路径。客户端应先读取 Card，再使用 Card 返回的地址调用；不要根据 Card 的展示名称自行拼接实例路径。

实例清单和 Card 只描述已经注册并发布的实例，不会根据配置自动创建 Handler，也不会返回 Handler 对象、内部存储配置或其他内部信息。

## 实例级 Card 配置

全局 A2A 配置位于 `openjiuwen.service.a2a`。托管实例的 Card 和远端目录配置位于 `openjiuwen.service.a2a.agents.<agentId>`：

```yaml
openjiuwen:
  service:
    a2a:
      agent-description: Runtime service
      json-rpc-path: /a2a
      public-url: https://runtime.example.com
      skills:
        - id: common-search
          name: Common Search
          description: Shared search capability
      agents:
        data:
          agent-name: Data Agent
          agent-description: Data query service
          skills:
            - id: data-query
              name: Data Query
              description: Query data
```

实例 Card 只允许覆盖 Card 业务字段。端口、线程池、TaskStore、共享 Runner、连接和其他基础设施配置仍由进程级配置管理，不能在实例 Card 下重复配置。

## 实例级远端 Agent 目录

全局远端目录对所有托管实例生效：

```yaml
openjiuwen:
  service:
    a2a:
      remote-agents:
        - name: remote-search
          url: https://search.example.com
          timeout-seconds: 300
```

某个实例可以在自己的配置下追加远端目标，或按名称覆盖全局目标：

```yaml
openjiuwen:
  service:
    a2a:
      agents:
        data:
          remote-agents:
            - name: remote-search
              url: https://data-search.example.com
            - name: local-helper
              url: http://127.0.0.1:8090/a2a/agents/helper
```

合并规则：

- 实例级同名条目覆盖全局条目；
- 实例级新增名称追加到该实例目录；
- 未在实例级配置的名称继续继承全局目录；
- 一个实例的覆盖不会影响其他实例；
- 目录中的 URL 是 A2A 目标基址，Runtime 通过 A2A Client 按 HTTP 调用；
- 同进程实例之间也可以通过各自的 A2A Card/URL 互调，但应避免自调用和循环委派。

## 生命周期和资源边界

Runtime 在启动阶段完成注册校验、依赖装配和实例发布，然后启动各实例 Handler。启动失败时按逆序回滚已经启动的实例；停止某个实例的清理异常不会阻断其他实例的清理。

进程关闭时：

- 所有已注册实例都参与关闭流程；
- 使用统一的关闭截止时间，不为每个实例重新计算完整超时；
- 共享 Runner、调度池和其他进程级资源只由进程所有者关闭一次；
- Handler 使用其原有的 `start`/`stop` 生命周期，不会因为注册而复制对象或重复关闭 Bean；
- 关闭日志使用注册 ID 标识实例，但不输出异常消息或堆栈等敏感内容。

## 单实例兼容与限制

以下方式继续有效：

```java
@Bean
AgentHandler agentHandler() {
    return new MyAgentHandler();
}
```

未提供 `HostedAgentDefinitions` 时，Runtime 使用原单 Agent 装配和入口语义。使用托管模式时，不应再同时声明会绕过托管装配的全局执行根组件；冲突配置会在启动阶段拒绝。

本能力不包含：

- 运行中动态新增、删除和替换实例；
- 租户路由或平台级 Agent 目录；
- 每个实例独立的线程池、连接池或资源配额；
- 自动改写 Core 的 Agent ID、conversationId 或 sessionId；
- 自动保证 Core 会话级状态在所有情况下按 Runtime 实例完全隔离；
- Gateway、RDC、Event-bus 等平台组件的集成改造。

## 最小调用示例

假设注册了 `workflow` 和 `data` 两个实例，默认实例是 `workflow`：

```bash
# 使用默认实例
curl -X POST http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"c1","message":"hello","stream":false}'

# 选择 data 实例
curl -X POST http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"agent_id":"data","conversation_id":"c2","message":"query","stream":false}'

# 通过 A2A Card 发现并调用 data 实例
curl http://localhost:8090/a2a/agents/data/.well-known/agent-card.json
curl -X POST http://localhost:8090/a2a/agents/data \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{"role":"ROLE_USER","parts":[{"text":"hello"}],"messageId":"m1"}}}'
```

## 延伸阅读

- [开发 Agent Service](开发Agent%20Service.md) — 单实例服务接入与 Handler SPI
- [A2A 开发指导](A2A/开发指导.md) — A2A 协议、Card、远端调用与 callback
- [HTTP 对话面](HTTP对话面.md) — Runtime REST Ingress
- [生命周期与探针](生命周期与探针.md) — Runtime 生命周期和中断语义
