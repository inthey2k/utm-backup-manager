package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.model.VmStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VmOverviewOrderTest {

    @Test
    void vmsOfAProviderAreSortedByDisplayNameIgnoringCase() {
        List<VmOverview> vms = List.of(
                overview("utm", "z-id.utm", "Zeta"),
                overview("utm", "b-id.utm", "beta"),
                overview("utm", "a-id.utm", "Alpha")
        );

        assertEquals(
                List.of("Alpha", "beta", "Zeta"),
                sorted(vms, List.of("utm"))
        );
    }

    @Test
    void sortingUsesTheDisplayNameNotTheLocalOrCanonicalId() {
        // The ids are in the opposite order of the names, so an id sort would fail this test.
        List<VmOverview> vms = List.of(
                overview("utm", "aaa.utm", "Yankee"),
                overview("utm", "zzz.utm", "Alpha")
        );

        assertEquals(List.of("Alpha", "Yankee"), sorted(vms, List.of("utm")));
    }

    @Test
    void configuredProviderOrderIsKeptAndProvidersAreNotMixed() {
        List<VmOverview> vms = List.of(
                overview("qemu", "arch", "Arch"),
                overview("utm", "zeta.utm", "Zeta"),
                overview("utm", "alpha.utm", "Alpha"),
                overview("qemu", "debian", "Debian")
        );

        assertEquals(
                List.of("utm:Alpha", "utm:Zeta", "qemu:Arch", "qemu:Debian"),
                sortedWithProvider(vms, List.of("utm", "qemu"))
        );
    }

    @Test
    void providersNotInTheConfiguredListComeLastOrderedByTheirId() {
        List<VmOverview> vms = List.of(
                overview("relic", "x", "Aardvark"),
                overview("utm", "y.utm", "Zulu"),
                overview("ancient", "z", "Bee")
        );

        assertEquals(
                List.of("utm:Zulu", "ancient:Bee", "relic:Aardvark"),
                sortedWithProvider(vms, List.of("utm"))
        );
    }

    @Test
    void namesThatDifferOnlyInCaseKeepAStableOrder() {
        List<VmOverview> vms = List.of(
                overview("utm", "lower.utm", "alpha"),
                overview("utm", "upper.utm", "Alpha")
        );

        // Equal ignoring case: the exact spelling decides, so the result does not depend on input order.
        assertEquals(List.of("Alpha", "alpha"), sorted(vms, List.of("utm")));
        assertEquals(List.of("Alpha", "alpha"), sorted(List.of(vms.get(1), vms.get(0)), List.of("utm")));
    }

    private static List<String> sorted(List<VmOverview> vms, List<String> providerOrder) {
        return vms.stream()
                .sorted(VmOverviewOrder.forProviders(providerOrder))
                .map(VmOverview::name)
                .toList();
    }

    private static List<String> sortedWithProvider(List<VmOverview> vms, List<String> providerOrder) {
        return vms.stream()
                .sorted(VmOverviewOrder.forProviders(providerOrder))
                .map(vm -> vm.providerId() + ":" + vm.name())
                .toList();
    }

    private static VmOverview overview(String providerId, String localId, String name) {
        return new VmOverview(
                new VmId(providerId, localId),
                providerId.toUpperCase(),
                name,
                new LocalVm(localId, name, List.of()),
                List.of(),
                VmStatus.STOPPED,
                VmChangeStatus.UNKNOWN
        );
    }
}
