---
description: Documentation and review-report conventions. Use when updating README, ROADMAP, release notes, contributor documentation, technical docs, or when producing a substantial architecture/code/security review.
---

# Documentation conventions

- Technical documentation is written in English.
- Keep `README.md`, `ROADMAP.md`, release notes, configuration examples, and implementation behavior consistent.
- Update documentation only when affected by the task; avoid unrelated cleanup.

## Substantial review reports

For substantial completed analyses such as architecture reviews, code-quality assessments, security reviews, or focused technical investigations:

- Save a readable Markdown report under `docs/reviews/` unless the user explicitly requests no file changes.
- Use `YYYY-MM-DD-review-topic.md`.
- Include date, scope, concrete findings/evidence, impact, and practical recommendations.
- Distinguish confirmed defects, plausible risks, and optional improvements.
- Do not create a report for quick questions or minor code explanations.
- Do not overwrite an existing report without permission.
- Include the report path in the final response.

A read-only code review may write only the requested report under `docs/reviews/`. If the user explicitly requests no file modifications at all, return the review in the conversation instead.
