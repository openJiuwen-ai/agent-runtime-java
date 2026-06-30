# Service 层文档索引

Agent Service（`service/`）开发指南子集。完整叙事文档与仓库级说明见上级 [documents/zh/SUMMARY.md](../../../documents/zh/SUMMARY.md)。

## 模块导读

- [service/README.md](../../README.md) — Maven 模块树与构建命令
- [agent-service-demo/README.md](../../agent-service-demo/README.md) — 运行 demo、LLM 配置、示例脚本

## 开发指南（仓库级）

| 主题 | 链接 |
| --- | --- |
| 快速开始 | [快速开始](../../../documents/zh/2.开发指南/快速开始.md) |
| 架构概述 | [架构概述](../../../documents/zh/2.开发指南/架构概述.md) |
| 逻辑架构 | [逻辑架构](../../../documents/zh/2.开发指南/逻辑架构.md) |
| HTTP 对话面 | [HTTP对话面](../../../documents/zh/2.开发指南/HTTP对话面.md) |
| 开发 Agent Service | [开发Agent Service](../../../documents/zh/2.开发指南/开发Agent Service.md) |
| Adapters 与 Handler | [Adapters与Handler](../../../documents/zh/2.开发指南/Adapters与Handler.md) |
| 生命周期与探针 | [生命周期与探针](../../../documents/zh/2.开发指南/生命周期与探针.md) |
| A2A 与平台边界 | [A2A与平台边界](../../../documents/zh/2.开发指南/A2A与平台边界.md) |
| A2A 开发指导 | [A2A开发指导](../../../documents/zh/2.开发指南/A2A开发指导.md) |

## API

- [spec 包说明](../../../documents/zh/2.开发指南/API文档/com.openjiuwen.service/spec.README.md)

## 测试入口

```bash
cd service
mvn clean test
```

集成测试：`agent-service-app`（`src/test/java/.../it/`）、`agent-service-a2a-test`（A2A 场景）。
