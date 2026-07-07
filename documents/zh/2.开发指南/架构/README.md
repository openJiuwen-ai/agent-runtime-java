# 架构

本栏目说明 **`agent-runtime-java`（Java Runtime 仓）** 在整体 **Agent Distributed Runtime** 蓝图中的位置。

- **整仓** = 架构图中的 **Agent Distributed Runtime**（Java 侧）
- **`service/`** = 其中的 **Agent Server** 子块（当前主交付）

整体架构图见 [逻辑架构 · 整体架构图](../逻辑架构.md#整体架构图)。

## 页面映射

| 页面 | 侧重 | 建议读者 |
| --- | --- | --- |
| [架构概述](../架构概述.md) | 本仓已交付模块（`service/` 等）与调用链 | 写 Spring Boot 镜像、接 HTTP/A2A |
| [逻辑架构](../逻辑架构.md) | 跨语言 Runtime 全景与仓库映射 | 与平台、Python Runtime 对齐 |

## 阅读提示

- 完成 [快速开始](../快速开始.md) 后：**先架构概述，再逻辑架构**。
- 勿将 **`service/`** 等同于整个 **`agent-runtime-java`**；Manager 等将随版本进入 **同一 Runtime 仓**。
- 执行内核见 [agent-core-java](https://gitcode.com/openJiuwen/agent-core-java)（[Agent Core 依赖](../Agent Core 依赖.md)）。
