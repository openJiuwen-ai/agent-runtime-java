---
description: Directs Claude to use templates and test-scenarios when creating code, fixing bugs, or writing tests in agent-runtime-java.
language: english
paths:
  - "service/**/src/main/java/**/*.java"
  - "service/**/src/test/java/**/*.java"
alwaysApply: false
---

# Templates & Test Scenarios

## When creating new code

Start from the matching template in `.claude/templates/`:

| Target | Template |
|---|---|
| Spring service / orchestrator / coordinator | `.claude/templates/service.java.tmpl` |
| Test class | `.claude/templates/test.java.tmpl` |

`{{placeholders}}` must be replaced with actual values. Do not copy
blindly — read nearby code in the target package to align with local
conventions (auto-config vs plain `@Component`, constructor vs field
injection).

## When writing tests

Check `.claude/test-scenarios/` for applicable scenarios that must be
covered. Match by `module` or `type`. If a scenario has
`Coverage: [ ]` (uncovered), add the corresponding test and flip it to
`[x]`.

When adding a new feature, create a `test-scenarios` entry for each P0
path (null/absent collaborator, boundary, concurrency, exception
propagation) even if the test is written immediately — this builds the
regression net.

## Scenario conventions

- One file per scenario, frontmatter with `id`, `title`, `module`,
  `priority` (P0/P1/P2), `type` (resource/concurrency/boundary/security),
  `tags`.
- Sections: Description / Input Conditions / Expected Behavior /
  Test Location / Coverage.
- Scenarios capture *runtime-specific* hazards (ObjectMapper reuse,
  Gson boundary isolation, thread-pool naming) — generic CodeArts rules
  stay in coding-standard.md.
