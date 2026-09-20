# In-Memory Redis 插件示例

独立 Maven 模块 `agent-service-demo-redis-inmemory`：一个**可拔插的** `RuntimeRedisClient` 插件 jar，
在进程内实现 SPI 全部 26 条命令，用于演示「插件接管 / 移除回退」。现有 `example/redis` 演示应用不做任何改动。

| 项          | 值                                                                      |
|------------|-------------------------------------------------------------------------|
| 目录        | `example/redis-inmemory/`                                               |
| 产物        | 普通 jar（无 mainClass、不做 spring-boot repackage）                          |
| 装配入口     | `InMemoryRedisStorageAutoConfiguration`（`AutoConfiguration.imports` 注册）     |
| 客户端实现   | `InMemoryRuntimeRedisClient`（26 方法全实现）                                 |

## 接管原理

1. 插件的 `@AutoConfiguration` 声明 `before = RedisMiddlewareAutoConfiguration`，保证先于原生装配处理。
2. 插件 `@Bean` **无条件**返回 `InMemoryRuntimeRedisClient`。
3. 原生 Jedis 客户端 bean 上既有的 `@ConditionalOnMissingBean(RuntimeRedisClient.class)` 检测到插件 bean
   已注册，自动让位（原生装配代码零改动）。
4. 移除插件 jar 后重新启动，原生客户端自动恢复，业务代码零改动。

启动日志通过 `RedisDatasourceDiagnostics` 输出**全限定实现类名**，接管与回退一目了然：

```text
Runtime Redis datasource selected: ... RuntimeRedisClient=com.openjiuwen.service.demo.example.redis.inmemory.InMemoryRuntimeRedisClient ...
```

## 快速开始（接管 / 回退演示）

在 `agent-runtime-java/service` 目录下执行。

### 1. 构建插件

```bash
mvn -pl agent-service-demo/example/redis-inmemory -am package
```

### 2. 以插件接管启动 example/redis（8091）

```bash
mvn -pl agent-service-demo/example/redis -am spring-boot:run \
  -Dspring-boot.run.folders=agent-service-demo/example/redis-inmemory/target/classes
```

- `spring-boot.run.folders` 把插件的 `target/classes` 追加到应用 classpath，等效于把插件 jar 放入部署 lib 目录。
- 启动日志出现 `RuntimeRedisClient=...InMemoryRuntimeRedisClient` 即接管成功；此时**无需本机 Redis**，
  checkpointer 读写全部落在进程内存。
- 与真实接入的对应：生产部署将插件 jar 加入部署 classpath（lib 目录或 `loader.path`），移除即回退。

### 3. 回退

去掉 `-Dspring-boot.run.folders` 重新启动，日志回到：

```text
... RuntimeRedisClient=com.openjiuwen.service.adapters.common.middleware.redis.JedisPooledRuntimeRedisClient ...
```

同时恢复对真实 Redis 的依赖（先 `redis-cli ping` 确认，参见 [../redis/README.md](../redis/README.md)）。

## 命令面与已知限制

| 能力             | 行为                                                                        |
|-----------------|-----------------------------------------------------------------------------|
| 冻结 17 命令     | 语义对齐 Redis（单键操作、返回值口径一致；`get(String)` 对非 UTF-8 载荷按替代字符解码）      |
| 8 条结构化命令    | hash/set 语义对齐；`hincrBy` 经 `ConcurrentHashMap.compute` 保证并发原子                       |
| TTL             | 记录到期时间戳、读时惰性判定；`expire(key, 0)` 立即删除；`SET` 覆盖旧值时清除 TTL              |
| `scanIter`      | 内存 glob 近似实现（支持 `*` 与 `?`，不支持 `[...]` 字符类）                                  |
| `eval`          | **显式抛出 `UnsupportedOperationException`**：内存插件无法近似 Lua 原子语义，按 FRS
                 「无法满足原子语义须显式声明不提供，不得退化为非原子实现」loudly 失败 |
| 生命周期         | 状态进程内，重启即失；跨进程恢复等持久化语义需回退真实 Redis                                  |

## 编写真实插件的对应关系

| 本演示件                                   | 真实插件（如 NOS 后端）                                        |
|-------------------------------------------|----------------------------------------------------------------|
| 依赖 `agent-service-spec`（仅 SPI）          | 依赖 SPI jar + 自家 SDK，不依赖本仓其余模块                        |
| 实现 26 条命令                              | 实现全部命令；无法满足的命令显式抛出，**不得静默降级**               |
| `META-INF/spring/...AutoConfiguration.imports` | 同（Spring Boot 自动装配标准注册方式）                           |
| `@AutoConfiguration(before = 原生装配)`       | 同（引用 adapters 工件类字面量，或与部署侧协调装配顺序）              |

## 测试

- `InMemoryRuntimeRedisClientTest`：命令语义、`hincrBy` 多线程原子累加、expire 语义、scan glob、eval 抛出、close 清空。
- `InMemoryRedisStorageAutoConfigurationTest`：插件+原生同场 → 插件接管且日志输出全限定类名；中间件关闭时插件仍供给；
  `spring.autoconfigure.exclude` 排除插件 → 原生客户端恢复（回退等价验证）。
