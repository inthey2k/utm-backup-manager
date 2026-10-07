# UTM Backup Manager 1.3.0
New feature: Automatic deletion of older backups beyond the Retention Limit configured in the application. When a backup starts, the application checks, if this means deleting older backups and warns the user. If they proceed, then old backup files will be deleted after a successful backup run. Also, improved cleaning up of .incomplete-* folder from previous failed backup runs.

## Highlights

- Automatic retention cleanup after successful backup runs
- Confirmation dialog before retention cleanup is triggered
- Stable backups remain protected from automatic deletion
- Cleanup runs independently from the backup job
- Old `.incomplete-*` backup directories are removed automatically
- Improved test coverage for retention and cleanup behavior

## Quality

- 59 automated tests
- SonarQube Quality Gate passed
- 0 SonarQube issues