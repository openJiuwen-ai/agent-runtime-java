# 开发 Agent Service

本文说明如何将 Agent 封装为可部署的 **Spring Boot Agent Service** 业务镜像：依赖装配、Handler 选型、配置分层与打包要点。

前置阅读：[快速开始](快速开始.md)、[架构概述](架构概述.md)。中间件与外部 egress 配置详见 [Adapters 与 Handler](Adapters与Handler.md)，勿在业务模块重复堆砌 YAML。

## 最小依赖

业务 `pom.xml` 典型依赖：

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-app</artifactId>
    <version>0.1.1</version>
</dependency>
<!-- 执行后端 + 中间件/外部 egress（默认 Agent Core leaf） -->
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-adapters-agentcore</artifactId>
    <version>0.1.1</version>
</dependency>
```

`agent-service-app` 主代码 **不硬依赖** `adapters-agentcore`；由业务镜像显式引入所需 adapter leaf。

## 启动类

```java
@SpringBootApplication
public class MyAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }
}
```

引入 `agent-service-app` 后，Spring Boot 自动加载：

| AutoConfiguration | 职责 |
| --- | --- |
| `AgentServiceAutoConfiguration` | Lifecycle、默认 Orchestrator、Query Controller（按配置）、`/health` |
| `A2AAutoConfiguration` | 进程内 A2A（按 `openjiuwen.service.a2a`） |
| `AgentCoreAdaptersAutoConfiguration` | 引入 agentcore leaf 时：Handler、middleware/external 注册器 |

Controller 包由 `@ComponentScan("com.openjiuwen.service.app.controller")` 扫描。

## 提供 AgentHandler 的三种方式

### 方式 A：`@Bean` 自定义 Handler

```java
@Bean
AgentHandler agentHandler() {
    return new MyAgentHandler();
}
```

存在 `@Bean AgentHandler` 时，`AgentCoreAdaptersAutoConfiguration` **不会** 再装配默认 `JiuwenCoreAgentHandler`。

### 方式 B：配置 `agent-id`（自动装配 Core Handler）

```yaml
openjiuwen:
  service:
    agent-id: my-registered-agent-id
    handler: agentcore   # 默认即为 agentcore
```

条件：`agent-id` **非空** 且 `handler=agentcore`，且无自定义 `@Bean AgentHandler`。Handler 通过 `agent-id` 从 `Runner.resourceMgr()` 加载 Agent。

### 方式 C：Demo 模式（直接注入 `LlmAgent`）

参考 `agent-service-demo`：通过 `LlmConfigResolver` 解析 `openjiuwen.service.llm`，并在 `@Bean` 中构造 `JiuwenCoreAgentHandler`，适合本地 LLM 联调。

## 配置分层

| 前缀 | 文档 | 说明 |
| --- | --- | --- |
| `openjiuwen.service` | 本文 | `agent-id`、`version` |
| `openjiuwen.service.query` | [HTTP 对话面](HTTP对话面.md) | Query / reset Controller 开关 |
| `openjiuwen.service.lifecycle` | [生命周期与探针](生命周期与探针.md) | 启停、fail-fast、shutdown 超时 |
| `openjiuwen.service.middleware` | [Adapters 与 Handler](Adapters与Handler.md) | Checkpointer、Redis |
| `openjiuwen.service.external` | [Adapters 与 Handler](Adapters与Handler.md) | MCP、Remote、Sandbox |
| `openjiuwen.service.a2a` | [A2A 开发指导](A2A/开发指导.md) | Agent Card、TaskStore、远端 Agent 目录 |

## 推荐 `application.yml`（骨架）

仅包含 **Service 层通用项**；中间件 / 外部 / A2A 片段见 Adapters 与 A2A 文档。

```yaml
server:
  port: 8090

spring:
  application:
    name: my-agent-service

openjiuwen:
  service:
    version: 0.1.1
    agent-id: ${AGENT_ID:}          # 方式 B；方式 A/C 可留空
    query:
      enabled: true
      mvc:
        enabled: true
      webflux:
        enabled: false               # WebFlux 流式时再开
      legacy-path-enabled: true
    lifecycle:
      shutdown-timeout-ms: 30000   # Shutdown 阶段等待活动流结束的上限
      init-fail-fast: true           # Init 失败是否阻止启动
```

### Spring Profile 示例（Demo 特性模块）

共用基础配置：`agent-service-demo/example/config/application-base.yml`。各特性为**独立可运行子模块**：

| 模块 | Profile / 配置 | 用途 |
| --- | --- | --- |
| `agent-service-demo-redis` | `redis-checkpointer` · `example/redis/application-redis-checkpointer.yml` | Redis Checkpointer |
| `agent-service-demo-mcp` | `mcp` · `example/mcp/application-mcp.yml` | MCP 出站 |
| `agent-service-demo-sandbox` | `sandbox` · `example/sandbox/application-sandbox.yml` | Sandbox |
| （转测） | `src/test/resources/application-a2a-remote.yml` | 出站 Remote（A2A） |

启动示例：`mvn -pl agent-service-demo-redis -am spring-boot:run`（需 `apiconfig.json` 时见 `example/redis/README.md`）。

## Lifecycle Hook

实现 SPI 并注册为 Spring Bean（详见 [生命周期与探针](生命周期与探针.md)）：

| 接口 | 时机 |
| --- | --- |
| `AgentInitHook` | Init：在 `AgentHandler.start()` 之前 |
| `AgentShutdownHook` | Shutdown：在 `AgentHandler.stop()` 之后 |
| `AgentInterruptHandler` | 进程内 `interrupt(conversationId)` 之后 |
| `AgentReadiness` | 自定义 `agent_loaded` 逻辑（可选） |

InitHook 支持 `@Order`。

## 打包 OCI 镜像

1. `mvn package` 生成可执行 jar（`spring-boot-maven-plugin`）。
2. Dockerfile 使用 JRE 17+ 运行 jar，暴露端口（默认 8090）。
3. **liveness**：`GET /health`，校验 `process_up`。
4. **readiness**：`GET /health`，校验 `agent_loaded`。

对外访问 URL 由 **Manager + 网关** 登记，Agent 镜像 **不自注册**（见 [架构概述 · 与 Manager 的关系](架构概述.md#5-与-managergateway-的关系)）。

## 与 Demo 对照

| 项 | demo | 生产镜像 |
| --- | --- | --- |
| Handler | `DemoAgentHandler` 或 `JiuwenCoreAgentHandler(LlmAgent)` | `@Bean` 或 `agent-id` |
| LLM | `apiconfig.json` / `openjiuwen.service.llm` | 环境变量 / 配置中心 |
| 中间件 / 外部 | profile + `example/*` | `middleware` / `external` 配置 |
| Maven 模块 | `agent-service-demo` | 业务 `*-service` 模块 |

参考实现：[service/agent-service-demo](../../../service/agent-service-demo/README.md)。

## 延伸阅读

- [HTTP 对话面](HTTP对话面.md) — Ingress API
- [Adapters 与 Handler](Adapters与Handler.md) — 中间件与外部 egress
- [A2A 开发指导](A2A/开发指导.md) — 进程内 A2A
- [生命周期与探针](生命周期与探针.md) — 启停与 interrupt
