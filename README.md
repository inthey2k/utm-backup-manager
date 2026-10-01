# UTM Backup Manager

UTM Backup Manager is a local web application for creating, managing,
and keeping track of backups of UTM virtual machines.

The application is designed for transparent file-based VM backups
without a proprietary backup format. VM packages are copied to a
configurable backup directory, while SQLite is used only for
supplemental application metadata.

> **Version 1.0.0:** UTM on macOS is currently the only implemented VM
> provider. The application contains a provider abstraction so
> additional virtualization platforms can be added in future versions.

## Features

-   Detects local UTM virtual machines and shows whether they are
    running or stopped
-   Creates backups of one or multiple VMs in a single batch
-   Only allows backups of stopped VMs
-   Displays backup progress and allows a running backup job to be
    cancelled
-   Detects whether a stopped VM has changed since its latest backup
-   Displays the logical size of local VMs
-   Shows existing backups in a VM-by-backup overview
-   Supports batch comments and notes for individual VM backups
-   Allows multiple backups of a VM to be marked as **Stable**
-   Protects Stable backups from deletion and excludes them from
    retention
-   Recognizes historical `*_stable` backup directories
-   Provides a configurable retention limit per VM
-   Marks retention candidates but **never deletes them automatically**
-   Allows individual VM backups to be deleted after confirmation
-   Stores supplemental metadata in SQLite
-   Provides German, English, and Spanish user interfaces
-   Shows the effective application configuration in the web interface
-   Listens on `127.0.0.1` by default and is therefore accessible only
    from the local computer

## Requirements

For version 1.0.0:

-   macOS
-   UTM installed, including the `utmctl` command-line tool
-   Java 21
-   Maven when building or running from source
-   A backup destination with enough free space

The configured backup directory must already exist. UTM Backup Manager
deliberately does **not** create the backup root automatically. This
prevents an unavailable external drive or mount from being silently
replaced by a local directory with the same path.

## Configuration

The repository contains a sample configuration file
`application.properties.example`.

Create your local configuration from the example:

```bash
cp application.properties.example application.properties
```

Then edit `application.properties` and adapt the values to your environment.

Example:

```properties
spring.application.name=utm-backup-manager
server.address=127.0.0.1
server.port=8085

vm.provider=utm
vm.utm.directory=/path/to/your/local/vms
vm.utm.package-suffix=.utm
vm.utm.cli=/path/to/utmctl

backup.root=/target/path/to/store/your/backups
backup.metadata-db=${user.home}/.utm-backup-manager/backup-manager.db
backup.legacy-state-file=${backup.root}/backup-state.tsv

spring.thymeleaf.cache=false
spring.messages.fallback-to-system-locale=false
```

Typical UTM installations on macOS store VM packages below:

```text
${user.home}/Library/Containers/com.utmapp.UTM/Data/Documents
```

and provide `utmctl` at:

```text
/Applications/UTM.app/Contents/MacOS/utmctl
```
This is the CLI executable of the Virtualization tool you use, with which the application 
communicates to fetch the list of VMs and request their status.

The paths may of course differ depending on the installation.
#### Notes
The real `application.properties` intentionally remains outside
`src/main/resources` and is excluded from Git. This prevents
machine-specific paths and settings from being packaged into the executable
JAR. The committed `application.properties.example` contains only example
values.

When the application is started from the project directory, Spring Boot
loads the external `application.properties`.

The SQLite database contains supplemental metadata and settings. The backup
directories themselves remain the primary source for the actual VM backup
data.

## Running from source

After configuring `application.properties`:

``` bash
mvn spring-boot:run
```

Then open `http://127.0.0.1:8085/` in a browser.

Run the test suite with:

``` bash
mvn clean test
```

## Building the executable JAR

Build the application with:

``` bash
mvn clean package
```

For version 1.0.0 the executable JAR is expected at:

``` text
target/utm-backup-manager-1.0.0.jar
```
You can run the JAR file using:
```bash
java -jar target/utm-backup-manager-1.0.0.jar
```
from the project folder.

Before publishing the first release, the packaged JAR and its
external-configuration workflow should be verified from a clean
checkout.

## How backups are stored

A backup batch is stored in a timestamped directory such as:

``` text
2026-10-01_1430/
├── BACKUP-INFO.txt
└── VMs/
    ├── Linux.utm/
    └── Server.utm/
```

A batch may contain one or several VMs. `BACKUP-INFO.txt` records
information about the batch, including the backup time, contained VMs,
and an optional comment.

UTM Backup Manager does not convert VM packages into a proprietary
archive format. The copied VM data remains directly accessible in the
filesystem.

During creation, a backup is first written to a temporary directory
whose name starts with `.incomplete-`. After a successful copy it is
moved to its final timestamped directory. When a backup is cancelled
normally through the web interface, its incomplete directory is cleaned
up.

## Stable backups

Any individual VM backup can be marked as **Stable**. Stable backups may
have their own Stable comment, are excluded from normal retention
candidates, and cannot be deleted while marked Stable. More than one
Stable backup per VM is allowed.

Historical backup directories whose names end in `_stable` are
recognized for compatibility with older backup workflows.

## Retention

The retention limit defines how many normal, non-Stable backups should
be retained for each VM. Retention is deliberately conservative: Stable
backups are excluded from the limit, backups exceeding the limit are
only marked as retention candidates, and **no backup is automatically
deleted**.

Deletion always remains an explicit user action.

## Backup deletion

The application can delete an individual VM backup from a batch
containing several VMs without deleting the other VM backups in that
batch. Stable backups are protected from deletion. When the last VM
backup is removed from a batch, the now-empty batch directory and its
batch metadata are removed as well.

## VM state and change detection

The UTM provider uses `utmctl` to determine whether local VMs are
running or stopped. Version 1.0.0 only creates backups of VMs reported
as stopped; it does not automatically shut down a running VM.

For stopped VMs, the application can indicate whether the VM has changed
since its most recent acknowledged backup. This is a practical
indication for deciding whether another backup may be useful; it is not
a cryptographic comparison of complete VM contents.

## Safety and data integrity

The application follows deliberately conservative rules:

-   a VM must be stopped before a backup can be started
-   the backup root must already exist
-   Stable backups cannot be deleted
-   retention never performs automatic deletion
-   source VM files are copied, not modified or removed
-   incomplete backups are kept separate from completed backup
    directories while copying
-   supplemental SQLite metadata is kept separate from the actual VM
    backup data

As with any backup software, important backups should be tested
independently before relying on them for disaster recovery.

## Known limitations in 1.0.0

-   **UTM on macOS is the only implemented VM provider.** The provider
    architecture is extensible, but other virtualization platforms are
    not yet supported.
-   **VM shutdown is not automated.** Running VMs must be stopped before
    starting their backup.
-   **Only one backup job can run at a time.**
-   **A hard application or system termination can leave a
    `.incomplete-*` directory behind.** Normal cancellation through the
    web interface cleans it up.
-   **Sparse disk images may consume substantially more physical space
    in the backup destination.** Whether sparse regions are preserved
    depends on the destination filesystem and storage/mount
    implementation. Plan backup capacity according to the logical VM
    size when in doubt.
-   **Change detection is timestamp-based.** It is an operational hint,
    not a byte-for-byte or cryptographic comparison.
-   **The web interface is intended for local use.** Authentication and
    remote multi-user operation are not part of version 1.0.0.

## Technology

UTM Backup Manager 1.0.0 uses Java 21, Spring Boot 4.1.1, Spring MVC,
Thymeleaf, SQLite and embedded Tomcat. The application uses a provider
abstraction for virtualization-specific integration; version 1.0.0
includes the UTM provider.

## Project status

Version 1.0.0 is the first public release candidate of the project.

## License

A license has not yet been selected for the project.
