---
description: Credentials, ingress TLS/auth, callback SSRF defense, path validation, and security-sensitive area rules for agent-runtime-java.
language: english
paths:
  - "service/agent-service-app/src/main/java/com/openjiuwen/service/app/security/**"
  - "service/agent-service-app/src/main/java/com/openjiuwen/service/app/autoconfigure/**"
  - "service/agent-service-app/src/main/java/com/openjiuwen/service/app/controller/**"
  - "service/agent-service-adapters/agent-service-adapters-common/src/main/java/**/external/**"
  - "service/agent-service-adapters/agent-service-adapters-common/src/main/java/**/credential/**"
alwaysApply: false
---

# Security Rules

## Credential Handling

- Never hard-code secrets, API keys, tokens, real endpoints in source.
- Credentials come from environment/config at runtime; decrypt through
  the `CredentialDecryptor` chain (adapters-common credential package) —
  do not invent side channels.
- Tests use placeholder values only. `.env*` and
  `apiconfig.json` are gitignored — keep it that way; `!.env.example`
  placeholders are fine.
- Demo TLS/security example materials live under
  `example/security/tls/` (gitignored, developer-local) — never commit
  real certificates/keystores.

## Ingress Security Toggles (know the defaults)

The whole security stack is **opt-in** (default off, single-user
reference posture):

```yaml
openjiuwen.service.security.enabled: true   # master gate (SecurityAutoConfiguration)
openjiuwen.service.security.tls.enabled: ...   # TLS startup validation
openjiuwen.service.security.auth.enabled: true  # FineGrainedAuthorizer AOP
```

- `@AuthorizedResource(resource, action)` on controllers is enforced by
  `ResourceAuthorizationAspect` **only when both** master and auth gates
  are on. Adding the annotation without documenting the toggle state is
  a review finding.
- `FineGrainedAuthorizerBootstrapValidator` fails startup if auth is on
  but no authorizer bean exists — keep that fail-fast behavior.

## A2A Task Authorization Boundary

Task-store reads are not caller-authorized in the current SDK
(CVE-2026-111764). Until the SDK upgrade lands with a
`TaskAuthorizationProvider`:

- A2A endpoints must sit behind network isolation in multi-caller
  deployments; do not expose `A2A_JSONRPC` paths beyond the trusted
  perimeter.
- `@AuthorizedResource(resource = "a2a", action = "rpc")` is an entry
  gate, not a task-ownership check — don't weaken it, don't claim it
  covers cross-caller reads.
- New task-reading code paths (e.g. future list/resume endpoints) must
  state their authorization assumption in the PR description.

## Callback SSRF Defense (do not regress)

Push-notification callback URLs are attacker-controllable ingress.
The defense is layered in this repo — preserve every layer:

1. `A2aPushNotificationCallbackUrlPolicy.validateCallbackUrl` at inline
   config registration (SendMessage / SendStreamingMessage dispatch);
2. `callbackUri()` re-check immediately before each delivery
   (`HttpPushNotificationSender.sendTerminalTask`);
3. Scheme allowlist (http/https only), no userInfo, valid port; reject
   loopback/site-local/link-local/multicast/0::/8/100.64 shared/240
   reserved/IPv6 ULA (fc00::/7);
4. `Redirect.NEVER` on delivery HttpClient — redirects never followed;
5. `callbackAllowedHosts` explicit egress allowlist in `A2AProperties`.

Known residual: DNS-rebinding TOCTOU between validation-time and
connection-time resolution — deployment egress controls are the
documented compensating control (code comment in the policy class).
Any change here requires updating `CallbackDestinationSecurityTest`.

## Outbound External Calls

- External service calls go through `ExternalCallExecutor` /
  decorators honoring `ExternalCallPolicy` (timeout / retry /
  circuit-breaker / audit) — don't hand-roll `HttpClient` calls for
  third-party APIs.
- The push-notification sender is the one sanctioned raw `HttpClient`
  user (protocol duty); its destination policy is above.

## Sensitive Log Data

See logging.md — no raw bodies/credentials at `info`+. Callback URLs
with embedded tokens: log host only.

## Reporting

Security issues: `SECURITY.md` at repo root. Do not open public issues
with exploit details.
