package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.VmId;

/**
 * Compatibility rules for format-1 backups, written by 1.4.x.
 *
 * In format 1 every VM folder directly below {@code VMs/} is a UTM package, and
 * its folder name is the provider-local id. Those backups therefore belong to
 * {@code utm:<folder name>}, even when UTM is not currently enabled.
 *
 * The on-disk layout does not change in this phase. These rules are the only
 * place that maps legacy folders to canonical identities, and they must be
 * replaced when the format-2 storage is introduced.
 */
public final class LegacyBackupLayout {

    /** The provider that owns every format-1 VM folder. */
    public static final String PROVIDER_ID = "utm";

    private LegacyBackupLayout() {
    }

    /**
     * Returns the canonical identity of a format-1 VM folder.
     */
    public static VmId vmIdForFolder(String folderName) {
        return new VmId(PROVIDER_ID, folderName);
    }

    /**
     * Returns whether change tracking for this provider still uses the legacy
     * {@code vm_state} keys, which are the provider-local ids.
     */
    public static boolean usesLegacyStateKeys(String providerId) {
        return PROVIDER_ID.equals(providerId);
    }
}
