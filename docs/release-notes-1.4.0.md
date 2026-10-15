## UTM Backup Manager 1.4.0

### Highlights

- Added retention cleanup integration to the backup workflow.
- Shows backups that are expected to be deleted by retention cleanup before the backup starts.
- Starts retention cleanup automatically after a successful backup.
- Keeps backup and retention-cleanup lifecycles separate so cleanup failures do not change a successful backup result.
- Added frontend status reporting for retention cleanup, including failed cleanup jobs and their error messages.

### Retention improvements

- Added preview calculation for backups pending deletion.
- Stable backups remain protected from retention cleanup.
- Pending-deletion markers are cleared when a backup completes, fails, or is cancelled.
- Retention cleanup is not started after failed or cancelled backups.

### Reliability and code quality

- Added and extended unit tests for the new retention workflow and retention cleanup status endpoint.
- Added JaCoCo coverage reporting to the Maven build.
- SonarQube Quality Gate passes with 90.0% coverage on new code.
- Improved handling of retention cleanup startup failures.

### Compatibility

- Java 21
- Spring Boot 4.1.x