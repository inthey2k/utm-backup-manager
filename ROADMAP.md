# Roadmap

UTM Backup Manager is under active development.

The roadmap shows released milestones as well as planned features and larger improvements.
Priorities and version assignments may change as the project evolves.

## 1.1.0 – Backup workflow improvements (Released)
- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.1.0](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.1.0)

## 1.2.0 – UI, configuration and retention preparation (Released)
- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.2.0](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.2.0)

## 1.3.0 – Automatic retention cleanup (Released)
- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.3.0](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.3.0)

## 1.4.0 – Cleanup and operational visibility (Released)
- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.4.0](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.4.0)

## 1.5.0 – Multi-provider support and QEMU integration (Released)
- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.5.0](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.5.0)

## 1.5.1 – UI and provider workflow refinements (Released)
- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.5.1](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.5.1)

## 1.5.2 – Stable VM identity and history migration (Released)
- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.5.2](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.5.2)

## 1.5.3 – Manual cleanup and deletion reliability (Released)
- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.5.3](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.5.3)

## 1.5.4 – UI refinements and backup visibility (Released)
- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.5.4](https://github.com/inthey2k/utm-backup-manager/releases/tag/v1.5.4)

## 1.5.5 – Backup storage safety (Planned)

- Show free disk space on the filesystem containing the backup root
- Show the available space and total estimated size of selected VMs in the backup dialog
- Compare the estimated backup size to the usable space on the target filesystem
- Warn or prevent backup creation when available space is insufficient, using a conservative safety margin
- Investigate sparse-aware size estimation without underestimating the space required by the actual copy operation
- Extend backup size statistics with aggregate sizes per VM and for the entire backup storage

## 1.6.0 – Backup restore

- Restore a VM from an existing backup
- Only allow restore operations while the affected VM is stopped or no local VM exists
- Distinguish locally available VMs from VMs that exist only as backups
- Support restoring archived VMs without a corresponding local VM while preserving their canonical identity
- Preserve the current local VM before restoring
  - rename the current VM directory instead of overwriting it
  - register the renamed directory as a local pre-restore backup
  - show local pre-restore backups in the web UI
- Allow local pre-restore backups to be deleted manually
- Allow restoring a local pre-restore backup
  - do not create another safety backup when restoring a local pre-restore backup
- Add robust restore error handling
  - validate target path, permissions and available disk space before starting where possible
  - copy restored data into a temporary/incomplete restore directory first
  - move the restored VM into its final location only after a successful copy
  - keep the local pre-restore backup untouched if the restore fails
  - clean up incomplete restore data when possible without hiding the original error
  - clearly report failures such as insufficient disk space or write errors in the UI
- Add restore progress and final status reporting
- Add confirmation dialogs before restore and deletion operations
- Add automated tests for successful, failed and interrupted restore operations

## 1.7.0 – Linux support

- Verify the complete application workflow on Linux
- Remove remaining macOS-specific assumptions outside provider implementations
- Verify QEMU operation on Linux
- Extend installation and configuration documentation for Linux

## Outlook

- Additional VM providers
- Explore support for Docker and Podman container backups, including persistent volumes and consistent backup strategies
- Improved backup history and statistics
- More detailed job history
- Persistent, dismissible job completion summaries with start time, end time and duration
- Further automation around backup maintenance
- Investigate platform-specific sparse-file preservation during VM backup to reduce actual storage consumption
- Reintroduce OpenAPI/Swagger when a dependency version without the known security vulnerabilities becomes available
  - integrate automated OpenAPI specification generation into the release script
- Toolbar/UI consolidation
- Browser auto-open upon startup of the application
