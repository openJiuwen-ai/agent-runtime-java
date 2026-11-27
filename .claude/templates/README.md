# Templates

Code/test scaffolding for AI-assisted development. `{{placeholders}}`
must be replaced; read nearby code before instantiating — local
conventions win over templates.

| Template | Use |
|---|---|
| `service.java.tmpl` | Spring service / coordinator / plain component (constructor injection, ObjectProvider optionality, SLF4J) |
| `test.java.tmpl` | Unit test (programmatic Mockito, `methodName_scenario_expected`, absent-collaborator path) |

For an auto-configuration class, do NOT use a template — copy the
nearest existing `*AutoConfiguration` and keep: registration in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
`@ConditionalOnMissingBean` before `@ConditionalOnProperty`.
