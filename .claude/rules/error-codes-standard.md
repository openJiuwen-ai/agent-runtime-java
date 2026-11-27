---
description: Error propagation, JSON-RPC error envelopes, HTTP status mapping, and exception rules for agent-runtime-java.
language: english
paths:
  - "service/**/src/main/java/**/*.java"
---

# Error Handling Rules

This repo does **not** use the agent-core `ErrorHelper`/`StatusCode`
exception system (that belongs to Core internals). Runtime errors are
transport-shaped: JSON-RPC error envelopes and HTTP status codes.

## JSON-RPC Error Envelopes (A2A controller)

All A2A failures surface as protocol errors via
`A2aJsonRpcProtocol.errorResponse(id, error)`:

| SDK error class | When | Notes |
|---|---|---|
| `InvalidParamsError` | malformed params, invalid callback URL, context mismatch | Preserve "Invalid params: <reason>" message shape |
| `TaskNotFoundError` | unknown taskId (hosted validate) | — |
| `MethodNotFoundError` | unmapped method | default case in `dispatch` — keep the switch fall-through |
| `InternalError` | serialization failure | Never leak raw exception text |

- Request id must echo exactly as parsed (`JsonElement`), never coerced
  through `double` — the !262/641/642 regression area.
- `HostedIngressResolver.SelectionException` → HTTP status (503) or
  `InvalidParamsError` by failure kind; follow `handleJsonRpc` mapping.
- Content-Length pre-check (413) before parsing; keep oversized-body
  rejection upstream of any deserialization.

## HTTP Errors (REST controllers)

- Map via Spring `@ExceptionHandler` / `ResponseEntity` — domain failures
  to 4xx, infrastructure failures to 5xx; authorization denial goes
  through `AuthorizationDeniedExceptionHandler` (403) wired by
  `AuthAutoConfiguration`.
- No stack traces in response bodies. Log the full exception, return a
  generic reason.

## Exception Discipline (CodeArts base, still enforced)

- No empty catch blocks (G.ERR.01); no catching
  `Throwable`/`Exception`/`RuntimeException` directly (G.ERR.02).
- Re-throw across a boundary → attach original as cause (G.ERR.06).
- External dependency exceptions are caught at the adapter boundary and
  re-thrown as the protocol error or a runtime exception with cause —
  never let SDK/Gson/Jackson exceptions propagate to controller
  responses unhandled.
- `finally` blocks: no return/throw (G.ERR.08).

## Admission / Resource Errors

- Admission overflow is a *rejection*, not an exception: surface as the
  admission business code in the response envelope (see
  `admissionRejectionCode`), keeping the request id intact.
- Store/IO failures inside delivery paths are recorded
  (`DeliveryRecord` failure reason) and logged at `warn` — push
  delivery failures must not crash the SSE stream or the task
  lifecycle; they are delivery-level events.

## Error Metadata

Extended failure context rides in `A2aErrorMetadata` — when adding a new
failure mode that callers can programmatically react to, add a typed
entry there instead of string-matching messages.
