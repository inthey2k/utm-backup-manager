# Backup Batch Format

This document describes the on-disk manifest of a backup batch, `BACKUP-INFO.txt`,
in format 1 (1.4.x) and format 2 (provider-aware). It is the contract that the
storage layer reads and writes.

Status: format 1 is read and scanned in production. Format 2 is implemented for
parsing and writing, and is tested in isolation. Production backups are still
written in the format 1 layout. The overview does not yet show format 2 batches.
The backup-root marker and the write guard described below are in place. Normal
backup creation still writes format 1 and does not create the marker.

## Versions

The **batch format version** is the compatibility contract. It is stored in the
`Format-Version` line. The application version is informational and is never used
to decide how a batch is read.

| Format | Marker | Layout | Identity |
|---|---|---|---|
| 1 | no `Format-Version` line (or `Format-Version: 1`) | `VMs/<package folder>/` | the folder name, provider `utm` |
| 2 | `Format-Version: 2` | `VMs/<container directory>/` | `Provider` and `VM-ID` in the manifest |

A version greater than the highest supported one is reported as **unsupported**.
It is never parsed as format 2.

## Format 1

A format 1 file has no `Format-Version` line. It is read exactly as in 1.4.x:

```text
Backup created by VM Backup Manager
Time: 2026-09-01T12:00:00
VMs:
test-linux-vm

Comment:
Before kernel update
```

- The comment is everything after the first line that is exactly `Comment:`,
  trimmed at both ends. Lines such as `VM:` or `VMs:` inside the comment are text.
- The `VMs:` section is not interpreted. Its entries are not VM identities.
- The time is the file-system modification time of `BACKUP-INFO.txt`. If the file
  is absent, the directory name (`yyyy-MM-dd_HHmm`) is used. This is unchanged, so
  retention ordering does not change.
- A missing file gives an empty comment.

## Format 2

```text
Backup created by UTM Backup Manager
Format-Version: 2
Time: 2026-10-20T12:00:00+02:00

VM:
Provider: utm
VM-ID: ArchLinux 02.27.utm
Name: ArchLinux 02.27
Directory: utm__ArchLinux-02.27

VM:
Provider: qemu
VM-ID: ArchLinux
Name: Arch Linux
Directory: qemu__ArchLinux

Comment:
Before kernel update
```

Syntax:

- UTF-8, with or without a byte-order mark. LF and CRLF line endings are accepted.
- Lines have the form `Key: value`. The key ends at the first colon, so values may
  contain colons, for example `VM-ID: a:b` or the time.
- Top-level lines: `Format-Version` (required, exactly once), `Time` (required,
  exactly once, ISO-8601 with an offset, for example `2026-10-20T12:00:00+02:00`).
  Any other top-level line is ignored, including the legacy `VMs:` line.
- A VM block starts with a line that is exactly `VM:`. It contains `Provider`,
  `VM-ID`, `Name` and `Directory`, and ends at the next blank line. Blocks are
  separated by blank lines.
- Unknown fields, inside or outside a VM block, are ignored. Fields of a VM block
  appearing outside a block are an error.
- `Comment:` starts the comment. Everything after it is the comment, unparsed,
  trimmed at both ends. It may contain any text, including `VM:` and `VMs:`.

Writing is always followed by a re-parse. A manifest that would not read back with
the same entries and time is refused, so an unsafe value cannot be written.

## Format 2 validation

Each VM block must satisfy these rules. Any violation makes the whole manifest
invalid. Nothing is guessed.

| Field | Rule |
|---|---|
| `Provider` | required; must be a valid provider id (`[a-z0-9-]+`, no normalisation) |
| `VM-ID` | required; non-blank; may contain spaces, dots and colons; values are trimmed on read |
| `Name` | optional; defaults to the `VM-ID` value when absent or blank |
| `Directory` | required; exactly one path element; must not contain `/`, `\`, `..`, be `.`, be blank, or contain control characters |

Across one batch:

- two entries with the same canonical `VmId` (`provider` + `VM-ID`) are invalid;
- two entries with the same `Directory` are invalid. The comparison is case-insensitive
  and Unicode-normalised (NFC), because macOS and external drives may treat such names
  as the same.

These rules keep a manifest from pointing outside `VMs/` of its batch.

## Failure behaviour

The reader returns one of three outcomes. They are never mixed:

| Outcome | When | Effect in the scanner |
|---|---|---|
| parsed format 1 | no `Format-Version` line, or `Format-Version: 1` | shown as before |
| parsed format 2 | valid format 2 manifest | not shown yet; reported as a batch issue |
| unsupported | `Format-Version` greater than 2 | not shown; reported as a batch issue |
| invalid | malformed format 2, ambiguous, or the file cannot be read | not shown; reported as a batch issue |

A malformed format 2 manifest is never read as format 1, so its folders cannot
become UTM VMs or backups. A batch that is not shown contributes no backups and
no retention decisions, so it cannot be deleted through the overview.

The one exception to the format 1 tolerance: a `BACKUP-INFO.txt` that exists but
cannot be read is invalid, because its format cannot be known. Bytes that are not
valid UTF-8 are replaced rather than rejected, since 1.4.x tolerated them and the
format markers are ASCII.

## Backup-root marker

The backup root may contain `UTM-BACKUP-MANAGER-INFO.properties`. It describes the
storage format of the whole medium. It is optional for legacy media.

```properties
format.version=2
application.name=UTM Backup Manager
created.at=2026-10-21T12:00:00+02:00
created.by.version=1.5.0
last.written.by.version=1.5.0
```

Syntax:

- UTF-8, with or without BOM, LF or CRLF line endings. Bytes that are not valid
  UTF-8 are rejected, because the file is machine-read.
- `key=value`, the value is trimmed. Lines starting with `#` and blank lines are
  ignored. A line without `=` is malformed.
- `format.version` (required) must be a positive integer. A value higher than the
  supported version is reported as newer, whatever else the file contains.
- `created.at` (required) must be an ISO-8601 date-time with an offset.
- `application.name`, `created.by.version` and `last.written.by.version` are
  informational. They may be absent. They are never used for compatibility.
- Unknown properties are ignored. A known property that appears twice is invalid.

### Format version and application version

- `format.version` is the **storage compatibility contract**. It is the only field
  that decides whether a medium may be written.
- The application version fields only record who created or last wrote the marker,
  for diagnostics. A later application release may still write the same format.
- The marker describes the highest format written to the medium. It does not change
  how an individual batch is read. A batch is read from its own
  `BACKUP-INFO.txt`, as described above.

## Compatibility states

Every mutating operation consults one central guard, which evaluates the medium on
each call. The decision rules live in one place.

| State | When | Writable |
|---|---|---|
| `LEGACY` | no marker and no batch declares a format other than 1 | yes, as in 1.4.x |
| `SUPPORTED` | marker present with a supported `format.version` (1 or 2) | yes |
| `READ_ONLY_NEWER_FORMAT` | marker declares a format newer than 2 | no |
| `READ_ONLY_INVALID_METADATA` | marker present but cannot be interpreted safely | no |
| `READ_ONLY_INCONSISTENT` | no marker, but at least one batch uses format 2, declares a newer format, or has a manifest that cannot be read | no |
| `UNAVAILABLE` | the backup directory does not exist | no |

Decision order:

1. The backup directory missing: `UNAVAILABLE`.
2. Marker absent: `LEGACY` if every batch manifest is readable format 1, otherwise
   `READ_ONLY_INCONSISTENT`. A format-2 batch is never treated as legacy because the
   marker is missing.
3. Marker present and supported: `SUPPORTED`.
4. Marker present and newer: `READ_ONLY_NEWER_FORMAT`.
5. Marker present but invalid: `READ_ONLY_INVALID_METADATA`. It is never silently
   treated as absent.

A marker that is absent because nothing was ever written is therefore safe, while
a marker that is unreadable is never ignored.

## Legacy and mixed media

- **Legacy medium** (1.4.x backups only): no marker, all batches format 1. Behaviour
  is unchanged. Backups can be created, deleted, marked Stable, annotated and cleaned
  up by retention. No marker is created by any of these operations.
- **Mixed medium**: legacy batches and format-2 batches can coexist. Legacy batches are
  never migrated or rewritten. Each batch is read by its own manifest. A medium with a
  supported marker is writable even when it contains format-2 batches.
- **Marker missing but format-2 batch present**: read-only, reported with the batch
  name. The scanner still reads each batch format independently, so the batch is not
  shown as legacy.

## Read-only safety rules

- Read operations (overview, configuration, size, status) are never blocked by the
  compatibility state.
- Mutating operations are refused before their first change: backup creation,
  deletion, retention cleanup, Stable changes, note changes, and root-marker writes.
  No file is created, moved or removed, and no SQLite row is changed.
- A refused operation fails with the domain reason, for example
  `Backup storage is read-only: the medium uses storage format 3, which is newer than the supported format 2`.
  Existing error paths show this message without further changes.
- Legacy Stable migration during a scan is skipped on a read-only medium. The Stable flag
  still applies for that scan.
- The marker is written only by the root-metadata writer. Production code does not
  call it yet, so normal backup creation never creates the marker.
- The writer writes to a temporary file in the same directory and moves it over the
  target. It never replaces a marker that declares a newer format or that cannot be
  read, and it never repairs one.

## Limits

An application version older than this specification does not know the marker. It
cannot be prevented from writing to a medium that declares a newer format. Protection
applies from the version that ships this guard onwards.

## Not yet defined here

- The scanning of format 2 VM containers into backups.
- The write path that produces format 2 batches in production.
- Stable and note state for format 2 batches.
