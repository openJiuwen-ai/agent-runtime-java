# Contributing to openJiuwen Agent Runtime Java

Thank you for your interest in contributing. This repository packages **Agent Core** into a deployable **Agent Service** (HTTP + A2A). Contributions may target runtime code, adapters, documentation, or examples.

## Before You Start

- **Agent Core** changes usually belong in [agent-core-java](https://gitcode.com/openJiuwen/agent-core-java). This repo should stay focused on service ingress, orchestration, and adapters.
- Read [README.md](README.md) and the [development guide](documents/zh/2.开发指南/README.md) (Chinese) for architecture boundaries.

## Development Setup

1. Install **Java 17+** and **Maven 3.9+**.
2. Build and install matching **agent-core-java** (Maven `0.1.12`, branch `feature/630` — see root `pom.xml`):

   ```bash
   git clone https://gitcode.com/openJiuwen/agent-core-java.git
   cd agent-core-java
   git checkout feature/630
   mvn install -DskipTests
   ```

3. Clone and build this repository:

   ```bash
   git clone https://gitcode.com/openJiuwen/agent-runtime-java.git
   cd agent-runtime-java
   mvn clean install -DskipTests
   ```

## Running Tests

From the `service` directory:

```bash
cd service
mvn clean test
```

Run a single module (with dependencies):

```bash
mvn -pl agent-service-app -am clean test
```

Run the demo locally:

```bash
mvn -pl agent-service-demo -am spring-boot:run
```

Some integration tests may require Redis or other middleware; check test class names (`*IntegrationTest`, `*IT`) and module READMEs if a test fails locally.

## Code Guidelines

- Follow existing module boundaries: `spec` → `adapters` → `app` (no reverse dependencies).
- HTTP controllers must call **`ServeOrchestrator`**, not Core `Runner` directly.
- Match naming and style in the module you edit; avoid drive-by refactors.
- Keep public API changes in `agent-service-spec` deliberate and documented.

## Documentation

- User-facing guides live under `documents/zh/`. Update the relevant page and [documents/zh/SUMMARY.md](documents/zh/SUMMARY.md) when adding new topics.
- Update [README.md](README.md) / [README.zh.md](README.zh.md) when changing scope, default behavior, or quick-start steps.

## Pull Requests

1. Fork the repository and create a feature branch from `develop` (or the branch indicated by maintainers).
2. Ensure `mvn clean test` passes in `service/`.
3. Describe **what** changed and **why**; link related issues if any.
4. For behavior changes, note impact on HTTP paths, A2A, or configuration keys.

## Issues

When reporting bugs, include:

- Java and Maven versions
- Steps to reproduce (commands, config snippets)
- Expected vs actual behavior
- Relevant logs (trimmed)

For feature requests, explain the use case and whether it fits this repo vs agent-core-java.

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
