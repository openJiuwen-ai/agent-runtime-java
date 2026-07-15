# credential

`com.openjiuwen.service.adapters.common.credential` 定义凭据解密扩展点。

## 类型

| Type | Description |
| --- | --- |
| `CredentialDecryptor` | 解密 SPI；兼容单参数方法，并支持 `decrypt(ciphertext, sceneType)` 场景化解密。 |
| `CredentialSceneType` | 稳定的解密场景常量：`UNKNOWN`、`REDIS_PASSWORD`、`LLM_API_KEY`、`MEMORY_API_KEY`。 |
| `PassthroughCredentialDecryptor` | 默认实现，原样返回输入值。 |
| `CredentialDecryptorAutoConfiguration` | 缺少 `CredentialDecryptor` Bean 时注册 passthrough 实现。 |

## 使用场景

- Redis encrypted password。
- LLM API Key。
- Memory provider API Key。
- 外部服务凭据字段。
- 机构配置中心或 KMS 接入时，可以注册自定义 `@Bean CredentialDecryptor` 覆盖默认实现。

## 源码路径

`service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/credential/`
