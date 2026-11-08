---
description: Safety rules for backup storage, retention, deletion, Stable flags, migrations, and metadata changes. Use whenever work can affect backup attribution, retention, deletion, storage layout, manifests, state, or real VM backup data.
---

# Backup safety rules

Backup data is the user's primary asset. Prefer conservative behavior when attribution or migration is uncertain.

## Hard rules

- Never delete, move, rename, truncate, or rewrite real backup data as a side effect of migration unless the user explicitly authorizes that operation.
- Never silently weaken Stable-backup protection, retention guards, storage guards, or backup-integrity checks.
- Preserve existing Stable flags, notes, timestamps, paths, manifests, and other metadata unless a change is explicitly required.
- Existing backup formats must remain readable unless the task explicitly introduces a documented incompatible format change.
- Migration code must be idempotent.
- If identity or attribution cannot be established safely, preserve the data and leave it unresolved rather than guessing.
- Tests must use temporary storage, fixtures, or mocks instead of real backup roots.

## Retention during migration

Identity/metadata migration is attribution-only.

If a migration changes the logical history and causes retention limits to be exceeded:

- recalculate candidate status,
- mark/report deletion candidates if the architecture supports it,
- do not physically delete them during migration.

Physical deletion may occur only through the normal explicitly supported retention/backup workflow after migration.
