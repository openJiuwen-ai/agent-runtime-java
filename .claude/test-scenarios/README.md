# Test Scenarios

P0 regression-net scenarios for agent-runtime-java. Each scenario
describes a hazard class; tests that cover it are listed under Test
Location. Flip `Coverage: [ ]` to `[x]` when a test exists.

| Id | Scenario |
|---|---|
| 001 | Null / absent optional collaborator must be a supported state |
| 002 | ObjectMapper singleton reuse |
| 003 | Gson/Jackson boundary isolation (A2A protocol boundary only) |
| 004 | Thread pool creation discipline |

Scenario vs case: a scenario is a hazard that *might* break (upfront
regression net). An incident that *did* break belongs in a `cases/`
entry (post-hoc archive) — see templates-and-cases.md.
