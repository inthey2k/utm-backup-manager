---
description: Project test and build strategy. Use when implementing code changes, adding tests, debugging test failures, or deciding which Maven verification command to run.
---

# Testing and build strategy

Use the smallest useful verification step while implementing changes.

- Run focused tests for the behavior being changed, for example:
  `mvn -Dtest=VmInventoryTest test`
- Use `mvn test` when several related test classes are affected.
- Use `mvn verify` when broader verification is useful but a clean build is unnecessary.
- Do not run `mvn clean verify` after every edit.
- For a substantial completed implementation phase, run `mvn clean verify` once as final verification unless there is a concrete reason not to.
- If the final command already contains a clean build, for example `mvn clean verify sonar:sonar ...`, do not run a separate `mvn clean verify` immediately beforehand.
- Documentation-only changes do not require a new full clean build when code has already been verified.
- When a focused test fails, fix and rerun that focused test before repeatedly running the entire suite.

Always report the verification commands actually run and their results. Never claim verification that was not performed.
