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

-  For a complete list of changes see published GitHub release notes:

[Release notes for v1.3.0](https://github.com/inthey2k/utm-backup-manager/releases#release-v1.3.0)


## 1.4.0 – Cleanup and operational visibility

- Show retention cleanup status in the web UI
    - running
    - completed
    - failed
- Improve error reporting for background cleanup jobs
- Review and clean up remaining IntelliJ inspection warnings
- Further UI polish and usability improvements
- Extend automated tests where useful

## 1.5.0 – Configuration and administration

- Improve configuration handling
- Make important runtime settings easier to inspect
- Improve validation and diagnostics for unavailable backup storage
- Extend documentation for installation and operation
- Improve release and upgrade documentation

## 1.6.0 – Linux support

- Verify operation on Linux
- Remove remaining macOS-specific assumptions
- Extend provider abstraction where required
- Add Linux installation and configuration documentation

## Later

- Additional VM providers
- Improved backup history and statistics
- More detailed job history
- Further automation around backup maintenance