---
description: Stable provider-independent VM identity and migration work for UTM Backup Manager 1.5.2. Use for canonical VM IDs, UTM UUIDs, generated IDs for providers without native UUIDs, VM-IDENTITIES.json, rename/history merging, state migration, and retention recalculation.
---

# VM identity migration — 1.5.2

## Goal

Introduce a provider-independent stable VM identity layer.

Canonical identity:

`providerId:stableLocalId`

Display names, paths, package names, and directory names are mutable attributes and must not be permanent identity.

## Identity resolution

Each provider resolves a discovered VM to a stable local ID:

1. Prefer a provider-native stable ID when available.
   - UTM: use its native UUID.
2. If no suitable native ID exists:
   - generate a UUID once,
   - persist it,
   - reuse it for later discoveries.
3. Preserve historical aliases/locators so renames or relocations do not automatically create a new logical VM.
4. Never guess identity when matching is unsafe.

Keep provider-specific discovery/fingerprinting out of generic services. Generic code should consume canonical identity plus current display/locator metadata.

## Provider-independent identity registry

Create a versioned, extensible registry:

`backup.root/VM-IDENTITIES.json`

Design it for current and future providers, not specifically for UTM.

It should support concepts equivalent to:

- schema version
- provider ID
- canonical stable local ID
- identity source such as `PROVIDER_NATIVE` or `GENERATED`
- historical aliases/provider-local IDs
- current locator
- historical/current display names
- optional provider-specific fingerprint data
- detection/migration metadata

Read/write defensively and atomically where appropriate.

## UTM

- UTM exposes a native UUID; use it as canonical stable identity.
- Inspect historical `.utm/config.plist` files for the original UUID and use it for attribution where reliable.
- Package names such as `Linux Mint 23.utm` and `Linux Mint 27.utm` become aliases/locators rather than canonical identity.
- If differently named histories have the same UTM UUID, expose one logical VM with one combined chronologically correct history under the current display name.
- Key UTM runtime status by UUID rather than display name.

## Providers without a native UUID

The architecture must support providers such as QEMU without another identity-layer redesign.

For QEMU, inspect whether a reliable stable identifier already exists in current configuration/storage. If not, use or prepare support for a Backup-Manager-generated persistent UUID with safe rediscovery.

Possible rediscovery inputs include persisted aliases, stable provider metadata, configuration fingerprints, storage metadata, or an appropriate sidecar identity. Do not rely on fragile heuristics.

Keep QEMU behavior compatible and do not introduce regressions.

## Historical data and state

- Existing backup paths/directories and manifests remain valid.
- Do not rename/move physical backup data merely to adopt canonical IDs.
- Merge histories logically through identity resolution.
- Preserve Stable flags, notes, timestamps, paths, and metadata.
- Migrate or alias `vm_state`/change tracking so identity migration does not make existing VMs falsely appear changed.
- Migration must be idempotent.

## Retention and hard safety rule

IDENTITY MIGRATION MUST NEVER DELETE, MOVE, OR RENAME EXISTING BACKUP DATA.

Retention operates on the combined canonical VM history. If a merge exceeds retention:

- recalculate deletion candidates,
- mark/report candidates only,
- do not physically delete during migration.

Actual deletion may happen only through a later normal retention/backup workflow.

If attribution is uncertain, preserve the data unresolved rather than guessing.

## Compatibility

- Existing legacy backups remain readable.
- Fresh installations work without migration data.
- Multi-provider architecture remains intact.
- Existing storage/write guards remain in force.
- Avoid unrelated frontend changes; only adapt presentation if necessary to show one merged logical VM/history.

## Required tests

Add focused regression/characterization tests for at least:

- provider-native canonical identity
- generated/persisted identity path
- alias resolution
- rename without identity change
- provider isolation
- identical local values in different providers do not collide
- UTM native UUID identity
- UTM runtime status by UUID
- historical UTM UUID attribution
- merged differently named histories with the same UUID
- current display name after rename
- preservation of Stable flags and notes
- retention across merged canonical history
- no physical deletion during migration
- `vm_state`/change-tracking continuity
- migration idempotency
- QEMU compatibility
- legacy backup readability

Use characterization tests first where existing behavior needs protection.

## Workflow

Before implementation:

1. Inspect relevant provider, inventory, scanner, storage, manifest/metadata, state, retention, and test code.
2. Briefly state the proposed identity model, migration approach, affected classes/files, and any uncertainty discovered from the current code.
3. Then implement without waiting for another approval unless the actual code reveals a materially destructive or incompatible choice not covered here.

During implementation, use focused tests.

At the end run `mvn clean verify` once and report:

- architecture/design used
- files changed
- migration behavior and safety guarantees
- tests added/changed
- final verification result
- remaining limitations/follow-up work

Do not commit or push.
