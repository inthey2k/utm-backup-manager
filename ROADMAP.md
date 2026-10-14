# Roadmap

UTM Backup Manager is under active development.

The roadmap shows released milestones as well as planned features and larger improvements.
Priorities and version assignments may change as the project evolves.

## 1.1.0 – Backup workflow improvements (✅ Released)

- Background backup jobs
- Backup progress display
- Cancellation of running backups
- VM size calculation
- Improved backup handling and validation
- Additional automated tests
> No release notes written for this version

## 1.2.0 – UI, configuration and retention preparation (✅ Released)

- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.2.0](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.2.0)

## 1.3.0 – Automatic retention cleanup (✅ Released)

- For a complete list of changes see published GitHub release notes:

  [Release notes for v1.3.0](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.3.0)

## 1.4.0 – Cleanup and operational visibility

- Show retention cleanup status in the web UI
  - running
  - completed
  - failed
- Highlight backups pending deletion during a running backup job
- Add a warning icon to retention candidates
- Show a persistent backup completion message
  - start time
  - finish time
  - total duration
  - manual dismissal only
- Improve error reporting for background cleanup jobs
- Improve compatibility with recent Java versions
  - handle SQLite JDBC native-access requirements
  - eliminate native-access warnings where possible
- Review and clean up remaining IntelliJ inspection warnings
- Further UI polish and usability improvements
- Extend automated tests where useful

## 1.5.0 – Configuration, startup and administration

- Automatically open the system default browser after application startup
  - open the backup overview page
  - make browser auto-start configurable for headless or server environments
- Improve configuration handling
- Make important runtime settings easier to inspect
- Improve validation and diagnostics for unavailable backup storage
- Extend documentation for installation and operation
- Improve release and upgrade documentation

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

- Verify operation on Linux
- Remove remaining macOS-specific assumptions
- Extend provider abstraction where required
- Add Linux installation and configuration documentation

## Later

- Additional VM providers
- Improved backup history and statistics
- More detailed job history
- Further automation around backup maintenance
- Investigate platform-specific sparse file preservation during VM backup to minimize file sizes
