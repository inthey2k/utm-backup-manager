package de.fjordkommission.utmbackup.model;

import java.util.Locale;

/**
 * Represents the runtime state of a VM as required by the backup manager.
 * Only a VM known to be stopped is considered safe for backup. Providers
 * translate their hypervisor-specific states into these generic states.
 */
public enum VmStatus {
    RUNNING,
    STOPPED,
    UNKNOWN;

    public String cssClass() {
        return name().toLowerCase(Locale.ROOT);
    }
}