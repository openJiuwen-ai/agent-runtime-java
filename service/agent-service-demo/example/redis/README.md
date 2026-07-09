# Redis Checkpointer 示例

独立 Maven 模块 `agent-service-demo-redis`，演示 **Core Session + Redis Checkpointer** 的多轮对话与跨进程恢复。

| 项            | 值                                                        |
|--------------|----------------------------------------------------------|
| 目录           | `example/redis/`                                         |
| 默认端口         | **8091**（主 demo 为 8090，可同时运行）                            |
| Agent        | `ReActAgent`（`ExampleReActAgentFactory`）                 |
| Handler      | `JiuwenCoreAgentHandler`（走 Core Runner）                  |
| Checkpointer | `openjiuwen.service.middleware.checkpointer.type: redis` |

## 这个示例解决什么问题

主 demo（8090）默认 `checkpointer.type=in_memory`，进程退出后会话即丢失。本模块把 checkpoint 写入 Redis，用于验证：

1. **同一进程内**：相同 `conversation_id` 的多轮 Query 能带上前文上下文。
2. **跨进程**（可选）：停止服务后重启，同一 `conversation_id` 仍可从 Redis 恢复会话。

> 必须使用真实大模型 API，并与主 demo（8090）一样走 Core 链路；本模块额外启用 Redis checkpointer。

## 快速开始

在仓库根目录下的 **`agent-runtime-java/service`** 中执行。

### 1. 启动 Redis

默认连接见 `../config/application-base.yml`：

| 配置项      | 默认值         |
|----------|-------------|
| type     | `standalone` |
| host     | `127.0.0.1` |
| port     | `6379`      |
| database | `0`         |
| 密码       | 无           |

```powershell
redis-cli -h 127.0.0.1 -p 6379 ping
# 期望: PONG
```

> 检查 Redis 请用 `redis-cli ping`，不要用 TCP 端口探测代替 Redis 协议。

### 2. 配置大模型 API

任选一种方式（详见 [../README.md](../README.md) 的「模型 API 配置」）：

**方式 A — 本地 yml（推荐）**

```bash
cp ../config/application-base_local.example.yml ../config/application-base_local.yml
# 编辑 application-base_local.yml，填写 openjiuwen.demo.llm 下的 api-key / api-base / model-name
# 建议设置 auto-discover: false
```

**方式 B — apiconfig.json**

```bash
# 工作目录或 OPENJIUWEN_API_CONFIG 指向的 apiconfig.json
export OPENJIUWEN_API_CONFIG=/path/to/apiconfig.json   # Linux / Git Bash
$env:OPENJIUWEN_API_CONFIG="C:\path\to\apiconfig.json"  # PowerShell
```

**方式 C — 环境变量**

`application-base.yml` 支持占位符：`OPENJIUWEN_DEMO_LLM_API_KEY`、`OPENJIUWEN_DEMO_LLM_API_BASE`、
`OPENJIUWEN_DEMO_LLM_MODEL_NAME`。

### 3. 启动服务

```bash
mvn -pl agent-service-demo/example/redis -am spring-boot:run
```

启动成功后监听 **http://localhost:8091**。`/health` 中 `app` 应为 `demo-redis-agent-service`。

### 4. 运行 smoke 脚本

**同步 Query（非流式）**

Windows（推荐）：

```powershell
cd agent-service-demo\example\redis
.\smoke-redis.ps1
# 可选: .\smoke-redis.ps1 -BaseUrl http://127.0.0.1:8091 -ConvId my-test-c1
```

Linux / Git Bash：

```bash
cd agent-runtime-java/service
bash agent-service-demo/example/redis/smoke-redis.sh
# 可选: BASE_URL=http://127.0.0.1:8091 CONV_ID=my-test-c1 bash ...
```

**流式 Query（SSE）**

Windows（推荐）：

```powershell
cd agent-service-demo\example\redis
.\smoke-redis-stream.ps1
# 可选: .\smoke-redis-stream.ps1 -ConvId redis-stream-c1 -CodeName REDIS-STREAM-42
```

Linux / Git Bash：

```bash
cd agent-runtime-java/service
bash agent-service-demo/example/redis/smoke-redis-stream.sh
# 可选: CONV_ID=redis-stream-c1 CODE_NAME=REDIS-STREAM-42 bash ...
```

同步脚本流程：

1. `GET /health`
2. 第一轮 Query：写入代号 `REDIS-DEMO-42`
3. 第二轮 Query：追问代号，断言回复中包含 `REDIS-DEMO-42`

流式脚本流程：

1. `GET /health`
2. 第一轮 `stream=true`：写入代号 `REDIS-STREAM-42`，校验 SSE 事件与非空聚合内容
3. 第二轮 `stream=true`：追问代号，从 SSE 聚合文本中断言包含 `REDIS-STREAM-42`

若 `/health` 返回 `app=demo-agent-service`，说明连到了 **8090 主 demo**，请确认本模块已在 **8091** 启动。

## 手动验证（可选）

```bash
# 第一轮
curl -s http://127.0.0.1:8091/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"manual-c1","message":"请记住代号 REDIS-DEMO-42，回复收到即可。","stream":false}'

# 第二轮（同一 conversation_id）
curl -s http://127.0.0.1:8091/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"manual-c1","message":"我刚才的代号是什么？只回答代号。","stream":false}'
```

**跨进程恢复**：执行完第一轮后停止 Java 进程，用相同命令重新 `spring-boot:run`，再发第二轮 Query；仍应能召回上下文。

## 配置说明

本模块通过 Spring Boot `spring.config.import` 分层加载配置（后加载的文件覆盖先加载的）：

```yaml
# application.yml
spring:
  config:
    import:
      - optional:classpath:application-base.yml          # 共享 LLM、Redis 连接、Query 等
      - optional:classpath:application-base_local.yml     # 本地密钥（gitignore）
      - optional:classpath:application-redis-checkpointer.yml  # 本模块：8091 + redis checkpointer
```

| 文件                                     | 作用                                                                |
|----------------------------------------|-------------------------------------------------------------------|
| `../config/application-base.yml`       | `openjiuwen.demo.llm`、Redis 连接、`checkpointer.type: in_memory`（默认） |
| `../config/application-base_local.yml` | 本地 API 覆盖（勿提交）                                                    |
| `application-redis-checkpointer.yml`   | **`server.port: 8091`**、`checkpointer.type: redis`                |

> `server.port` 必须写在 **最后 import** 的 `application-redis-checkpointer.yml` 中，否则会沿用 base 里的 8090。

修改 Redis 地址时，编辑 `application-base.yml`（或 local 覆盖）中的 `openjiuwen.service.middleware.redis.default`。
默认 `type=standalone`，可省略；如需连接 Redis Cluster，配置命名 endpoint 并让 `redis-ref` 指向该 endpoint：

```yaml
openjiuwen:
  service:
    middleware:
      checkpointer:
        type: redis
        redis-ref: cluster
      redis:
        cluster:
          type: cluster
          nodes:
            - 10.10.1.11:6379
            - 10.10.1.12:6379
          database: 0      # cluster 模式忽略该字段；非 0 也不会启动失败
          timeout-ms: 3000
          encrypted-password: ""
```

`standalone` 使用 `host`、`port`、`database`；`cluster` 使用 `nodes`，不会从 `host` / `port` 推导集群节点。

## 常见问题

| 现象                                                 | 可能原因                             | 处理                                                                |
|----------------------------------------------------|----------------------------------|-------------------------------------------------------------------|
| 启动报 `api-key` / `api-base` / `model-name` 未配置      | LLM 未填                           | 配置 `application-base_local.yml` 或 `apiconfig.json`                |
| `Redis connection` / checkpoint 写入失败               | Redis 未启动或 endpoint 配置不对          | `redis-cli ping`；单机核对 host/port，集群核对 type/nodes              |
| smoke 连错服务                                         | 8090 与 8091 端口混淆                 | 确认 `/health` 中 `app=demo-redis-agent-service`，且 BASE_URL 指向 8091  |
| 第二轮无法召回代号                                          | 模型未遵循指令，或未走 Core 路径              | 查看 8091 日志；换更强模型或重试；确认 `checkpointer.type=redis`                  |
| `bash smoke-redis.sh` 报 `pipefail: invalid option` | 脚本 CRLF 换行                       | Windows 用 `.\smoke-redis.ps1`，或 `sed -i 's/\r$//' smoke-redis.sh` |
| smoke 通过但跨进程失败                                     | Redis 数据被清空或 conversation_id 不一致 | 确认 Redis 持久化策略；两轮使用相同 `conversation_id`                           |

## 相关代码

- 入口：`src/main/java/.../RedisDemoApplication.java`
- 共享工厂与 LLM 配置：`../support/`、`../config/`
- 集成测试参考：`agent-service-adapters-agentcore` 中的 `MiddlewareRedisSpringIT`

更多特性示例见 [../README.md](../README.md)。
