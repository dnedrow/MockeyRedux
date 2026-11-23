# Agent Instructions

## Project Overview

Mockey Redux is a Kotlin rewrite of Mockey, organized as six Gradle modules:

- `mockey-core`: engine, domain model, routing, responses, and ports
- `mockey-api`: admin API wire types
- `mockey-legacy`: legacy definition import
- `mockey-server`: mock and admin server
- `mockey-ui`: server-rendered UI
- `mockey-parity-tests`: black-box parity testing

## Development Conventions

- Use Kotlin on JDK 21 through the Gradle toolchain.
- Keep `mockey-core` independent of the other project modules.
- Keep `mockey-ui` dependent on `mockey-api` only; it must not depend on `mockey-core`.
- Use the existing convention plugin in `build-logic` for JVM modules.
- Use explicit API mode in `mockey-core` and `mockey-api`.
- Use JUnit 5 and `kotlin.test` for tests.
- Run ktlint through the existing Gradle convention.
- Prefer small, focused changes and preserve the module dependency boundaries.

## Validation

Run the smallest applicable Gradle check for a change. The standard project checks are:

```shell
./gradlew build
./gradlew check
```

The root `checkModuleDependencies` task enforces the approved project dependency graph.

## GitHub Actions

Use direct setup actions in GitHub Actions workflows, such as `actions/setup-java`. Do not substitute them with
internal setup action versions, such as `internal/actions/setup-java`.

## OpenSpec

Implementation work tracked by OpenSpec belongs in the active change under
`openspec/changes/`. Read the change artifacts before implementing tasks, update
the task checkbox only after the behavior is complete, and use the OpenSpec
validation commands when appropriate.

## Commits

Use Conventional Commits for all commit messages, such as:

```text
feat(core): add request routing
fix(server): preserve upstream response headers
docs: update deviation register
chore(build): update Gradle conventions
```

When creating commits, include the repository-required Copilot co-author trailer
unless the user explicitly asks otherwise:

```text
Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```
