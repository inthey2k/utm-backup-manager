package de.fjordkommission.utmbackup.model;

import java.util.Locale;

/**
 * Describes whether a local VM changed since its last acknowledged state.
 */
public enum VmChangeStatus {
    CHANGED,
    UNCHANGED,
    NEVER_BACKED_UP,
    UNKNOWN;

    public String cssClass() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
