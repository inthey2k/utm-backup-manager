package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Backup-id schemes. Ids are the keys of the Stable and note metadata in SQLite, so
 * an existing scheme must never change.
 *
 * <ul>
 *   <li>Format 1 (unchanged since 1.4.x): {@code base64url(batch + "\n" + folder)}.</li>
 *   <li>Format 2: {@code base64url("format-2\n" + batch + "\n" + canonicalVmId)}.
 *       The canonical id separates providers, so the same local id under two
 *       providers gets two ids. The tag makes the scheme disjoint from format 1,
 *       because a format-1 batch name always starts with a year.</li>
 * </ul>
 * Neither scheme uses a display name.
 */
public final class BackupIdentity {

    static final String FORMAT_TWO_TAG = "format-2";

    private BackupIdentity() {
    }

    /** The format-1 id, byte-for-byte the formula used in 1.4.x. */
    public static String legacyId(String batchDirectoryName, String vmFolder) {
        return encode(batchDirectoryName + "\n" + vmFolder);
    }

    /** The format-2 id of one VM entry of a batch. */
    public static String formatTwoId(String batchDirectoryName, VmId vmId) {
        return encode(FORMAT_TWO_TAG + "\n" + batchDirectoryName + "\n" + vmId);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
