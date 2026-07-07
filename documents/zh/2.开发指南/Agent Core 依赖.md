# Agent Core 依赖

Runtime 仓通过 Maven 依赖独立仓库 **[agent-core-java](https://gitcode.com/openJiuwen/agent-core-java)** 提供 Agent、工作流、Runner 等执行能力。本文是 **版本与本地构建的唯一说明页**；其它文档只链接到此处，不再重复写版本号或分支名。

## 版本从哪里来

**以本仓根 `pom.xml` 为准**（`<properties>`）：

| 属性 | 含义 |
| --- | --- |
| `agent-core.version` | Maven 坐标 `com.openjiuwen:agent-core-java` 的版本 |
| `agent-core.git.branch` | 从源码本地安装 Core 时 checkout 的分支 |
| `agent-core.repo.url` | Core 仓库地址 |

升级 Core 时 **只改根 `pom.xml` 上述属性**，再按下方步骤重装 Core（或从私服拉取同版本制品）。

查看当前值（在 `agent-runtime-java` 根目录）：

```bash
mvn -q help:evaluate -Dexpression=agent-core.version -DforceStdout
mvn -q help:evaluate -Dexpression=agent-core.git.branch -DforceStdout
```

## 本地安装 Core（源码）

本地 Maven 仓库尚无对应 jar 时：

```bash
git clone https://gitcode.com/openJiuwen/agent-core-java.git
cd agent-core-java
git checkout "$(mvn -q -f /path/to/agent-runtime-java/pom.xml help:evaluate -Dexpression=agent-core.git.branch -DforceStdout)"
mvn clean install -DskipTests
```

将 `/path/to/agent-runtime-java` 换为本仓实际路径。Windows PowerShell 可先 `cd` 到 runtime 根目录再执行：

```powershell
$branch = mvn -q help:evaluate "-Dexpression=agent-core.git.branch" "-DforceStdout"
cd ..\agent-core-java   # 按你的 clone 路径调整
git checkout $branch
mvn clean install -DskipTests
```

## 从 Maven 仓库解析

若机构私服或中央仓已发布与 `agent-core.version` 一致的制品，**无需 clone Core**，在本仓直接 `mvn install` / `mvn test` 即可。

## Core 文档入口

- 开发指南与 API 索引见 Core 仓 **`documents/zh/`**（路径与分支随 Core 仓演进，**以与 `agent-core.version` 匹配的源码或 tag 为准**）。
- 仓库首页：[agent-core-java](https://gitcode.com/openJiuwen/agent-core-java)

## 与 Runtime 的关系

- Runtime **不**通过 Git Submodule / `vendor/` 携带 Core 源码。
- Service 层经 `agent-service-adapters-agentcore` 调用 Core `Runner`；Ingress、Orchestrator、Adapters 边界见 [架构概述](架构概述.md)、[Adapters 与 Handler](Adapters与Handler.md)。
