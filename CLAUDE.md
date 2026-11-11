# UTM Backup Manager — Claude Code Instructions

## Project context

UTM Backup Manager is a Java 21 / Spring Boot application for managing local VM backups through a browser-based interface. It has a multi-provider architecture and currently supports UTM and QEMU. It is primarily a user-operated enthusiast tool, not an enterprise backup platform.

Read the relevant code, tests, `README.md`, and `ROADMAP.md` before making substantial changes. Treat the implementation as the source of truth and verify assumptions against it.

## Core development rules

- Follow the existing architecture, Spring Boot patterns, naming, and formatting.
- Keep changes focused; avoid unnecessary dependencies and unrelated refactoring.
- Preserve existing behavior unless the task explicitly requires a change.
- Write source comments, JavaDoc, and technical documentation in English.
- Add or update focused tests for changed behavior where practical.
- Keep affected documentation consistent with implemented behavior.
- Distinguish confirmed defects from hypothetical risks and do not overstate non-critical issues.
- Never weaken backup integrity, retention, Stable-backup protection, or other existing safety guards.

## Git workflow

- Work only in the local Alpine clone under `~/Projects/utm-backup-manager`.
- Never work directly in `/mnt/host/projects/utm-backup-manager`.
- Before starting a task:
  - `git fetch origin`
  - switch to the user-specified source branch
  - update it with `git pull --ff-only`
- Create a dedicated work branch named:
  `claude/<release>-<short-task-name>`
- Never make changes directly on `main`.
- Never commit, push, merge, rebase, reset, or delete branches unless explicitly requested by the user.
- If the source branch has diverged from origin, stop and report it instead of resolving it automatically.

## Data and credentials

- Do not modify or delete real VM backups, VM files, or production backup metadata unless explicitly instructed.
- Prefer mocks, fixtures, and temporary directories for tests.
- Never expose credentials or private configuration values.

## Communication

- Be direct and specific.
- For substantial architectural changes, briefly state the proposed design and affected areas before implementation.
- At the end of implementation work, summarize changed files, verification actually run, remaining caveats, and relevant documentation updates.
