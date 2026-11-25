package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.VmOverview;

import java.util.Comparator;
import java.util.List;

/**
 * Orders the VM overview: providers in their configured order, then the VMs of each provider
 * alphabetically by their current display name, ignoring case. Canonical and provider-local
 * ids and the order of the provider's own listing never decide the position. A provider that
 * is not in the configured list comes last, ordered by its id.
 */
final class VmOverviewOrder {

    private VmOverviewOrder() {
    }

    static Comparator<VmOverview> forProviders(List<String> providerOrder) {
        return Comparator
                .comparingInt((VmOverview vm) -> rank(providerOrder, vm.providerId()))
                .thenComparing(VmOverview::providerId)
                .thenComparing(VmOverview::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                // Equal names up to case are ordered by their exact spelling, so the order is total.
                .thenComparing(VmOverview::name, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(vm -> vm.id().localId(), String.CASE_INSENSITIVE_ORDER);
    }

    private static int rank(List<String> providerOrder, String providerId) {
        int index = providerOrder.indexOf(providerId);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
