---
description: SonarQube workflow for this project. Use when the user asks to run, inspect, explain, or fix SonarQube analysis, Quality Gate failures, coverage, duplication, security findings, or code-quality issues.
---

# SonarQube workflow

- Use `SONAR_HOST_URL` and `SONAR_TOKEN` from the environment. Never print or persist their values.
- Treat SonarQube findings as evidence to investigate, not automatically as defects.
- Inspect the relevant code before changing it.
- Prefer the smallest correct fix and avoid broad refactoring solely to silence a rule.
- Preserve behavior and add/update tests where the fix changes executable logic.
- For concurrency findings, use genuinely thread-safe state handling rather than relying on `volatile` for mutable collections.
- When analysis is run, report the Quality Gate/result and any remaining relevant issues.
- Do not commit generated Sonar artifacts. Local `.sonar/` content belongs outside version control.
