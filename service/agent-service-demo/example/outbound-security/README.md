# Outbound TLS / Auth Demo (Issue #25)

演示 **MCP** 与 **Sandbox** 出站 HTTPS + Bearer 鉴权的端到端接线方式。本模块不依赖外部服务，内置 mock HTTPS server。

A2A Remote 出站 demo 暂不纳入本模块（service 内存在两套 A2A 出站路径，当前配置绑定的并非主用链路）。

## 文件

| 文件 | 说明 |
| --- | --- |
| `MockOutboundSecureMcpServer.java` | 本地 HTTPS MCP JSON-RPC（Bearer + `secure_echo`） |
| `MockOutboundSecureJiuwenBoxServer.java` | 本地 HTTPS jiuwenbox mock（Bearer + `readFile` 下载） |
| `support/OutboundTlsMaterialGenerator.java` | 用 `keytool` 生成临时 PKCS12 证书（口令每次运行随机生成，见 `Material#password()`） |
| `OutboundSecurityMcpClientExample.java` | MCP：`ExternalOutboundSecuritySupport` → Core `StreamableHttpClient` |
| `OutboundSecuritySandboxClientExample.java` | Sandbox：`DefaultAgentCoreSandboxClientFactory` → Core `SandboxClient`（OkHttp 注入） |
| `OutboundSecurityMcpE2EIT.java` / `OutboundSecuritySandboxE2EIT.java` | Maven 集成测试 |
| `application-outbound-security.example.yml` | MCP + Sandbox YAML 模板 |

Profile 交叉引用：

- [`../mcp/application-mcp-outbound-security.example.yml`](../mcp/application-mcp-outbound-security.example.yml)
- [`../sandbox/application-sandbox-outbound-security.example.yml`](../sandbox/application-sandbox-outbound-security.example.yml)

## 前提

在 `agent-runtime-java/service` 目录执行。

## 一键 E2E（推荐）

```bash
mvn -pl agent-service-demo/example/outbound-security -am test
```

覆盖两条出站路径：

| 测试 | 验证点 |
| --- | --- |
| MCP | JDK `HttpClient` + `authHeaders` → `tools/list` |
| Sandbox | OkHttp `_ojw_okhttp_client` + Bearer Interceptor → `fs().readFile` |

## 手动运行

MCP client：

```bash
mvn -pl agent-service-demo/example/outbound-security exec:java \
  -Dexec.mainClass=com.openjiuwen.service.demo.example.outboundsecurity.OutboundSecurityMcpClientExample
```

Sandbox client：

```bash
mvn -pl agent-service-demo/example/outbound-security exec:java \
  -Dexec.mainClass=com.openjiuwen.service.demo.example.outboundsecurity.OutboundSecuritySandboxClientExample
```

## 单独启动 Mock Server

```bash
# MCP
mvn -pl agent-service-demo/example/outbound-security exec:java \
  -Dexec.mainClass=com.openjiuwen.service.demo.example.outboundsecurity.MockOutboundSecureMcpServer \
  -Dexec.args="--port=18443 --token=demo-outbound-token"

# Sandbox (jiuwenbox)
mvn -pl agent-service-demo/example/outbound-security exec:java \
  -Dexec.mainClass=com.openjiuwen.service.demo.example.outboundsecurity.MockOutboundSecureJiuwenBoxServer \
  -Dexec.args="--port=18490 --token=demo-outbound-token"
```

Mock server 启动后会打印 truststore 路径，并把随机生成的 keystore 口令写入同目录下的 `store-password.txt`。
把它导出给随后启动的服务（YAML 模板中 `trust-store-password` 没有默认值，必须由该变量提供）：

```bash
export DEMO_OUTBOUND_TRUST_STORE_PASSWORD=$(cat /tmp/agent-outbound-security-demo-*/store-password.txt)
```

> 口令与证书都随 mock server 进程退出而删除，不会跨次运行复用。

## YAML 配置要点

出站 TLS / 鉴权挂在各 endpoint 下：

```yaml
openjiuwen:
  service:
    external:
      mcp:
        servers:
          - server-id: demo-secure-mcp
            server-path: https://127.0.0.1:18443/mcp
            tls: { enabled: true, trust-store: file:..., verify-hostname: false }
            auth: { type: bearer, token: demo-outbound-token }
      sandbox:
        enabled: true
        servers:
          - server-id: demo-secure-sandbox
            service-url: https://127.0.0.1:18490
            sandbox-type: jiuwenbox
            tls: { enabled: true, trust-store: file:..., verify-hostname: false }
            auth: { type: bearer, token: demo-outbound-token }
```

## 延伸阅读

- [外部服务 — 出站 TLS 与鉴权](../../../../documents/zh/2.开发指南/开发与扩展/外部服务.md#9-出站-tls-与鉴权-issue-25)
- [security 入站示例](../security/README.md)（Issue #24）
