# Security（TLS / 细粒度鉴权）示例

独立 Maven 模块 `agent-service-demo-security`，演示 **`openjiuwen.service.security.*` 配置** 与 **`FineGrainedAuthorizer` SPI** 的接入方式。

| 项 | 值 |
|----|-----|
| 目录 | `example/security/` |
| 默认端口 | **8095** |
| 默认 profile | 仅启用 **auth**（HTTP）；TLS 配置见样例文件 |
| 配置参考 | `application-security.example.yml`（每项含说明 + 必选性 + 条件必选） |

## 这个示例解决什么问题

Issue #24 引入入站 **HTTPS/mTLS** 与 **细粒度鉴权 AOP**，但主 demo 与各特性模块默认均未开启 `openjiuwen.service.security`。开发者若只看代码，难以知道：

1. YAML 里有哪些键、各自含义与必选关系；
2. 开启 `auth.enabled=true` 后如何提供 `FineGrainedAuthorizer` Bean；
3. 鉴权拒绝时的 403 JSON 契约；
4. TLS/mTLS 与 auth 如何独立或组合启用。

本模块提供 **可运行的 auth demo** + **带完整注释的配置样例**。

## 配置样例文件

| 文件 | 作用 |
|------|------|
| `application-security.yml` | 本模块默认加载：8095 端口 + `security.enabled` + `auth.enabled` |
| `application-security.example.yml` | **完整参考**：每个配置项注释含 `[说明]` / `[必选性]` / `[条件必选]` |
| `application-security-tls.example.yml` | **TLS/mTLS 本地 profile 模板**（需先生成 `tls/` 目录下证书） |
| `application-security_local.yml` | 本地覆盖（复制 example 后修改；建议 gitignore，勿提交密钥） |
| `application-security-tls_local.yml` | TLS 本地 profile（gitignore；复制 tls example 后启用） |

`application.yml` 通过 `spring.config.import` 依次加载 base → local → security → security_local。

## 快速开始

在 `agent-runtime-java/service` 下执行。

### 1. 配置大模型 API

与 [../README.md](../README.md) 相同，复制并填写 `../config/application-base_local.yml`。

### 2. 启动服务

```bash
mvn -pl agent-service-demo/example/security -am spring-boot:run
```

启动成功后监听 **http://localhost:8095**。`/health` 不受鉴权 AOP 影响。

### 3. 验证鉴权

**无 `X-User-ID` → 403**

```bash
curl -s -o - -w "\nHTTP %{http_code}\n" http://127.0.0.1:8095/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"manual-sec-1","message":"hello","stream":false}'
```

期望 HTTP 403，body 含 `"code":"ACCESS_DENIED"`。

**带 `X-User-ID` → 200（需 LLM 可用）**

```bash
curl -s http://127.0.0.1:8095/v1/query \
  -H 'Content-Type: application/json' \
  -H 'X-User-ID: demo-user-1' \
  -d '{"conversation_id":"manual-sec-2","message":"hello","stream":false}'
```

Windows 也可运行：

```powershell
cd agent-service-demo\example\security
.\smoke-security.ps1
```

### 4. 启用 TLS / mTLS（可选）

**Step A — 生成本地测试证书（`tls/` 已 gitignore，勿提交）**

Windows：

```powershell
cd agent-service-demo\example\security
.\generate-tls-material.ps1
```

Linux / Git Bash：

```bash
cd agent-service-demo/example/security
bash generate-tls-material.sh
```

**Step B — 启用 TLS profile**

```bash
cp application-security-tls.example.yml application-security-tls_local.yml
# 编辑 application-security-tls_local.yml：
#   KEYSTORE_PASSWORD / TRUSTSTORE_PASSWORD 设为 demo-tls-pass（或你的 ENC 密文）
#   生产环境请使用 ENC(...) 密文，勿提交明文
```

**Step C — 启动 HTTPS（8443，mTLS need）**

```bash
mvn -pl agent-service-demo/example/security -am spring-boot:run
```

验证（需客户端信任 `tls/client-trust.p12` 并提供 `tls/client.p12`；可用集成测试里的 `TlsTestSslContextFactory` 思路，或机构侧 mTLS 客户端）：

- 无客户端证书 → TLS 握手失败（连接层，无 HTTP body）
- 带有效客户端证书 → `GET https://127.0.0.1:8443/health` 返回 200

> 自动化覆盖见 `agent-service-app` 模块：`TlsHttpsIntegrationTest`、`TlsMtlsNeedIntegrationTest`、`TlsMtlsWantIntegrationTest`（测试内临时 keytool 生成证书，不入库）。

默认 **8095 HTTP + auth** 与 **8443 HTTPS mTLS**  profile 互斥加载：仅 import `application-security-tls_local.yml` 时走 TLS demo；否则走 auth demo。

## 代码入口

| 类 | 说明 |
|----|------|
| `SecurityDemoApplication.java` | Spring Boot 入口；注册 `FineGrainedAuthorizer` Bean |
| `DemoFineGrainedAuthorizer.java` | 示例 SPI：要求请求头 `X-User-ID` |
| `../support/ExampleFineGrainedAuthorizers.java` | 测试用 `permitAll()` 工厂 |

生产环境请替换为机构 IAM 实现，勿直接使用 demo 策略。

## 相关文档

- 设计：[localmd/issue需求分析/issue-24.md](../../../../localmd/issue需求分析/issue-24.md)
- 更多特性示例：[../README.md](../README.md)
