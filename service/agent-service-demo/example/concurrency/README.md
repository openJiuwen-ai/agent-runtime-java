# 并发 E2E 验证（DeepAgent + Redis + A2A）

独立 Maven 模块 `agent-service-demo-concurrency`，用于 **Java runtime-core 多会话并发** 的性能与稳定性验证。

| 项 | 值 |
|---|---|
| 目录 | `example/concurrency/` |
| 默认端口 | **8096** |
| Agent | `DeepAgent`（`skill_echo` / `concurrent_lookup` skill 类工具） |
| Checkpointer | Redis |
| A2A | Agent Card + skills 声明（与 Query 共用同一 DeepAgent） |

## 验证目标

1. **DeepAgent + Redis checkpoint**：多 `conversation_id` 并发 Query，会话隔离与 checkpoint 写入稳定性。
2. **Tools / Skill 并发**：每会话触发 `skill_echo` 与 `concurrent_lookup`（含模拟延迟），观察 tool-call 池与 DeepAgent stream 池表现。
3. **A2A 多会话**（可选）：对 Agent A（18090）并发 JSON-RPC，验证嵌套 A→B→C/D 链路在 Redis TaskStore 下的稳定性。

> 本模块首要用途是 **压测与稳定性观测**。推荐 **Mock LLM 模式** 做可重复的并发基线；真实 LLM 模式用于端到端抽检。两种模式均需 Redis。

## LLM 模式：真实 vs Mock

| 模式 | 用途 | 是否需要 API Key |
|------|------|------------------|
| **real**（默认） | 端到端行为抽检 | 是 |
| **mock** | 可重复并发/稳定性压测 | 否 |

Mock 模式**仅替换 ModelClient 的 `invoke` / `stream`**（每次 LLM 调用固定延迟，默认 **3 秒**），**不会** mock `agent.invoke`、tool rails、Redis checkpoint 或 A2A 链路——用于隔离 runtime 并发性能与真实 LLM 波动。

### 启动 Mock LLM（任选一种）

**方式 A — Spring Profile（推荐）**

```bash
mvn -pl agent-service-demo/example/concurrency -am spring-boot:run \
  -Dspring-boot.run.profiles=concurrency-mock
```

**方式 B — JVM 系统属性**

```bash
mvn -pl agent-service-demo/example/concurrency -am spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Ddemo.concurrency.llm.mode=mock"
```

**方式 C — YAML**（`application-concurrency.yml` 或 local 覆盖）

```yaml
openjiuwen:
  service:
    demo:
      concurrency:
        llm:
          mode: mock
          mock-delay-ms: 3000
```

启动日志应出现：`Concurrency demo LLM mode: MOCK (delayMs=3000, ...)`。

### Mock LLM 配置项

| 系统属性 / 环境变量 / YAML | 默认 | 说明 |
|---|---|---|
| `demo.concurrency.llm.mode` / `DEMO_CONCURRENCY_LLM_MODE` / `openjiuwen.service.demo.concurrency.llm.mode` | `real` | `real` 或 `mock` |
| `demo.concurrency.llm.mock-delay-ms` / `...mock-delay-ms` | `3000` | 每次 LLM 调用的固定延迟（毫秒） |

Mock 模式下压测客户端仍可使用 `-Ddemo.concurrency.timeout-seconds=120`（非 stream 通常足够）。

## 前置条件

```powershell
redis-cli -h 127.0.0.1 -p 6379 ping
# 期望: PONG
```

配置 LLM（**real 模式**，与 [../README.md](../README.md) 相同；mock 模式可跳过）：

```bash
cp ../config/application-base_local.example.yml ../config/application-base_local.yml
# 填写 openjiuwen.service.llm
```

## 启动服务

在 `agent-runtime-java/service` 下：

```bash
mvn -pl agent-service-demo/example/concurrency -am spring-boot:run
```

成功后可访问 `http://localhost:8096/health`，`app` 应为 `demo-concurrency-agent-service`。

## 运行压测（CLI）

轻量 smoke（6 会话 × 3 并发）：

```bash
mvn -pl agent-service-demo/example/concurrency -am exec:java \
  -Ddemo.concurrency.mode=query \
  -Ddemo.concurrency.sessions=6 \
  -Ddemo.concurrency.concurrency=3 \
  -Ddemo.concurrency.min-success-rate=0.80
```

 heavier 压测示例：

```bash
mvn -pl agent-service-demo/example/concurrency -am exec:java \
  -Ddemo.concurrency.mode=query \
  -Ddemo.concurrency.sessions=40 \
  -Ddemo.concurrency.concurrency=20 \
  -Ddemo.concurrency.stream=true \
  -Ddemo.concurrency.lookup-delay-ms=50
```

流式 vs 非流式、线程池与 QPS 对比时可配合 JVM 参数调整 executor，例如：

```bash
-Dopenjiuwen.executor.deep-agent-stream.max-size=32
-Dopenjiuwen.llm.http.max-requests-per-host=64
```

### 配置项

| 系统属性 / 环境变量 | 默认 | 说明 |
|---|---|---|
| `demo.concurrency.mode` / `DEMO_CONCURRENCY_MODE` | `query` | `query` / `a2a` / `both` |
| `demo.concurrency.base-url` | `http://localhost:8096` | Query 压测目标 |
| `demo.concurrency.a2a-base-url` | `http://localhost:18090` | A2A Agent A |
| `demo.concurrency.sessions` | `20` | 独立会话数 |
| `demo.concurrency.concurrency` | `10` | 并发 worker 数 |
| `demo.concurrency.stream` | `false` | Query/A2A 是否流式 |
| `demo.concurrency.warmup` | `0` | 预热会话（不计入指标） |
| `demo.concurrency.timeout-seconds` | `120` | 单请求超时 |
| `demo.concurrency.min-success-rate` | `0.95` | 成功率阈值 |
| `demo.concurrency.lookup-delay-ms` | `50` | lookup 工具模拟延迟 |
| `demo.concurrency.rounds-per-session` | `2` | 每会话顺序请求轮数（奇数轮 skill_echo，偶数轮 lookup） |

输出示例：

```text
[query] total=80 success=78 failure=2 successRate=97.50% duration=12.34s qps=6.48 p50=890ms p95=2100ms p99=3200ms
[query] PASS
```

## A2A 并发压测

先按 [../a2a/README.md](../a2a/README.md) 启动四个 Agent。可选启用 Redis overlay：

```bash
cp ../a2a/application-a2a-redis.example.yml ../a2a/application-a2a-redis.local.yml
```

压测 Agent A：

```bash
mvn -pl agent-service-demo/example/concurrency -am exec:java \
  -Ddemo.concurrency.mode=a2a \
  -Ddemo.concurrency.sessions=8 \
  -Ddemo.concurrency.concurrency=4 \
  -Ddemo.concurrency.min-success-rate=0.75
```

A2A 场景含 LLM + 中断链路，成功率阈值建议低于纯 Query 压测。

## Smoke 脚本

Windows：

```powershell
cd agent-service-demo\example\concurrency
.\run-benchmark.ps1 -Sessions 6 -Concurrency 3
```

Linux / Git Bash：

```bash
bash agent-service-demo/example/concurrency/run-benchmark.sh
```

脚本会检查 `/health` 后执行轻量压测。

## Maven 集成测试

单元测试（无需 LLM）：

```bash
mvn -pl agent-service-demo/example/concurrency -am test
```

对外部运行服务的 E2E（需 LLM + Redis + 8096 已启动）：

```bash
mvn -pl agent-service-demo/example/concurrency -am test \
  -Ddemo.concurrency.e2e.base-url=http://127.0.0.1:8096
```

A2A E2E（四 Agent 已启动）：

```bash
mvn -pl agent-service-demo/example/concurrency -am test \
  -Ddemo.concurrency.e2e.a2a-base-url=http://127.0.0.1:18090
```

## 每会话请求模式（Query）

每个 benchmark 会话在同一 `conversation_id` 下顺序执行 **N 轮**（`-Ddemo.concurrency.rounds-per-session=N`，默认 2）：

1. 奇数轮（0,2,4…）：`skill_echo:{token}` → 断言响应含 token
2. 偶数轮（1,3,5…）：`lookup:{key}` → 断言响应含 key（含 configurable 延迟）

默认 2 轮即原来的 `skill_echo` + `lookup` 各一次。不同 `conversation_id` 并行执行，验证 Redis checkpoint 与 tool 并发。

## 相关代码

| 文件 | 说明 |
|---|---|
| `ConcurrencyDemoApplication.java` | DeepAgent + Redis 入口 |
| `mock/ConcurrencyMockModelClient.java` | 仅 mock LLM 调用（固定延迟 + 确定性 tool 规划） |
| `mock/ConcurrencyDemoLlmSupport.java` | real/mock 模式切换 |
| `SkillEchoRail.java` / `ConcurrentLookupRail.java` | skill 类工具（真实执行，不 mock） |
| `load/ConcurrentLoadRunner.java` | CLI 压测入口 |
| `load/ConcurrentLoadHarness.java` | 并发编排 |
| `load/QueryConcurrentClient.java` / `A2aConcurrentClient.java` | HTTP 客户端 |

## 常见问题

| 现象 | 处理 |
|---|---|
| `/health` 失败 | 确认 8096 已启动且 LLM 配置正确 |
| 成功率低、延迟高 | 降低 `concurrency`；调大 `deep-agent-stream` / HTTP 连接上限 |
| `ECHO:` marker 缺失 | 模型未调用工具；确认 `temperature=0` 或换更强模型 |
| A2A 压测全失败 | 确认 18090–18093 四 Agent 均已启动 |
| Mock 模式仍要求 API Key | 确认 `demo.concurrency.llm.mode=mock` 或 `spring.profiles.active=concurrency-mock` |
| Mock 压测超时 | 调大 `demo.concurrency.timeout-seconds`；或减少 `mock-delay-ms` × 每请求 LLM 轮次 |

更多特性示例见 [../README.md](../README.md)。
