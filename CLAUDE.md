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

## Working tree and Git

- You operate on the original Git working tree shared from the macOS host. Changes are immediately visible in IntelliJ IDEA.
- The project owner handles Git operations.
- Do not create branches/worktrees, stage, commit, push, reset, revert, tag, release, or run release/deployment scripts unless explicitly requested.
- Preserve user changes and untracked files. Never clean, overwrite, or discard them without explicit approval.
- Leave implementation changes uncommitted for review.
- Do not modify files outside this repository without permission.

## Data and credentials

- Do not modify or delete real VM backups, VM files, or production backup metadata unless explicitly instructed.
- Prefer mocks, fixtures, and temporary directories for tests.
- Never expose credentials or private configuration values.

## Communication

- Be direct and specific.
- For substantial architectural changes, briefly state the proposed design and affected areas before implementation.
- At the end of implementation work, summarize changed files, verification actually run, remaining caveats, and relevant documentation updates.
