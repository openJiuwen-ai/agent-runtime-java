# a2a.catalog

`com.openjiuwen.service.app.a2a.catalog` 提供可供 Runtime 适配器复用的远端 A2A Agent 目录。

## 类型

| Type | Description |
| --- | --- |
| `A2ARemoteAgentCardRegistry` | 线程安全地保存远端 Agent Card、调用超时与调用模式，并发布目录更新事件。 |
| `RemoteAgentEntry` | 保存单个远端 Agent 的名称、Agent Card、调用超时与调用模式。 |
| `RemoteAgentCatalogSnapshot` | 保存版本号和全量远端 Agent 条目的不可变快照。 |
| `RemoteAgentCatalogChangedEvent` | 远端 Agent 目录更新后发布的全量快照事件。 |

## 源码路径

`service/agent-service-app/src/main/java/com/openjiuwen/service/app/a2a/catalog/`
