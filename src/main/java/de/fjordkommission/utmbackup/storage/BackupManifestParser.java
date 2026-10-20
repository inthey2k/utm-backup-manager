package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses the text of a {@code BACKUP-INFO.txt} manifest.
 *
 * <p>Format 1 (no {@code Format-Version} line) is parsed exactly as in 1.4.x: the
 * comment is everything after the first {@code Comment:} line, trimmed. The
 * {@code VMs:} section of legacy files is not interpreted.
 *
 * <p>Format 2 is a human-readable, UTF-8 text. It has top-level {@code Key: value}
 * lines, then blocks that start with a line that is exactly {@code VM:} and are
 * separated by blank lines, and finally a {@code Comment:} section that holds the
 * rest of the file verbatim. Unknown fields are ignored. Malformed or ambiguous
 * content yields {@link BackupManifestResult.Invalid}; it is never parsed as
 * format 1.
 */
public final class BackupManifestParser {

    static final String FORMAT_VERSION = "Format-Version";
    static final String TIME = "Time";
    static final String VM_MARKER = "VM:";
    static final String COMMENT_MARKER = "Comment:";
    static final String PROVIDER = "Provider";
    static final String VM_ID = "VM-ID";
    static final String NAME = "Name";
    static final String DIRECTORY = "Directory";

    private static final Set<String> VM_FIELDS = Set.of(PROVIDER, VM_ID, NAME, DIRECTORY);
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,9}");

    /**
     * Parses manifest text. The text may start with a UTF-8 byte-order mark and may
     * use LF or CRLF line endings.
     */
    public BackupManifestResult parse(String text) {
        List<String> lines = normalize(text).lines().toList();

        int commentIndex = commentMarkerIndex(lines);
        List<String> header = commentIndex < 0 ? lines : lines.subList(0, commentIndex);
        String comment = commentIndex < 0
                ? ""
                : String.join("\n", lines.subList(commentIndex + 1, lines.size())).trim();

        // Only top-level lines declare the format; a block may carry unknown fields.
        List<String> versions = fieldValues(topLevelLines(header), FORMAT_VERSION);

        if (versions.isEmpty()) {
            return new BackupManifestResult.Parsed(BackupManifest.legacy(comment));
        }

        if (versions.size() > 1) {
            return new BackupManifestResult.Invalid("Format-Version appears more than once");
        }

        String versionText = versions.getFirst();

        if (!DIGITS.matcher(versionText).matches()) {
            return new BackupManifestResult.Invalid("Format-Version is not a number: " + versionText);
        }

        int version = Integer.parseInt(versionText);

        if (version < BackupFormat.LEGACY) {
            return new BackupManifestResult.Invalid("Format-Version must be at least 1: " + version);
        }

        if (version > BackupFormat.CURRENT) {
            return new BackupManifestResult.UnsupportedVersion(version);
        }

        if (version == BackupFormat.LEGACY) {
            return new BackupManifestResult.Parsed(BackupManifest.legacy(comment));
        }

        return parseProviderAware(header, comment);
    }

    private BackupManifestResult parseProviderAware(List<String> header, String comment) {
        List<String> times = new ArrayList<>();
        List<Map<String, String>> blocks = new ArrayList<>();
        Map<String, String> currentBlock = null;

        for (String rawLine : header) {
            String line = rawLine.trim();

            if (line.isEmpty()) {
                // A blank line ends the current VM block.
                currentBlock = null;
                continue;
            }

            if (line.equals(VM_MARKER)) {
                currentBlock = new LinkedHashMap<>();
                blocks.add(currentBlock);
                continue;
            }

            Optional<Field> field = Field.of(line);

            if (field.isEmpty()) {
                continue;
            }

            if (currentBlock != null) {
                if (!VM_FIELDS.contains(field.get().key())) {
                    // Unknown fields inside a VM block are tolerated for forward compatibility.
                    continue;
                }

                if (currentBlock.putIfAbsent(field.get().key(), field.get().value()) != null) {
                    return new BackupManifestResult.Invalid(
                            "VM block repeats " + field.get().key() + ": " + field.get().value()
                    );
                }
            } else {
                if (field.get().key().equals(TIME)) {
                    times.add(field.get().value());
                } else if (VM_FIELDS.contains(field.get().key())) {
                    return new BackupManifestResult.Invalid(
                            field.get().key() + " appears outside a VM block"
                    );
                }
                // Other top-level fields, including free text and "VMs:", are ignored.
            }
        }

        if (times.size() != 1) {
            return new BackupManifestResult.Invalid(
                    times.isEmpty() ? "Time is missing" : "Time appears more than once"
            );
        }

        OffsetDateTime time;

        try {
            time = OffsetDateTime.parse(times.getFirst(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            return new BackupManifestResult.Invalid(
                    "Time must be an ISO date-time with offset: " + times.getFirst()
            );
        }

        List<BackupManifest.VmEntry> vms = new ArrayList<>();

        for (int index = 0; index < blocks.size(); index++) {
            try {
                vms.add(toVmEntry(blocks.get(index), index + 1));
            } catch (IllegalArgumentException e) {
                return new BackupManifestResult.Invalid(e.getMessage());
            }
        }

        try {
            return new BackupManifestResult.Parsed(new BackupManifest(
                    BackupFormat.PROVIDER_AWARE,
                    Optional.of(time),
                    comment,
                    vms
            ));
        } catch (IllegalArgumentException e) {
            return new BackupManifestResult.Invalid(e.getMessage());
        }
    }

    /**
     * Builds one VM entry. Every field is required except {@code Name}, which
     * defaults to the local id, so nothing is guessed about identity.
     */
    private static BackupManifest.VmEntry toVmEntry(Map<String, String> block, int position) {
        String provider = requiredField(block, PROVIDER, position);
        String localId = requiredField(block, VM_ID, position);
        String directory = requiredField(block, DIRECTORY, position);
        String name = block.getOrDefault(NAME, "");

        if (name.isBlank()) {
            name = localId;
        }

        return new BackupManifest.VmEntry(new VmId(provider, localId), name, directory);
    }

    private static String requiredField(Map<String, String> block, String key, int position) {
        String value = block.get(key);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("VM block " + position + " has no " + key);
        }

        return value;
    }

    /**
     * Returns the lines outside VM blocks. A VM block starts at a {@code VM:} line
     * and ends at the next blank line.
     */
    private static List<String> topLevelLines(List<String> header) {
        List<String> result = new ArrayList<>();
        boolean inBlock = false;

        for (String rawLine : header) {
            String line = rawLine.trim();

            if (line.isEmpty()) {
                inBlock = false;
            } else if (line.equals(VM_MARKER)) {
                inBlock = true;
            } else if (!inBlock) {
                result.add(line);
            }
        }

        return result;
    }

    private static List<String> fieldValues(List<String> lines, String key) {
        return lines.stream()
                .map(Field::of)
                .flatMap(Optional::stream)
                .filter(field -> field.key().equals(key))
                .map(Field::value)
                .toList();
    }

    private static int commentMarkerIndex(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).trim().equals(COMMENT_MARKER)) {
                return index;
            }
        }

        return -1;
    }

    /**
     * Removes a leading byte-order mark and converts CRLF and CR to LF.
     */
    static String normalize(String text) {
        String withoutBom = text.startsWith("﻿") ? text.substring(1) : text;

        return withoutBom.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * A {@code Key: value} line. The key ends at the first colon, so values may
     * contain colons, for example timestamps and local ids.
     */
    private record Field(String key, String value) {

        static Optional<Field> of(String line) {
            int colon = line.indexOf(':');

            if (colon < 0) {
                return Optional.empty();
            }

            return Optional.of(new Field(
                    line.substring(0, colon).trim(),
                    line.substring(colon + 1).trim()
            ));
        }
    }
}
