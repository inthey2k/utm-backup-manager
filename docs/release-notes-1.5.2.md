# UTM Backup Manager 1.5.2

## Overview

Version 1.5.2 introduces stable, provider-independent VM identities and improves the migration of existing backup histories 
across VM renames and provider-specific identifier changes.

The main goal of this release is to ensure that a VM keeps the same logical identity even when its display name, package 
name, or directory name changes. I became aware of this issue after renaming a VM in UTM and then having two columns in 
backup manager. The backups are now grouped together, even if for different VM names.

## Highlights

### Stable VM identities

- Added a provider-independent stable identity model based on:
  - native provider IDs where available
  - generated persistent IDs where required
- Canonical VM identity is now based on `providerId:stableLocalId`.
- Display names, package names, and directory names are treated as mutable attributes rather than permanent identifiers.
- Added an explicit stable-identity contract for VM providers.

### UTM identity migration

- UTM VMs now use their native UUID as the stable identity.
- UUIDs are read from `Information -> UUID` in UTM `config.plist`.
- Historical UTM backups are attributed to the same VM through their stored UUID where available.
- Backup histories created before and after a VM rename are merged logically.

Example:

- `Linux Mint 23`
- `Linux Mint 27`

If both belong to the same UTM UUID, they are now displayed as one VM with one combined backup history.

### QEMU stable identities

- QEMU VMs now receive a generated persistent UUID.
- The identity is stored in a hidden sidecar file inside the VM directory:
  `.utm-backup-manager-identity`
- The identity survives:
  - application restarts
  - directory renames
  - display-name changes
- Existing disk images are not modified.

### VM identity registry

- Added `VM-IDENTITIES.json` in the configured backup root.
- The registry stores canonical identities, aliases, historical names, current locators, and identity metadata.
- Reads are defensive and writes are atomic.
- Unsupported or invalid registry files are left untouched.

### Historical backup migration

- Existing backup directories and manifests are not renamed or moved.
- Historical backups are attributed logically to their canonical VM identity.
- Existing Stable flags, notes, timestamps, paths, and metadata are preserved.
- Migration is idempotent.

### Retention after identity merging

- Retention is recalculated across merged backup histories.
- If a merged history exceeds the configured retention limit, backups are only marked as deletion candidates.
- Identity migration never deletes backup data.
- Physical deletion remains part of the normal retention workflow.

### Change tracking

- Existing VM state continues to work after identity migration.
- Historical aliases are used as fallback keys.
- Existing VMs are not reported as changed solely because their canonical identity was migrated.

### Runtime status

- UTM runtime status is now matched by UUID instead of display name.
- QEMU runtime behavior remains compatible with the new stable identity model.

## Reliability and compatibility

- Existing backup formats remain readable.
- Fresh installations work without migration data.
- Existing multi-provider behavior remains intact.
- New providers must explicitly declare how they obtain a stable VM identity.
- Real-system testing confirmed:
  - UTM rename histories are merged correctly
  - QEMU VMs are no longer duplicated after identity migration
  - retention is calculated correctly across merged histories

## Build and quality

- Updated test dependencies to remove vulnerable transitive test-only dependencies.
- `json-smart` updated to `2.6.1`.
- Replaced legacy `android-json` usage with `org.json`.
- XML parser error handling was improved so expected malformed-plist test cases no longer write noisy fatal parser messages to stderr.
- SonarQube Quality Gate: Passed
- SonarQube issues: 0
- Final test suite: 549 tests, 0 failures, 0 errors

## Known limitations

- Read-only QEMU VM directories cannot store the persistent identity sidecar. In that case, the directory name is used as fallback identity.
- Cloned QEMU VM directories copy the identity sidecar and therefore initially share the same identity until one clone receives a new identity.
- QEMU VMs renamed before upgrading to 1.5.2 cannot always be matched reliably to older backups if no stable identity information existed at the time.
- Binary UTM plist files still fall back to the compatibility path if their UUID cannot be read safely.

## Upgrade notes

No manual migration is required.

On first startup with 1.5.2:

- stable identities are discovered or generated
- `VM-IDENTITIES.json` is created when required
- historical backups are attributed to their canonical VM identity
- merged histories are recalculated without deleting any backup data

As always, backup data is never deleted as part of identity migration.
