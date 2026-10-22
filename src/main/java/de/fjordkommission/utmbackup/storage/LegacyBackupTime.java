package de.fjordkommission.utmbackup.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time of a format-1 batch, as shown and ordered by retention.
 *
 * The file-system time of {@code BACKUP-INFO.txt} is used. The batch directory name
 * is the fallback. This is kept exactly as in 1.4.x. Format 2 carries an explicit
 * time instead.
 */
public final class LegacyBackupTime {

    private static final Pattern BACKUP_DIRECTORY =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(?:_(\\d{4}))?.*$");

    private LegacyBackupTime() {
    }

    public static LocalDateTime resolve(Path batchDirectory) {
        Path info = batchDirectory.resolve(BackupManifestReader.FILE_NAME);

        if (Files.isRegularFile(info)) {
            try {
                return LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(info).toInstant(),
                        ZoneId.systemDefault()
                );
            } catch (IOException ignored) {
                // Fall back to the timestamp encoded in the directory name.
            }
        }

        Matcher matcher = BACKUP_DIRECTORY.matcher(batchDirectory.getFileName().toString());

        if (matcher.matches()) {
            LocalDate date = LocalDate.parse(matcher.group(1));

            if (matcher.group(2) != null) {
                LocalTime time = LocalTime.parse(
                        matcher.group(2),
                        DateTimeFormatter.ofPattern("HHmm")
                );

                return LocalDateTime.of(date, time);
            }

            return date.atStartOfDay();
        }

        return LocalDateTime.MIN;
    }
}
