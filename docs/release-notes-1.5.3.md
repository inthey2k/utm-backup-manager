# UTM Backup Manager 1.5.3

## Overview

Version 1.5.3 adds manual backup cleanup and improves the reliability of retention-based deletion.

Backups that exceed the configured retention limit can now be deleted manually without waiting for another backup run. In addition, individual backups can be selected and deleted directly from the overview.

This release also fixes retention cleanup after the stable-identity migration introduced in 1.5.2 and improves deletion of older backup formats, especially on macOS-mounted NTFS volumes.

## Highlights

### Manual retention cleanup

- Added a new **Clean up backups** action.
- Backups that currently exceed the configured retention limit can be deleted immediately.
- The candidates are calculated by the backend at execution time.
- Stable backups are never included in retention cleanup.
- A confirmation dialog shows the backups that will be permanently deleted before cleanup starts.

### Manual backup selection

- Added a selection mode for deleting individual backups.
- Multiple backups can be selected and deleted in one operation.
- Stable backups cannot be selected for deletion.
- Cancelling the confirmation dialog keeps the current selection so it can still be adjusted.
- Leaving selection mode clears the current selection.
- Attempting to delete without selecting a backup shows an informational message instead of submitting an empty request.

### Safer bulk deletion

- The complete selection is validated before the first backup is deleted.
- Unknown backup IDs, Stable backups, unsafe paths, and read-only backup storage stop the operation before any deletion starts.
- Duplicate backup IDs are processed only once.
- Manual deletion cannot run concurrently with the automatic retention cleanup job.

### Retention cleanup after VM identity migration

- Fixed automatic retention cleanup for VMs whose local provider identity differs from their canonical identity.
- Retention cleanup now resolves VM identities consistently before matching backup histories.
- Renamed VMs and migrated backup histories continue to use their combined canonical history when retention is calculated.

### Legacy backup cleanup

- Improved deletion of backups created with older backup formats.
- Deleting one VM from a multi-VM legacy batch preserves the remaining VM backups.
- Legacy auxiliary data such as the old `VM Shared Folder` remains untouched while other VM backups still exist in the batch.
- When the last VM backup is removed, the complete batch directory and remaining legacy content are removed recursively.
- Non-VM files such as `.DS_Store` no longer keep an otherwise empty batch alive.

The current Java backup implementation already stores VM data only. The legacy `VM Shared Folder` originated from older backup workflows and is not created by current backups.

### Robust deletion on macOS / NTFS volumes

- Recursive deletion now tolerates files that disappear while the directory tree is being traversed.
- This fixes failures observed with macOS AppleDouble metadata files such as `._.DS_Store` on NTFS volumes mounted through Mounty.
- `NoSuchFileException` for an already vanished entry is treated as an already completed deletion.
- Other I/O errors, including permission errors, continue to abort the operation.
- File and directory cleanup now uses safe `deleteIfExists` semantics where appropriate.

### Improved deletion logging

- Added server-side logging for backup deletion.
- Logs now include:
    - the backup ID and path when deletion starts
    - successful VM deletion
    - complete batch removal
    - failures with the affected backup and full exception stack trace
- Browser error messages remain concise and do not expose internal stack traces.

This logging helped identify and reproduce a real deletion failure on a macOS-mounted NTFS backup volume.

## Reliability and compatibility

- Existing Format 1 / legacy backups remain readable and deletable.
- Current Format 2 backups continue to use the existing deletion behavior.
- Stable protection remains enforced for both individual and bulk deletion.
- Canonical VM identity handling introduced in 1.5.2 remains intact.
- UTM and QEMU backups were successfully tested with the new manual deletion workflow.
- Real-system testing confirmed:
    - manual cleanup of retention candidates
    - deletion of multiple manually selected backups
    - deletion of UTM backups
    - deletion of QEMU backups
    - automatic removal of batches after their last VM backup
    - successful cleanup on an NTFS backup volume mounted through Mounty

## Build and quality

- Added regression tests for manual cleanup, legacy backup deletion, bulk deletion, Stable protection, unsafe paths, read-only storage, and disappearing filesystem entries.
- Final test suite: **597 tests, 0 failures, 0 errors**
- SonarQube Quality Gate: **Passed**
- SonarQube open issues: **0**

## Upgrade notes

No manual migration is required.

Existing backup data and metadata remain compatible with 1.5.3.

Legacy backup batches may still contain data from older backup workflows such as `VM Shared Folder`. These directories do not need to be removed manually. When the last VM backup in such a batch is deleted, the remaining legacy batch contents are removed automatically.

As always, Stable backups remain protected from automatic and manual deletion.