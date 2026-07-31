# Agent Service（`service/`）

**② Agent Service** Maven 聚合：逻辑架构中的 **Agent Server** 数据面（Agent-App + Adapters 胶水），将单 Agent 封装为 HTTP
可部署服务。完整逻辑架构见 [逻辑架构](../documents/zh/2.开发指南/逻辑架构.md)。

上级文档：[仓库 README](../README.zh.md) · [开发指南](../documents/zh/2.开发指南/README.md)

## 模块树

```text
service/                              packaging=pom · agent-service
├── agent-service-spec                契约 + SPI（纯 Java）
├── agent-service-adapters/             Adapters 聚合
│   ├── agent-service-adapters-common   中间件客户端、凭证、外部调用 DFX（引擎无关）
│   └── agent-service-adapters-agentcore  AgentHandler + Core 中间件/外部服务绑定
├── agent-service-app                 Controller + Orchestrator + Lifecycle + AutoConfig
└── agent-service-demo                可运行示例
```

## 依赖关系

```text
agent-service-app
  → agent-service-adapters-*（业务镜像选择）
    → agent-service-spec
```

`agent-service-app` **主代码**不硬依赖 `adapters-agentcore`；由 demo 或产品镜像引入具体 adapter。

## 构建与测试

在 **`service/` 目录**执行：

```bash
mvn clean test
```

构建单个模块（含依赖 `-am`）：

```bash
mvn -pl agent-service-app -am clean test
mvn -pl agent-service-demo -am spring-boot:run
```

## 各模块说明

| 模块                     | 说明                                                      | 文档                                                                                |
|------------------------|---------------------------------------------------------|-----------------------------------------------------------------------------------|
| **spec**               | 路径、DTO、`AgentHandler` / `ServeOrchestrator` SPI         | [spec.README](../documents/zh/2.开发指南/API文档/com.openjiuwen.service/spec.README.md) |
| **adapters-common**    | 中间件（Redis 等）、凭证解密、外部调用 DFX                              | 包内 `package-info`                                                                 |
| **adapters-agentcore** | `JiuwenCoreAgentHandler`；Checkpointer/MCP/远端/Sandbox 注册 | [Adapters 与 Handler](../documents/zh/2.开发指南/Adapters与Handler.md)                  |
| **app**                | Ingress Controller、默认 Orchestrator、Lifecycle            | [HTTP 对话面](../documents/zh/2.开发指南/HTTP对话面.md)                                     |
| **demo**               | 最小 Spring Boot 示例                                       | [agent-service-demo/README.md](agent-service-demo/README.md)                      |

## 与 Agent Core 的关系

执行内核为 **agent-core-java**（独立仓库，版本见 [Agent Core 依赖](../../documents/zh/2.开发指南/Agent Core 依赖.md)）。Service 层通过 `adapters-agentcore` 调用 `Runner`，不重复实现图执行引擎。

## 版本

与仓库根 `agent-runtime-java` 版本一致（当前 `0.1.1`）。
