---
id: 001
title: Optional collaborators (ObjectProvider) must treat absent as a supported state
module: service/agent-service-app
priority: P0
type: boundary
tags: [object-provider, optional-deps, null-safety]
---

## Description

Runtime beans receive optional collaborators via `ObjectProvider<T>` /
`@Autowired(required = false)` (admission gates, ingress hooks,
FineGrainedAuthorizer). `getIfAvailable()` returning null is a valid,
supported runtime state — code paths must degrade gracefully, never NPE.

## Input Conditions

- Any bean whose collaborator arrives as `ObjectProvider<T>`
- The bean is constructed/invoked with no `T` bean registered in the
  context

## Expected Behavior

- No NullPointerException during construction or handling
- Default/no-op behavior is applied and (when meaningful) logged at debug
- Feature-flag-off paths (`@ConditionalOnProperty`) behave identically
  to absent-bean paths

## Test Location

- Every new auto-configuration ships a context test asserting the
  optional path, per `.claude/rules/test-flow.md`
- Existing reference: `HostedAssemblyTest`, `TaskStoreCallbackBindingTest`

## Coverage

- [ ] not covered
