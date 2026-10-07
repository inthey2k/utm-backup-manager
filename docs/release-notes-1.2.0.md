## UTM Backup Manager 1.2.0

This release focuses on code quality, maintainability, test coverage and internal structure.

### Improvements

- Refactored controller responsibilities into dedicated controllers
- Added `BackupJobController`, `BackupStartController` and `VmController`
- Improved asynchronous backup job handling and test synchronization
- Improved exception handling in repository initialization
- Added and extended tests for backup start and controller behavior
- Added JaCoCo coverage reporting
- Added SonarQube analysis support
- Improved Maven build configuration

### Code quality

SonarQube analysis now reports:

- 0 open Security issues
- 0 open Reliability issues
- 0 open Maintainability issues
- 0.0% duplicated code
- Quality Gate passed

Two findings are intentionally accepted because the use of `volatile` for immutable backup-job snapshots is deliberate.

### Compatibility

- Java 21
- Spring Boot 4.1.1
- macOS with UTM