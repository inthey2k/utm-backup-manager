package de.fjordkommission.utmbackup.model;

import java.util.List;
import java.util.Map;

/**
 * The result of querying one provider during an inventory build.
 *
 * A failure is recorded in {@link #errors()} and never thrown to the caller, so
 * one provider cannot hide the VMs or backups of another.
 *
 * @param providerId   provider id
 * @param providerName provider display name
 * @param listed       true when {@code findAll()} succeeded; false means no local VMs are known
 * @param localVms     discovered VMs with unique ids; empty when not listed
 * @param statuses     states keyed by local id; empty when the state query failed
 * @param errors       human-readable problems, empty when the provider worked
 */
public record ProviderSnapshot(
        String providerId,
        String providerName,
        boolean listed,
        List<LocalVm> localVms,
        Map<String, VmStatus> statuses,
        List<String> errors
) {

    public ProviderSnapshot {
        localVms = List.copyOf(localVms);
        statuses = Map.copyOf(statuses);
        errors = List.copyOf(errors);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
