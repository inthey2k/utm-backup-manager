package de.fjordkommission.utmbackup.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads {@code UTM-BACKUP-MANAGER-INFO.properties} from the backup root.
 *
 * <p>The file is UTF-8 with LF or CRLF line endings and an optional byte-order mark.
 * Unlike batch manifests, bytes that are not valid UTF-8 are rejected, because this
 * file is machine-read and a silently altered value could change the compatibility
 * decision. Unknown properties are ignored. Known properties must not repeat.
 */
public final class BackupStorageMetadataReader {

    public static final String FILE_NAME = "UTM-BACKUP-MANAGER-INFO.properties";

    static final String FORMAT_VERSION = "format.version";
    static final String APPLICATION_NAME = "application.name";
    static final String CREATED_AT = "created.at";
    static final String CREATED_BY_VERSION = "created.by.version";
    static final String LAST_WRITTEN_BY_VERSION = "last.written.by.version";

    private static final Set<String> KNOWN_KEYS = Set.of(
            FORMAT_VERSION, APPLICATION_NAME, CREATED_AT, CREATED_BY_VERSION, LAST_WRITTEN_BY_VERSION
    );
    private static final Pattern DIGITS = Pattern.compile("\\d{1,9}");

    public BackupStorageMetadataResult read(Path root) {
        Path file = root.resolve(FILE_NAME);

        if (Files.notExists(file)) {
            return new BackupStorageMetadataResult.Absent();
        }

        if (!Files.isRegularFile(file)) {
            return new BackupStorageMetadataResult.Invalid(FILE_NAME + " is not a regular file");
        }

        try {
            String text = decodeUtf8(Files.readAllBytes(file));
            return parse(text);
        } catch (CharacterCodingException e) {
            return new BackupStorageMetadataResult.Invalid(FILE_NAME + " is not valid UTF-8");
        } catch (IOException e) {
            return new BackupStorageMetadataResult.Invalid(FILE_NAME + " could not be read: " + e.getMessage());
        }
    }

    /**
     * Parses the file contents. Exposed for tests of the syntax rules.
     */
    BackupStorageMetadataResult parse(String text) {
        Map<String, String> values = new HashMap<>();

        for (String line : normalize(text).lines().map(String::trim).toList()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int separator = line.indexOf('=');

            if (separator < 0) {
                return new BackupStorageMetadataResult.Invalid("Malformed line without '=': " + line);
            }

            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();

            if (KNOWN_KEYS.contains(key) && values.putIfAbsent(key, value) != null) {
                return new BackupStorageMetadataResult.Invalid("Property " + key + " appears more than once");
            }
        }

        return toResult(values);
    }

    private static BackupStorageMetadataResult toResult(Map<String, String> values) {
        String versionText = values.get(FORMAT_VERSION);

        if (versionText == null) {
            return new BackupStorageMetadataResult.Invalid(FORMAT_VERSION + " is missing");
        }

        if (!DIGITS.matcher(versionText).matches()) {
            return new BackupStorageMetadataResult.Invalid(FORMAT_VERSION + " is not a number: " + versionText);
        }

        int version = Integer.parseInt(versionText);

        if (version < BackupFormat.LEGACY) {
            return new BackupStorageMetadataResult.Invalid(FORMAT_VERSION + " must be at least 1: " + version);
        }

        // Checked before the other properties, so a newer medium is always reported as newer.
        if (version > BackupFormat.CURRENT) {
            return new BackupStorageMetadataResult.UnsupportedVersion(version);
        }

        String createdAtText = values.get(CREATED_AT);

        if (createdAtText == null) {
            return new BackupStorageMetadataResult.Invalid(CREATED_AT + " is missing");
        }

        OffsetDateTime createdAt;

        try {
            createdAt = OffsetDateTime.parse(createdAtText, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            return new BackupStorageMetadataResult.Invalid(
                    CREATED_AT + " must be an ISO date-time with offset: " + createdAtText
            );
        }

        return new BackupStorageMetadataResult.Present(new BackupRootMetadata(
                version,
                values.getOrDefault(APPLICATION_NAME, ""),
                createdAt,
                values.getOrDefault(CREATED_BY_VERSION, ""),
                values.getOrDefault(LAST_WRITTEN_BY_VERSION, "")
        ));
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static String normalize(String text) {
        String withoutBom = text.startsWith("\uFEFF") ? text.substring(1) : text;

        return withoutBom.replace("\r\n", "\n").replace('\r', '\n');
    }
}
