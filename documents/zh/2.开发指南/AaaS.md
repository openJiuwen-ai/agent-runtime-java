# AaaS

**AaaS**（Agent as a Service）指把一个 Agent 从代码对象封装成可部署、可调用、可观测、可运维的服务。
在 `agent-runtime-java` 中，AaaS 的主要落点是 **Agent Service**：一个 Spring Boot 进程承载一个 Agent，对外暴露 HTTP / A2A 对话入口，对内通过 `AgentHandler` 接入 Agent Core 或其他执行后端。

本文定位为概念说明和使用教程。接口字段、生命周期、A2A 协议等细节请按文末链接继续阅读。

## 1. 为什么需要 AaaS

只在本地代码里创建 Agent 时，调用方需要关心模型配置、Runner 生命周期、会话状态、流式输出和异常处理。
AaaS 把这些能力收敛到一个服务边界内：

```text
Client / Gateway
  -> Agent Service
      -> ServeOrchestrator
          -> AgentHandler
              -> Agent Core Runner / 其他执行后端
```

这样调用方只需要通过稳定的协议发起请求，Agent 的内部实现可以独立演进。

在 Java Runtime 中，AaaS 主要解决四类问题：

| 问题 | AaaS 的处理方式 |
| --- | --- |
| 如何调用 Agent | 暴露 `/v1/query`、流式 SSE、`/health`、`/reset_conversation` 等数据面接口 |
| 如何接入不同执行后端 | 通过 `AgentHandler` SPI 适配 Agent Core、远端服务或自定义引擎 |
| 如何管理生命周期 | Spring Boot 自动装配 init、shutdown、readiness、活动流中断 |
| 如何部署到平台 | 业务镜像打成 OCI，部署、路由、服务注册交给 Manager / Gateway 等平台能力 |

## 2. 本仓库中的 AaaS 边界

`agent-runtime-java` 当前主交付是 **单 Agent 数据面服务**，也就是逻辑架构里的 **Agent Server**。它不是完整的平台控制面。

| 能力 | 本仓状态 | 说明 |
| --- | --- | --- |
| Agent Service | 已实现 | `service/` 模块，提供 HTTP Query、health、reset、生命周期 |
| Agent Core 执行 | 已接入 | 通过 `agent-service-adapters-agentcore` 调用 `agent-core-java` |
| 进程内 A2A | 已实现 | Agent Card、JSON-RPC、远端 Agent 调用见 A2A 专篇 |
| Deploy Manager | 规划中 | 负责部署、实例记录、服务发现、平台路由 |
| 完整 Gateway | 平台侧能力 | 负责鉴权、统一入口、sticky、跨服务路由等 |

因此，写一个 Java AaaS 服务时，你主要关心三件事：

1. 选择或实现一个 `AgentHandler`。
2. 引入 `agent-service-app`，让它提供 Controller、Orchestrator 和 Lifecycle。
3. 配置端口、Agent 标识、Query、A2A、外部服务等运行参数。

## 3. 模块组成

`service/` 是 AaaS 的核心模块树：

```text
service/
├── agent-service-spec              # 契约：paths / dto / spi
├── agent-service-app               # Controller + Orchestrator + Lifecycle + AutoConfig
├── agent-service-adapters/
│   ├── agent-service-adapters-common
│   └── agent-service-adapters-agentcore
└── agent-service-demo              # 最小可运行示例
```

各模块职责如下：

| 模块 | 职责 |
| --- | --- |
| `agent-service-spec` | 定义路径常量、DTO、`AgentHandler`、`ServeOrchestrator` 等 SPI |
| `agent-service-app` | 提供 HTTP / A2A Controller、默认编排器、生命周期、自动装配 |
| `agent-service-adapters-agentcore` | 提供 `JiuwenCoreAgentHandler`，把 Service 接到 Core `Runner` |
| `agent-service-demo` | 提供 mock handler、真实 LLM/Core 链路和 smoke 示例 |

核心调用链保持一个不变量：

```text
Controller -> ServeOrchestrator -> AgentHandler -> 执行后端
```

Controller 不直接调用 Runner；Runner、远端 HTTP 或其他执行引擎都应该藏在 `AgentHandler` 后面。

## 4. 快速跑通一个 AaaS 服务

### 4.1 前置条件

| 项 | 要求 |
| --- | --- |
| Java | 17+ |
| Maven | 3.9+ |
| agent-core-java | `com.openjiuwen:agent-core-java:0.1.12` |

如果本地 Maven 仓库还没有 `agent-core-java`，先安装 Core：

```bash
git clone https://gitcode.com/openJiuwen/agent-core-java.git
cd agent-core-java
git checkout 0.1.12
mvn clean install -DskipTests
```

### 4.2 构建 Runtime

```bash
git clone <repository-url>
cd agent-runtime-java
mvn clean install -DskipTests
```

只构建并测试 Service 层：

```bash
cd service
mvn clean test
```

### 4.3 启动 Demo

从 `agent-runtime-java/service` 启动：

```bash
mvn -pl agent-service-demo -am spring-boot:run
```

默认端口是 `8090`。没有真实大模型配置时，demo 使用 mock handler，返回稳定的 `demo:<message>`。

强制使用 mock：

```bash
mvn -pl agent-service-demo -am spring-boot:run \
  "-Dspring-boot.run.arguments=--openjiuwen.demo.llm.enabled=false"
```

### 4.4 检查健康状态

```bash
curl -s http://localhost:8090/health
```

典型响应：

```json
{
  "status": "healthy",
  "app": "demo-agent-service",
  "version": "0.1.0",
  "process_up": true,
  "agent_loaded": true
}
```

生产环境建议：

| 探针 | 判断 |
| --- | --- |
| liveness | `process_up == true` |
| readiness | `agent_loaded == true` |

### 4.5 发起第一条 Query

非流式：

```bash
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1","message":"hello","stream":false}'
```

mock handler 下返回形态类似：

```json
{
  "result": {
    "role": "assistant",
    "content": "demo:hello",
    "conversation_id": "demo-c1"
  },
  "conversation_id": "demo-c1"
}
```

流式 SSE：

```bash
curl -N http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1","message":"stream hello","stream":true}'
```

重置会话：

```bash
curl -s http://localhost:8090/v1/reset_conversation \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"demo-c1"}'
```

## 5. 开发自己的 AaaS 服务

### 5.1 引入依赖

业务服务通常引入 `agent-service-app` 和一个具体 adapter：

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-app</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-adapters-agentcore</artifactId>
    <version>0.1.0</version>
</dependency>
```

`agent-service-app` 提供服务框架；`agent-service-adapters-agentcore` 提供默认 Core Handler。
如果你完全自定义执行后端，也可以只实现自己的 `AgentHandler`。

### 5.2 创建启动类

```java
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MyAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }
}
```

引入 `agent-service-app` 后，`AgentServiceAutoConfiguration` 会自动注册 Controller、生命周期、就绪状态和默认编排能力。

### 5.3 方式一：使用 Agent Core 默认 Handler

适合高码 `WorkflowAgent`、`LlmAgent` 或已经注册到 Core `ResourceMgr` 的 Agent。

```yaml
server:
  port: 8090

spring:
  application:
    name: my-agent-service

openjiuwen:
  service:
    version: 0.1.0
    handler: agentcore
    agent-id: my-registered-agent-id
    query:
      mvc:
        enabled: true
      legacy-path-enabled: true
```

`agent-id` 非空且没有自定义 `AgentHandler` Bean 时，`agent-service-adapters-agentcore` 会装配 `JiuwenCoreAgentHandler`。

### 5.4 方式二：自定义 AgentHandler

适合远端引擎、业务 HTTP 代理、人机中断或其他不直接走 Core Runner 的场景。

```java
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MyAgentConfig {
    @Bean
    AgentHandler agentHandler() {
        return new AgentHandler() {
            @Override
            public QueryResponse query(ServeRequest request) {
                return new QueryResponse(result(request), request.getConversationId());
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, result(request)));
                observer.onComplete();
            }

            private Map<String, Object> result(ServeRequest request) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("role", "assistant");
                result.put("content", "hello from my agent");
                result.put("conversation_id", request.getConversationId());
                return result;
            }
        };
    }
}
```

存在 `@Bean AgentHandler` 时，默认 agentcore adapter 不会覆盖你的实现。
真实项目中建议把 Handler 拆成独立类，并在 `start()`、`stop()`、`clearSession()` 中处理后端连接、资源释放和会话清理。

## 6. 接入真实大模型

demo 默认会尝试发现 `apiconfig.json`。找到配置后，链路会从 mock 切换为真实 Core 链路：

```text
Query API -> ServeOrchestrator -> JiuwenCoreAgentHandler -> Runner -> LlmAgent
```

配置文件查找顺序：

1. `openjiuwen.demo.llm.config-file`
2. `OPENJIUWEN_API_CONFIG`
3. 从当前工作目录向上查找 `apiconfig.json`

示例：

```json
{
  "API_BASE": "https://api.example.com/v1",
  "API_KEY": "replace-with-your-api-key",
  "MODEL_PROVIDER": "OpenAI",
  "MODEL_NAME": "replace-with-your-model-name",
  "LLM_SSL_VERIFY": "true"
}
```

启动：

```bash
OPENJIUWEN_API_CONFIG=/path/to/apiconfig.json \
mvn -pl agent-service-demo -am spring-boot:run
```

如果使用同一个 `conversation_id` 连续发多轮请求，正式 Core 链路会进入 Core 的 Session / Context 机制。

## 7. AaaS 与 A2A 的关系

AaaS 是服务化形态，A2A 是 Agent 间互联协议。
一个 Agent Service 可以同时提供基础 REST 对话面和进程内 A2A 入口：

| 入口 | 用途 |
| --- | --- |
| `/v1/query` | 普通 HTTP 客户端、网关、业务系统调用 Agent |
| `/health` | 探针、运维和编排系统检查服务状态 |
| `/.well-known/agent-card.json` | A2A Client 发现 Agent Card |
| `/a2a` 或 `/a2a/` | A2A JSON-RPC：`SendMessage`、`SendStreamingMessage`、`GetTask` |

如果只是把一个 Agent 服务化，先跑通 `/v1/query`。
如果需要 Agent 间委托、远端调用、Task 状态和中断恢复，再阅读 A2A 开发指导。

## 8. 生产化检查清单

上线前至少确认这些点：

| 检查项 | 说明 |
| --- | --- |
| Handler 选型 | 使用默认 `JiuwenCoreAgentHandler`，还是自定义 `AgentHandler` |
| 健康探针 | liveness 看 `process_up`，readiness 看 `agent_loaded` |
| 会话策略 | 明确 `conversation_id`、`user_id`、`space_id` 的来源 |
| 流式策略 | 客户端是否支持 SSE，是否需要 WebFlux `/v1/query/reactive` |
| 密钥配置 | LLM、MCP、远端 Agent 等密钥不要写入仓库 |
| 镜像配置 | 固定 Java 17+ Runtime，暴露服务端口，保留 `/health` |
| 平台边界 | 部署、路由、鉴权、服务注册通常由 Manager / Gateway 承担 |

## 9. 延伸阅读

- [快速开始](快速开始.md)：本地构建、启动 demo、发起第一条 Query。
- [HTTP 对话面](HTTP对话面.md)：`/v1/query`、SSE、health、reset 字段契约。
- [开发 Agent Service](<开发Agent Service.md>)：业务镜像、依赖、配置和打包。
- [Adapters 与 Handler](Adapters与Handler.md)：`AgentHandler` SPI、Core Handler 和自定义 Handler。
- [生命周期与探针](生命周期与探针.md)：init、shutdown、readiness、interrupt。
- [A2A 与平台边界](A2A/平台边界.md)：进程内 A2A 与平台 A2A 的边界。
- [A2A 开发指导](A2A/开发指导.md)：Agent Card、JSON-RPC、远端调用、中断恢复。
