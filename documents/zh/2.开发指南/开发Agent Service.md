# 开发 Agent Service

本文说明如何将 Agent 封装为可部署的 **Spring Boot Agent Service** 镜像（③ Product 镜像）。

## 最小依赖

业务 `pom.xml` 典型依赖：

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-app</artifactId>
    <version>0.1.0</version>
</dependency>
<!-- 任选执行后端 -->
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-adapters-agentcore</artifactId>
    <version>0.1.0</version>
</dependency>
```

## 启动类

```java
@SpringBootApplication
public class MyAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }
}
```

引入 `agent-service-app` 后，`AgentServiceAutoConfiguration` 会自动注册 Orchestrator、Lifecycle、Controller（在配置启用时）。

## 提供 AgentHandler 的两种方式

### 方式 A：@Bean 自定义 Handler

```java
@Bean
public AgentHandler agentHandler() {
    return new MyAgentHandler();
}
```

存在 `@Bean AgentHandler` 时，默认的 `agentcore` 自动装配 **不会** 覆盖你的 Bean。

### 方式 B：使用 Core 默认 Handler

在 `application.yml` 中配置：

```yaml
openjiuwen:
  service:
    agent-id: my-registered-agent-id
    handler: agentcore   # 默认即为 agentcore
```

前提：启动前已在 Core `ResourceMgr` 中注册对应 `agent-id` 的 Agent 定义（与 Core 示例一致）。

### 方式 C：Demo 模式（直接注入 LlmAgent）

参考 `agent-service-demo`：在 `@Bean` 中构造 `JiuwenCoreAgentHandler(LlmAgent)`，适合快速验证。

## 推荐 application.yml

```yaml
server:
  port: 8090

spring:
  application:
    name: my-agent-service

openjiuwen:
  service:
    enabled: true
    version: 0.1.0
    agent-id: ${AGENT_ID:}
    query:
      enabled: true
      mvc:
        enabled: true
      webflux:
        enabled: false
      legacy-path-enabled: true
```

## Lifecycle Hook

实现 SPI 并注册为 Spring Bean：

| 接口 | 时机 |
| --- | --- |
| `AgentInitHook` | Init 阶段（Handler 加载后） |
| `AgentShutdownHook` | 优雅停机 |
| `AgentInterruptHandler` | 流式 interrupt 通知 |

详见 [生命周期与探针](生命周期与探针.md)。

## 打包 OCI 镜像

1. `mvn package` 生成可执行 jar（`spring-boot-maven-plugin`）。
2. Dockerfile 使用 JRE 17+ 运行 jar，暴露 `containerPort`（默认 8090）。
3. 健康检查指向 `GET /health`，readiness 校验 `agent_loaded`。

部署与 `url` 返回由 **Manager + 网关** 负责，不在 Agent 镜像内实现。

## 与 Demo 对照

| 项 | demo | 生产镜像 |
| --- | --- | --- |
| Handler | `DemoAgentHandler` 或 `JiuwenCoreAgentHandler` | 业务 Handler 或 `agent-id` |
| LLM 配置 | `apiconfig.json` / demo 属性 | 环境变量 / 配置中心 |
| 模块 | `agent-service-demo` | 业务 `*-service` 模块 |

参考实现：[service/agent-service-demo](../../service/agent-service-demo/README.md)。
