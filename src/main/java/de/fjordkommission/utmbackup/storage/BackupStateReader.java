package de.fjordkommission.utmbackup.storage;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Reads {@code BACKUP-STATE.properties}, the mutable Stable and note state of one
 * format-2 backup container.
 *
 * <p>Syntax: UTF-8, optional byte-order mark, LF or CRLF line endings. Lines are
 * {@code key=value}; blank lines and lines starting with {@code #} are ignored.
 * {@code stable} is required and must be exactly {@code true} or {@code false}.
 * {@code note} is optional and may use the escapes {@code \\}, {@code \n} and
 * {@code \r}, so that a note with line breaks stays on one line. Any other escape
 * is malformed. A key that appears twice is malformed. Unknown keys are ignored.
 */
public final class BackupStateReader {

    public static final String FILE_NAME = "BACKUP-STATE.properties";

    static final String STABLE_KEY = "stable";
    static final String NOTE_KEY = "note";

    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    /**
     * Reads the state file of a container. A file that is not a regular file, or that
     * cannot be decoded as UTF-8, is invalid.
     */
    public BackupStateResult read(Path container) {
        Path file = container.resolve(FILE_NAME);

        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new BackupStateResult.Absent();
        }

        if (!Files.isRegularFile(file)) {
            return new BackupStateResult.Invalid(FILE_NAME + " is not a regular file");
        }

        String text;

        try {
            text = Files.readString(file, UTF_8);
        } catch (MalformedInputException e) {
            return new BackupStateResult.Invalid(FILE_NAME + " is not valid UTF-8");
        } catch (IOException e) {
            return new BackupStateResult.Invalid(FILE_NAME + " could not be read");
        }

        return parse(text);
    }

    /** Parses the text of a state file. Public so that the writer can check its own output. */
    public BackupStateResult parse(String text) {
        String content = !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;

        Boolean stable = null;
        String note = null;

        try {
            for (String rawLine : content.split("\r\n|\r|\n", -1)) {
                String line = rawLine.strip();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                int separator = line.indexOf('=');

                if (separator < 0) {
                    throw new IllegalArgumentException("a line has no '='");
                }

                String key = line.substring(0, separator).strip();
                String value = line.substring(separator + 1).strip();

                switch (key) {
                    case STABLE_KEY -> {
                        requireUnset(stable, key);
                        stable = parseStable(value);
                    }
                    case NOTE_KEY -> {
                        requireUnset(note, key);
                        note = unescape(value);
                    }
                    default -> {
                        // Unknown properties are ignored, so newer versions can add fields.
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            return new BackupStateResult.Invalid(FILE_NAME + ": " + e.getMessage());
        }

        if (stable == null) {
            return new BackupStateResult.Invalid(FILE_NAME + ": " + STABLE_KEY + " is missing");
        }

        return new BackupStateResult.Parsed(new BackupState(stable, note));
    }

    /** Encodes a note so that it fits on one line. The inverse of {@link #unescape(String)}. */
    static String escape(String note) {
        return note.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static void requireUnset(Object value, String key) {
        if (value != null) {
            throw new IllegalArgumentException(key + " appears more than once");
        }
    }

    private static boolean parseStable(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(STABLE_KEY + " must be true or false");
        };
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean afterBackslash = false;

        for (char current : value.toCharArray()) {
            if (afterBackslash) {
                result.append(switch (current) {
                    case '\\' -> '\\';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> throw new IllegalArgumentException(
                            NOTE_KEY + " contains an unknown escape: \\" + current);
                });
                afterBackslash = false;
            } else if (current == '\\') {
                afterBackslash = true;
            } else {
                result.append(current);
            }
        }

        if (afterBackslash) {
            throw new IllegalArgumentException(NOTE_KEY + " ends with an incomplete escape");
        }

        return result.toString();
    }
}
