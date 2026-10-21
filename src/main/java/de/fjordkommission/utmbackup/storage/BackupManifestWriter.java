package de.fjordkommission.utmbackup.storage;

import java.time.format.DateTimeFormatter;

/**
 * Writes format-2 {@code BACKUP-INFO.txt} text.
 * The output is parsed again before it is returned. If the parser would not read
 * back the same VM entries and time, the write is refused. This keeps unsafe
 * names, directories or times from ever reaching a backup batch.
 */
public final class BackupManifestWriter {

    private final BackupManifestParser parser = new BackupManifestParser();

    /**
     * Returns the UTF-8 text of a format-2 manifest, ready to be stored as
     * {@code BACKUP-INFO.txt}.
     *
     * @throws IllegalArgumentException if the manifest is not format 2 or would not
     *                                  be read back unchanged
     */
    public String write(BackupManifest manifest) {
        if (manifest.formatVersion() != BackupFormat.PROVIDER_AWARE) {
            throw new IllegalArgumentException(
                    "Only format " + BackupFormat.PROVIDER_AWARE + " manifests can be written"
            );
        }

        StringBuilder text = new StringBuilder();

        text.append("Backup created by UTM Backup Manager\n");
        text.append(BackupManifestParser.FORMAT_VERSION).append(": ")
                .append(BackupFormat.PROVIDER_AWARE).append('\n');
        text.append(BackupManifestParser.TIME).append(": ")
                .append(manifest.backupTime().orElseThrow().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .append('\n');

        for (BackupManifest.VmEntry vm : manifest.vms()) {
            text.append('\n');
            text.append(BackupManifestParser.VM_MARKER).append('\n');
            text.append(BackupManifestParser.PROVIDER).append(": ").append(vm.id().providerId()).append('\n');
            text.append(BackupManifestParser.VM_ID).append(": ").append(vm.id().localId()).append('\n');
            text.append(BackupManifestParser.NAME).append(": ").append(vm.name()).append('\n');
            text.append(BackupManifestParser.DIRECTORY).append(": ").append(vm.directory()).append('\n');
        }

        text.append('\n');
        text.append(BackupManifestParser.COMMENT_MARKER).append('\n');
        text.append(manifest.comment().replace("\r\n", "\n").replace('\r', '\n').strip()).append('\n');

        String result = text.toString();
        requireRoundTrip(manifest, result);

        return result;
    }

    private void requireRoundTrip(BackupManifest manifest, String text) {
        BackupManifestResult parsed = parser.parse(text);

        if (!(parsed instanceof BackupManifestResult.Parsed(BackupManifest readBack))
                || !readBack.vms().equals(manifest.vms())
                || !readBack.backupTime().equals(manifest.backupTime())) {
            throw new IllegalArgumentException(
                    "Manifest would not be read back unchanged and was not written: " + describe(parsed)
            );
        }
    }

    private static String describe(BackupManifestResult result) {
        return switch (result) {
            case BackupManifestResult.Parsed ignored -> "different content";
            case BackupManifestResult.UnsupportedVersion(int formatVersion) -> "unsupported version " + formatVersion;
            case BackupManifestResult.Invalid(String reason) -> reason;
        };
    }
}
