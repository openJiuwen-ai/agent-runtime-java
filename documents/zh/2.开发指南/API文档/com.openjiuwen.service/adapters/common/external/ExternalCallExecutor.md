# com.openjiuwen.service.adapters.common.external.ExternalCallExecutor

## class ExternalCallExecutor

`ExternalCallExecutor` 是外部服务出站调用治理执行器。调用方传入服务标签、治理策略、错误码和实际调用逻辑，由执行器统一处理超时、重试、熔断、审计和异常封装。

## 核心能力

| 能力 | Description |
| --- | --- |
| timeout | 按 `ExternalCallPolicy.getTimeoutMs()` 限制调用耗时。 |
| retry | 按 `ExternalRetryPolicy.max/backoffMs` 重试。 |
| circuit breaker | 达到失败阈值后快速失败，等待 reset window 后恢复。 |
| audit | 记录外部调用日志。 |
| error wrapping | 统一抛出 `ExternalSvcAdapterException`。 |

## 使用位置

- `DecoratingMcpClient`
- `DecoratingRemoteClient`
- `DecoratingSandboxClient`

## 相关文档

- [外部服务](../../../../../特性/外部服务.md)
