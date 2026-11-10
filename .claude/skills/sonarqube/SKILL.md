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
- The repository may live on a slow shared filesystem such as `/mnt/host`. Always run Maven verification and SonarQube analysis with: `-Dcustom.build.directory="$HOME/.cache/utm-backup-manager/target"` so Maven build output stays on the local Alpine filesystem.
- Use this command to generate Sonar reports: 
- ```bash 
  mvn -B \
  -Dcustom.build.directory="$HOME/.cache/utm-backup-manager/target" \
  clean verify sonar:sonar \
  -Dsonar.projectKey=utm-backup-manager \
  -Dsonar.host.url="$SONAR_HOST_URL" \
  -Dsonar.token="$SONAR_TOKEN"
- Do not use very large `timeout` values assuming milliseconds; GNU/BusyBox timeout uses seconds unless a suffix is given.