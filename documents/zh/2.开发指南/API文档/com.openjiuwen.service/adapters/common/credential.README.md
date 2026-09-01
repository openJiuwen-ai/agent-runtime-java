# credential

`com.openjiuwen.service.adapters.common.credential` 定义凭据解密扩展点。

## 类型

| Type | Description |
| --- | --- |
| `CredentialDecryptor` | 解密 SPI；兼容单参数方法，并支持 `decrypt(ciphertext, sceneType)` 场景化解密。 |
| `CredentialSceneType` | 稳定的解密场景常量（见下表）。 |
| `PassthroughCredentialDecryptor` | 默认实现，原样返回输入值。 |
| `CredentialDecryptorAutoConfiguration` | 缺少 `CredentialDecryptor` Bean 时注册 passthrough 实现。 |

## CredentialSceneType 常量

| 常量 | 值 | 用途 |
| --- | --- | --- |
| `UNKNOWN` | 0 | 未区分场景 |
| `REDIS_PASSWORD` | 1 | Redis 密码 |
| `LLM_API_KEY` | 2 | LLM API Key |
| `MEMORY_API_KEY` | 3 | Memory provider API Key |
| `MCP_AUTH_TOKEN` | 10 | MCP 出站 auth token |
| `REMOTE_AUTH_TOKEN` | 11 | Remote(A2A) 出站 auth token |
| `SANDBOX_AUTH_TOKEN` | 12 | Sandbox 出站 auth token |
| `TLS_KEYSTORE_PASSWORD` | 13 | TLS keystore 密码（入站/出站） |
| `TLS_TRUSTSTORE_PASSWORD` | 14 | TLS truststore 密码 |

## 使用场景

- Redis encrypted password。
- LLM API Key。
- Memory provider API Key。
- MCP / Remote / Sandbox 出站 `encrypted-token`（场景 10/11/12）。
- TLS keystore / truststore 密码（场景 13/14）。
- 外部服务凭据字段。
- 机构配置中心或 KMS 接入时，可以注册自定义 `@Bean CredentialDecryptor` 覆盖默认实现。

## 源码路径

`service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/credential/`
