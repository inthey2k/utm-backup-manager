# Backup Batch Format

This document describes the on-disk manifest of a backup batch, `BACKUP-INFO.txt`,
in format 1 (1.4.x) and format 2 (provider-aware). It is the contract that the
storage layer reads and writes.

Status: format 1 is read and scanned in production. Format 2 is implemented for
parsing and writing, and is tested in isolation. Production backups are still
written in the format 1 layout. The overview does not yet show format 2 batches.

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

## Not yet defined here

- Root metadata (`UTM-BACKUP-MANAGER-INFO.properties`) and the read-only storage
  guard.
- The scanning of format 2 VM containers into backups.
- The write path that produces format 2 batches in production.
- Stable and note state for format 2 batches.
