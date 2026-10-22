package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.VmId;

/**
 * Compatibility rules for format-1 backups, written by 1.4.x.
 * In format 1 every VM folder directly below {@code VMs/} is a UTM package, and
 * its folder name is the provider-local id. Those backups therefore belong to
 * {@code utm:<folder name>}, even when UTM is not currently enabled.
 * New backups are format 2 and carry their canonical identity in the manifest,
 * so this class is used only when reading format-1 batches.
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
}
