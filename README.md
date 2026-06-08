# agent-runtime-java

Java 高码 Agent Runtime：Agent Service（`service/`）+ Agent Core（`vendor/agent-core-java`）。

## Agent Service Maven 模块

```text
service/
├── agent-service-spec       ← 契约 + SPI（paths / dto / spi，纯 Java）
├── agent-service-adapters   ← Adapters：AgentFW / Middleware / ExternalSvc
└── agent-service-app        ← AgentApp：Controller + Orchestrator + SpringBoot
```

依赖方向：`app` → `adapters` → `spec`。
