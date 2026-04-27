# agent-runtime-java

Java 版 EDPAgent 运行包，包含两个进程：

- `a2a_service`：北向 A2A / SSE 入口，负责会话编排
- `versatile_adapter`：代理真实 Versatile 工作流服务

运行依赖可以全部使用外部服务：Redis、LLM、Versatile、MCP 都不要求与本项目同机部署。

## 快速开始

初始化 submodule：

```bash
git submodule update --init --recursive
```

初始化配置：

```bash
scripts/deploy.sh init-config
vim config/deploy.properties
```

构建并启动：

```bash
scripts/deploy.sh build
scripts/deploy.sh start
scripts/deploy.sh status
```

健康检查：

```bash
scripts/deploy.sh probe
```

调用示例：

```bash
scripts/invoke.sh --query "帮我查一下账户余额"
scripts/invoke.sh --query "推荐两款低风险理财产品"
```

停止：

```bash
scripts/deploy.sh stop
```

## 关键配置

配置文件为 `config/deploy.properties`。

- `REDIS_*`：`a2a_service` 会话、task、checkpoint 使用的 Redis
- `VERSATILE_URL_TEMPLATE`：`versatile_adapter` 调真实 Versatile 的 URL，必须保留 `{conversation_id}`
- `VERSATILE_ADAPTER_URL`：`a2a_service` 访问 `versatile_adapter` 的地址
- `VA_WORKFLOW_RESULT_NODE`：真实 Versatile 返回的 QA 节点名，默认 `GXZQAResponseNode`
- `LLM_*`：EDPAgent 使用的模型配置
- `MCP_*`：MCP-first 理财推荐链路使用

更完整的部署说明见 [docs/deployment.md](docs/deployment.md)。
