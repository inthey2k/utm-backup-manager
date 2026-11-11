# Roadmap

UTM Backup Manager is under active development.

The roadmap shows released milestones as well as planned features and larger improvements.
Priorities and version assignments may change as the project evolves.

## 1.1.0 – Backup workflow improvements (Released)

- Background backup jobs
- Backup progress display
- Cancellation of running backups
- VM size calculation
- Improved backup handling and validation
- Additional automated tests
> No release notes written for this version

## 1.2.0 – UI, configuration and retention preparation (Released)

- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.2.0](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.2.0)

## 1.3.0 – Automatic retention cleanup (Released)

- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.3.0](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.3.0)

## 1.4.0 – Cleanup and operational visibility (Released)

For a complete list of changes see published GitHub release notes:

[Release notes for v1.4.0](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.4.0)


## 1.5.0  Multi-provider support and QEMU integration        
For a complete list of changes see published GitHub release notes:
[Release notes for v1.5.0](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.5.0)

## 1.5.1  UI and provider workflow refinements              Released
For a complete list of changes see published GitHub release notes:
[Release notes for v1.5.1](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.5.1)

## 1.5.2  Stable VM identity and history migration          Released
For a complete list of changes see published GitHub release notes:
[Release notes for v1.5.2](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.5.2)

## 1.5.3  Manual cleanup and deletion reliability           Released
For a complete list of changes see published GitHub release notes:
[Release notes for v1.5.3](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.5.3)


## 1.5.4  Backup progress and storage safety
       - live per-VM transfer progress
       - free backup-root disk space
       - selected backup size vs available space
       - conservative preflight warning/blocking
       - sparse-aware sizing where possible
       - backup size/statistics groundwork

## 1.6.0 – Backup restore

- Restore a VM from an existing backup
- Only allow restore operations while the affected VM is stopped
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
- Improved backup history and statistics
- More detailed job history
- Further automation around backup maintenance
- Investigate platform-specific sparse file preservation during VM backup to minimize file sizes
- OpenAPI/Swagger reintroduction, after removing it because of security vulnerabilities
- Toolbar/UI consolidation
- Browser auto-open upon startup of the application
- Sparse-file preservation improvements
