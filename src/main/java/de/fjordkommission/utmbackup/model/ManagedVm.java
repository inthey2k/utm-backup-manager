package de.fjordkommission.utmbackup.model;

import java.util.Objects;

/**
 * A local VM after provider aggregation.
 *
 * The provider id is not stored separately: it is part of {@link VmId}. The
 * {@link LocalVm} keeps the provider-local id and the backup sources.
 *
 * @param id           canonical identity, the only identity generic code should use
 * @param providerName display name of the provider that reported this VM
 * @param localVm      the VM as reported by its provider
 * @param status       runtime state, UNKNOWN unless the provider reported it unambiguously
 */
public record ManagedVm(
        VmId id,
        String providerName,
        LocalVm localVm,
        VmStatus status
) {

    public ManagedVm {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(providerName, "providerName");
        Objects.requireNonNull(localVm, "localVm");
        Objects.requireNonNull(status, "status");
    }

    public String providerId() {
        return id.providerId();
    }

    /** Display name only. Never used for identity. */
    public String name() {
        return localVm.name();
    }
}
