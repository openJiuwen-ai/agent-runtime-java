@AGENTS.md

# Claude Code Notes

- Keep shared project rules in `AGENTS.md` so all coding agents use the
  same architecture guidance.
- Use this file only for Claude-specific imports or workflow notes.
- Detailed rules by topic: see `.claude/rules/` (architecture,
  coding-standard, concurrency, dependency-management, json-serialization,
  logging, test-flow, security).
- Skills for workflow guides: see `.claude/skills/`
  (using-devflow, devflow-specify/design/tdd/clean-code/review/fix/ship,
  team-skill-creator).
- Templates for new code/tests: see `.claude/templates/`; regression net
  scenarios: `.claude/test-scenarios/`.
- Permissions, env vars, and model defaults: see `.claude/settings.json`.
