## UTM Backup Manager 1.0.0

First public release of UTM Backup Manager.

### Highlights

- Detects local UTM virtual machines and their runtime status
- Creates backups of one or multiple stopped VMs
- Displays backup progress, elapsed time and estimated remaining time
- Supports cancellation of running backups
- Detects whether a VM has changed since its latest backup
- Supports backup comments and per-VM notes
- Allows backups to be marked as Stable
- Protects Stable backups from deletion and retention
- Provides configurable retention per VM
- Never deletes backups automatically
- Supports German, English and Spanish
- Uses SQLite only for supplemental metadata
- Runs locally on `127.0.0.1`

### Requirements

- macOS
- UTM with `utmctl`
- Java 21 or later
- Maven to run build, test, and run the Spring Boot application

See the README for installation, configuration and usage details.