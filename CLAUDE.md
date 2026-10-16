# UTM Backup Manager — Claude Code Instructions

## Project context

UTM Backup Manager is a Java 21 / Spring Boot application for managing local UTM virtual-machine backups through a browser-based interface. It is intended primarily for hobbyists and ambitious VM enthusiasts ("VM herders"), not as an enterprise backup platform.

Read `README.md`, `ROADMAP.md`, and the relevant code and tests before proposing changes. Treat the existing implementation and documentation as the source of truth; verify assumptions against the code.

## Development priorities

1. Complete planned features and improve the user experience.
2. Keep contributor-facing documentation accurate, clear, and welcoming.
3. Address non-critical refactoring, robustness, concurrency, and security hardening in later maintenance releases.

This prioritization reflects a locally run, user-operated tool. Do not present every theoretical edge case as an urgent blocker. Nevertheless, flag credible data-loss risks clearly and distinguish confirmed defects from hypothetical scenarios. Never weaken existing backup protections.

## Code and documentation conventions

- Use Java 21 and follow the project's established Spring Boot patterns, architecture, naming, and formatting.
- Keep changes focused and maintainable; avoid unnecessary dependencies or large rewrites.
- Write source comments, JavaDoc, and technical documentation in **English**.
- Keep comments concise. Describe what a class or method does and explain *why* an implementation choice matters; add inline comments only for non-obvious logic.
- Preserve existing behavior unless the task explicitly calls for a change.
- Add or update focused regression tests for bug fixes and tests for new behavior where practical.
- Run relevant tests and report what passed, failed, or could not be run. Do not claim verification without actually performing it.
- Keep `README.md`, `ROADMAP.md`, release notes, and other affected documentation consistent with implemented behavior.

## Working safely

- You operate on the **original Git working tree** shared from the macOS host. Edits are immediately visible to the developer in IntelliJ; this is not a disposable copy.
- The project owner handles all Git operations. Do not run Git commands, create branches or worktrees, stage, commit, push, reset, or revert unless explicitly requested.
- Edit the existing working tree directly; do not require a separate Git worktree.
- Preserve existing user changes and untracked files; never discard, overwrite, or clean them without explicit approval.
- Leave all changes uncommitted for review in IntelliJ IDEA, and summarize created, modified, or deleted files when finished.
- Do not create releases or tags or invoke deployment/release scripts without explicit authorization.
- Do not change files outside this repository without permission.
- Do not delete or modify real VM backups, backup metadata, or UTM VM files unless explicitly instructed. Prefer mocks, fixtures, and temporary test directories.
- Do not expose credentials or private configuration values in code, logs, documentation, or commits.
- For substantial architectural changes or potentially destructive operations, explain the proposed approach and get approval before implementation.
- Never silently loosen safeguards around stable backups, retention, deletion, or backup integrity.

## Reviews and analysis reports

For **substantial completed analyses** (such as architecture reviews, code-quality assessments, security reviews, or focused technical investigations):

- Save a readable Markdown report in `docs/reviews/` as part of the requested deliverable, unless the user explicitly asks not to write files.
- Use a descriptive name in the format `YYYY-MM-DD-review-topic.md`. Create `docs/reviews/` if needed.
- Include the date, scope, findings with concrete evidence (file paths and line references when useful), impact, and practical recommendations.
- Clearly distinguish **confirmed defects**, **plausible risks**, and **optional improvements**. Prioritize them according to this project's actual use case, rather than generic enterprise standards.
- Do not overwrite an existing report without permission. Do not create a report for every quick question or minor code explanation.
- Include the saved report path in your final response.

**Read-only review convention:** A request for a *read-only code review* forbids changes to application code, configuration, and tests, but **permits writing the requested report under `docs/reviews/`**. If the user explicitly requests *no file modifications at all*, do not save a report; present it in the conversation instead.

## Communication

- Be direct, specific, and collaborative. Explain trade-offs without overstating risk.
- When identifying a defect, cite the relevant code and suggest a way to reproduce or test it.
- Separate observations from assumptions; ask when requirements are genuinely ambiguous.
- Before broad refactoring, propose a small, reviewable plan.
- At the end of implementation tasks, summarize changed files, tests run, remaining caveats, and any follow-up documentation updates.
