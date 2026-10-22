# Backup Batch Format

This document describes the on-disk manifest of a backup batch, `BACKUP-INFO.txt`,
in format 1 (1.4.x) and format 2 (provider-aware). It is the contract that the
storage layer reads and writes.

Status: format 1 is read and scanned, and remains readable unchanged. Production
backup creation writes **format 2** with the provider-aware container layout
described below. Existing format-1 batches are never rewritten or migrated.

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
- Stable and note changes for format 2 also write `BACKUP-STATE.properties`, which is
  described in *Stable and note state*.
- A refused operation fails with the domain reason, for example
  `Backup storage is read-only: the medium uses storage format 3, which is newer than the supported format 2`.
  Existing error paths show this message without further changes.
- Legacy Stable migration during a scan is skipped on a read-only medium. The Stable flag
  still applies for that scan.
- The marker is created or updated only by the guard's `ensureFormatTwoMarker`, and
  only after a format-2 batch is complete (see *Write ordering*).
- The writer writes to a temporary file in the same directory and moves it over the
  target. It never replaces a marker that declares a newer format or that cannot be
  read, and it never repairs one.

## Limits

An application version older than this specification does not know the marker. It
cannot be prevented from writing to a medium that declares a newer format. Protection
applies from the version that ships this guard onwards.

## Production format-2 layout

Every new batch is written in this layout:

```text
<batch>/
├── BACKUP-INFO.txt                 format-2 manifest
└── VMs/
    ├── utm__ArchLinux-02-27-utm-6a667c24/
    │   └── ArchLinux 02.27.utm/    the UTM package, copied unchanged
    └── utm__test-linux-vm/
        └── TestLinux.utm/
```

- Each direct child of `VMs/` is exactly one logical VM, its **container**. Its
  sources are copied inside it under their own file names. The container may also
  hold `BACKUP-STATE.properties`, the mutable Stable and note state of that VM
  backup (see *Stable and note state*). It is optional and is never a source.
- The container name is only a filesystem name. It is never parsed. Identity comes
  from the manifest entry whose `Directory` names the container.
- Container name rule: `<providerId>__<local id>`, where every character outside
  `[A-Za-z0-9_-]` becomes `-` and the local-id part is truncated to 64 characters.
  Dots are replaced too, so a name can never be `.`, `..` or contain a separator.
  If the name was changed, or differs from another container only in case (macOS),
  a hash of the canonical id is appended: `-` plus eight hex digits of its SHA-256.
  Names are assigned in canonical-id order, so the result is deterministic.
- The container name of a plain local id is unchanged, for example
  `utm__test-linux-vm`.

## Format-2 backup IDs

A backup id is the key of its row in the SQLite metadata table, so it is fixed once
used. For format 2 that row is only a cache of the state on the medium (see
*Stable and note state*).

| Format | Scheme | Example input |
|---|---|---|
| 1 (unchanged) | `base64url(batch + "\n" + folder)` | `2026-09-01_1200` + `Linux.utm` |
| 2 | `base64url("format-2\n" + batch + "\n" + canonicalVmId)` | `2026-10-20_1200` + `utm:ArchLinux 02.27.utm` |

- The format-2 scheme uses the canonical id, so the same local id under two
  providers gets two ids. It never uses a display name.
- The `format-2` tag keeps the schemes disjoint: a format-1 batch name always starts
  with a year.
- Format-1 ids are byte-for-byte identical to 1.4.x (golden tests).

## Manifest and write ordering

Production creation of one batch (`CreateBackupService.create`) runs in this order.
Each step only starts after the previous one succeeded.

1. **Compatibility check.** The guard refuses a newer, invalid or inconsistent
   medium before anything is created.
2. **Input validation.** VM ids unique, sources present, and file names unique within
   each VM. The manifest is built and checked by the manifest writer, which re-parses
   its text. Unsafe names or directories are refused here, before any file exists.
3. **Incomplete batch.** `.incomplete-<batch>/VMs/` is created. Names starting with
   `.` are never scanned.
4. **VM data.** Each VM's sources are copied into its container.
5. **Manifest.** `BACKUP-INFO.txt` is written last among the content, atomically
   (temporary file, then move). It refers only to data that is already complete.
6. **Root marker.** Created, or updated to format 2, through the guard. This happens
   only once the batch is complete, and before it is published.
7. **Publish.** The incomplete directory is moved to its final name.
8. **Change-tracking acknowledgement**, keyed by the canonical VmId (see *Change-tracking state*).

Failure model:

- A failure in steps 1–6 removes the incomplete directory, and the marker is never
  written, so no half-written batch is shown and no marker is created for it.
- A copy failure leaves no manifest entry for data that was not written: the manifest
  is written only in step 5.
- If the publish move (step 7) fails after the marker was written, the medium declares
  format 2 without a format-2 batch. This is consistent: the medium is supported and
  nothing is shown for the failed backup.
- Only `.incomplete-` directories are cleaned up, and only by the creator.

The marker is updated, not rewritten, when it already declares format 2 and was last
written by the same application version. Creation fields are kept when the marker is
rewritten.

## Delete and retention for format 2

- Deleting one format-2 VM:
  1. the manifest is rewritten without that entry (atomic),
  2. only that VM's container is removed,
  3. if no entry remains, the batch directory is removed too.
  A crash between steps 1 and 2 leaves orphaned data that is never shown. It never
  leaves a manifest entry that points at deleted data.
- The guard runs before any of this. Before the manifest is rewritten, the
  container's `BACKUP-STATE.properties` is read again. A backup that is Stable there
  cannot be deleted, and an unreadable state file blocks the deletion. SQLite is not
  consulted, so a stale Stable flag there neither protects nor unprotects a backup.
- Retention works on canonical ids and on the same per-VM backup list. The Stable
  state shown in that list comes from the medium, so a Stable format-2 backup is never
  a retention candidate. An unreadable state file is shown as Stable. It is excluded
  from retention and blocked from deletion, so it is never removed by mistake.
  Retention deletes the oldest non-Stable backups of the selected VMs through the same
  delete path, so other VMs of the same batch and other providers are untouched.
- Format-1 deletion and retention are unchanged.

## Stable and note state

Stable and the note are mutable. They are not part of the immutable batch identity
or the manifest. Each format-2 backup, that is each VM of a batch, has its own state
file. This matches the per-backup scope of Stable and notes.

`BACKUP-STATE.properties` lives in the VM's container, next to its copied sources:

```properties
# UTM Backup Manager: mutable state of this backup, replaced atomically.
stable=true
note=Before kernel update
```

Syntax:

- UTF-8, with or without BOM, LF or CRLF line endings. Bytes that are not valid
  UTF-8 make the file invalid.
- `key=value`. Blank lines and lines starting with `#` are ignored. A line without `=`
  is malformed.
- `stable` (required) must be exactly `true` or `false`. A missing, duplicated or
  other value (for example `yes`) is malformed.
- `note` (optional) is one line. The escapes `\\`, `\n` and `\r` represent a backslash
  and line breaks. Any other backslash sequence is malformed. The note is stripped at
  both ends and is empty when absent.
- A known key that appears twice is malformed. Unknown keys are ignored, so later
  versions can add fields.
- The writer adds no timestamp, so the same state always gives the same bytes. It
  checks by re-parsing that the output reads back unchanged, and it writes through a
  temporary file that is moved over the target.

### Note and BACKUP-INFO comment

These are two different things and keep their semantics:

- `Comment:` in `BACKUP-INFO.txt` is the immutable comment written with the batch.
  It applies to every VM of the batch and is never changed by the application.
- The **note** in `BACKUP-STATE.properties` is the mutable note of one VM backup,
  edited through the "Note" button. It is never written into the manifest.

### Authority rules

| Format | Authority for Stable and note | SQLite |
|---|---|---|
| 1 | SQLite (`vm_backup_metadata`), as in 1.4.x. The legacy `_stable` directory name still gives Stable once. | The authority. No state file is written. |
| 2 | `BACKUP-STATE.properties` of the container | A cache (see below). It is never read for format-2 Stable or notes. |

For format 2 the file wins. The two sources are never combined with OR or a merge.
If they disagree, the file decides. Concretely:

- A file that says `stable=true` protects the backup, even if SQLite says `false`.
- A file that says `stable=false` does not protect the backup, even if SQLite says `true`.
- The note comes from the file alone.

### Defaults when the file is absent

A format-2 backup without `BACKUP-STATE.properties` is not an error. It is
`stable=false` with an empty note, and SQLite is not consulted. The file is created by
the first Stable or note change.

### Unreadable state

A file that exists but is invalid is never replaced by the defaults or by SQLite:

- The scanner shows the backup as **Stable**, so it is excluded from retention and cannot
  be deleted. The batch issue names the VM and gives the reason.
- Changing Stable or the note is refused with the reason, and nothing is written.
- Deletion is refused with the reason.

The user repairs or removes the file on the medium to continue. Removing it gives the
defaults.

### Write ordering

Stable and note changes for format 2 run in this order:

1. The compatibility guard (`assertWritable`) refuses a read-only medium.
2. The backup is found by the scanner and must be format 2 by its manifest.
3. The current state file is read. An absent file gives the defaults; an invalid file
   stops the change.
4. The new state, with the other field unchanged, is written atomically to
   `BACKUP-STATE.properties`. This is the commit point.
5. SQLite is updated as a mirror of the new state, only after step 4 succeeded.

Failure semantics:

- A failure in step 4 fails the operation. The old file is kept, SQLite is unchanged,
  and no partial file is left behind.
- A failure in step 5 after a successful step 4 is reported as: the change was saved
  to the backup, but the local metadata cache could not be updated. The backup is
  authoritative, so the change is in effect. SQLite can be stale until the next
  successful change. Nothing reads it for format 2, so the scan and deletion stay
  correct.

### SQLite's role for format 2

SQLite keeps a row per format-2 backup id, updated only as a mirror of the state file.
It exists for compatibility with the existing table and for a future local index. It
is never a source for Stable, notes, deletion, or retention of format 2. A stale row
has no effect, and no schema change is needed.

Format 1 is unchanged: Stable and notes are read and written in SQLite, and no
`BACKUP-STATE.properties` is created for a format-1 batch.

## Mixed-format media

- Each batch is read according to its own manifest. A supported marker does not make
  every batch format 2, and a format-2 batch does not make legacy batches migrate.
- The scanner shows both kinds in one overview. Format-2 backups attach to a local VM
  through the manifest's canonical id, and a format-2 VM without a local VM appears as
  a backup-only entry with the manifest name.
- Legacy batches are never rewritten, moved, or renamed.

## Change-tracking state

The `vm_state` table stores the acknowledged state of each VM, keyed by the
canonical VmId serialized as `providerId:localId`, for example `utm:Linux Mint 23.utm`.
Display names are never keys.

Before Phase 7a, the key was the UTM local id, a folder name such as
`Linux Mint 23.utm`. A one-time migration runs at startup, before any runtime read or
the legacy state-file import:

- A key that is already canonical is unchanged. A key is canonical if its prefix is a
  valid provider id and something follows the colon.
- A key with a valid provider prefix and nothing after the colon, or a blank key, is
  malformed. It is kept unchanged and counted in the log.
- Any other key is a legacy UTM key. It is renamed to `utm:<key>` with its stored
  time and acknowledgement unchanged.
- If the canonical key already exists, the canonical value is kept and the legacy row
  is left in place, so nothing is overwritten or deleted. The conflict is logged.

The migration runs in one transaction with a marker in the `settings` table, and a
second run changes nothing. Backup files, backup ids and Stable or note state are not
touched.

## Not yet defined here

- Enabling backups of VMs from providers other than UTM. Format 2 can store them;
  the restriction is still in the backup-start controller until the provider
  implementation is verified.
