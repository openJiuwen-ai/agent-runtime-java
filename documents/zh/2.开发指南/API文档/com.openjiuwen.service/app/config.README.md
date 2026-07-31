# config

`com.openjiuwen.service.app.config` 绑定 Agent Service 的 Spring Boot 配置。

## 类型

| Type | Prefix / Role | Description |
| --- | --- | --- |
| `ServiceProperties` | `openjiuwen.service` | Service 根配置。 |
| `QueryProperties` | `openjiuwen.service.query` | Query MVC / WebFlux / legacy path 配置。 |
| `LifecycleProperties` | `openjiuwen.service.lifecycle` | shutdown drain 和 init fail-fast 配置。 |
| `A2AProperties` | `openjiuwen.service.a2a` | Agent Card、A2A endpoint、skills、remote agents 配置。 |
| `SecurityProperties` | `openjiuwen.service.security` | 入站 TLS/mTLS 与细粒度鉴权（Issue #24）。 |
| `LlmProperties` | `openjiuwen.service.llm` | 原始 LLM 配置，`api-key` 可保存密文。 |
| `LlmConfigResolver` | LLM 配置解析 | 合并 `apiconfig.json`、应用默认值并按场景解密 API Key。 |
| `ResolvedLlmConfig` | LLM 运行配置 | 不可变的已解析配置，供 Agent 工厂消费。 |
| `DefaultAgentServiceIdentity` | `AgentServiceIdentity` 默认实现 | 从环境读取应用名。 |

## ServiceProperties

| Property | Default | Description |
| --- | --- | --- |
| `agent-id` | `null` | 默认 `JiuwenCoreAgentHandler` 从 Core `ResourceMgr` 取 Agent 时使用。 |
| `version` | `0.1.1` | `/health.version`。 |

## QueryProperties

| Property | Default | Description |
| --- | --- | --- |
| `mvc.enabled` | `true` | 是否暴露 Servlet Query Controller。 |
| `webflux.enabled` | `false` | 是否暴露 WebFlux reactive Query Controller。 |
| `legacy-path-enabled` | `true` | 是否暴露 `/query` 和 `/reset_conversation`。 |

## LifecycleProperties

| Property | Default | Description |
| --- | --- | --- |
| `shutdown-timeout-ms` | `30000` | shutdown 阶段等待活动流结束的最大时间。 |
| `init-fail-fast` | `true` | init hook 抛异常时是否失败启动。 |

## A2AProperties

| Property | Default | Description |
| --- | --- | --- |
| `agent-description` | `""` | Agent Card 描述。 |
| `provider-organization` | `OpenJiuwen` | Agent Card provider organization。 |
| `is-streaming` | `true` | Agent Card streaming 能力。 |
| `is-push-notifications` | `false` | Agent Card push notification 能力。 |
| `default-input-modes` | `["text", "text/plain"]` | 默认输入模式。 |
| `default-output-modes` | `["text", "text/plain"]` | 默认输出模式。 |
| `json-rpc-path` | `/a2a` | JSON-RPC 路径配置。 |
| `public-url` | `null` | Agent Card 中对外 URL。 |
| `skills` | `[]` | Agent Card skills。 |
| `remote-agents` | `[]` | 远端 A2A Agent 配置。 |

## SecurityProperties

前缀：`openjiuwen.service.security`。总开关 `enabled=false` 时不注册 TLS 绑定与鉴权 AOP。

### 根属性

| Property | Default | Description |
| --- | --- | --- |
| `enabled` | `false` | 安全能力总开关 |
| `tls` | — | 入站 TLS / mTLS 子配置 |
| `auth` | — | 细粒度鉴权子配置 |

### `security.tls`

| Property | Default | Description |
| --- | --- | --- |
| `enabled` | `false` | 启用 HTTPS / TLS |
| `protocol` | `TLS` | SSL 上下文协议 |
| `enabled-protocols` | `[TLSv1.2, TLSv1.3]` | 允许的 TLS 版本 |
| `client-auth` | `none` | mTLS：`none` / `want` / `need` |
| `key-store` | — | 服务端 keystore（`tls.enabled=true` 必填） |
| `key-store-password` | — | keystore 密码；场景 `TLS_KEYSTORE_PASSWORD`(13) |
| `key-store-type` | `PKCS12` | keystore 类型 |
| `trust-store` | — | `client-auth=want\|need` 时必填 |
| `trust-store-password` | — | truststore 密码；场景 `TLS_TRUSTSTORE_PASSWORD`(14) |
| `trust-store-type` | `PKCS12` | truststore 类型 |
| `certificate-expiry-policy` | `warn` | 证书过期：`warn` 或 `fail`（启动失败） |

### `security.auth`

| Property | Default | Description |
| --- | --- | --- |
| `enabled` | `false` | 启用 `@AuthorizedResource` AOP；须注册唯一 `FineGrainedAuthorizer` Bean |

详见 [安全加固](../../../开发与扩展/安全加固.md)。

## LlmProperties

| Property | Default | Description |
| --- | --- | --- |
| `config-file` | `null` | 显式 `apiconfig.json` 路径。 |
| `auto-discover` | `false` | 是否从当前目录向上发现 `apiconfig.json`。 |
| `provider` | `OpenAI` | 模型客户端 provider。 |
| `api-key` | `""` | API Key；自定义 `CredentialDecryptor` 可将其作为密文解密。 |
| `api-base` | `""` | 模型服务地址。 |
| `model-name` | `""` | 模型名称。 |
| `ssl-verify` | `true` | 是否校验服务端 TLS 证书。 |
| `system-prompt` | `""` | 系统提示词。 |
| `temperature` | `0.6` | 模型 temperature。 |
| `top-p` | `0.8` | 模型 top-p。 |
| `timeout` | `60s` | 模型客户端超时。 |
| `context-window-limit` | `10` | 上下文窗口轮数。 |
| `max-iterations` | `5` | Agent 最大迭代次数。 |

## 源码路径

`service/agent-service-app/src/main/java/com/openjiuwen/service/app/config/`
