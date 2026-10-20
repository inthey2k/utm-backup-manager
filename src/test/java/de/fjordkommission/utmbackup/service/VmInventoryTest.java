package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.ManagedVm;
import de.fjordkommission.utmbackup.model.ProviderSnapshot;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.FakeVmProvider;
import de.fjordkommission.utmbackup.provider.VmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VmInventoryTest {

    private static LocalVm vm(String id, String name) {
        return new LocalVm(id, name, List.of(Path.of("/vms", id)));
    }

    private static VmInventory inventory(FakeVmProvider... providers) {
        List<String> ids = java.util.Arrays.stream(providers)
                .map(FakeVmProvider::providerId)
                .toList();

        return new VmInventory(new VmProviderRegistry(
                List.of(providers),
                new VmProperties(ids, null, null)
        ));
    }

    private static ProviderSnapshot snapshot(VmInventory.Inventory inventory, String providerId) {
        return inventory.providers().stream()
                .filter(snapshot -> snapshot.providerId().equals(providerId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void singleProviderInventoryContainsItsVmsWithCanonicalIds() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(vm("Linux.utm", "Linux")),
                Map.of("Linux.utm", VmStatus.STOPPED)
        );

        VmInventory.Inventory inventory = inventory(utm).build();

        assertEquals(1, inventory.vms().size());
        ManagedVm vm = inventory.vms().getFirst();
        assertEquals(new VmId("utm", "Linux.utm"), vm.id());
        assertEquals("UTM (fake)", vm.providerName());
        assertEquals("Linux", vm.name());
        assertEquals(VmStatus.STOPPED, vm.status());
        assertFalse(snapshot(inventory, "utm").hasErrors());
    }

    @Test
    void twoProvidersAreAggregatedInConfiguredOrder() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(vm("Linux.utm", "Linux")),
                Map.of("Linux.utm", VmStatus.STOPPED)
        );
        FakeVmProvider qemu = FakeVmProvider.of(
                "qemu",
                List.of(vm("FreeBSD", "FreeBSD")),
                Map.of("FreeBSD", VmStatus.RUNNING)
        );

        VmInventory.Inventory inventory = inventory(utm, qemu).build();

        assertEquals(
                List.of(new VmId("utm", "Linux.utm"), new VmId("qemu", "FreeBSD")),
                inventory.vms().stream().map(ManagedVm::id).toList()
        );
        assertEquals(VmStatus.RUNNING, inventory.find(new VmId("qemu", "FreeBSD")).orElseThrow().status());
    }

    @Test
    void sameLocalIdInTwoProvidersProducesTwoDistinctVmIds() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(vm("ArchLinux", "ArchLinux")),
                Map.of("ArchLinux", VmStatus.STOPPED)
        );
        FakeVmProvider qemu = FakeVmProvider.of(
                "qemu",
                List.of(vm("ArchLinux", "ArchLinux")),
                Map.of("ArchLinux", VmStatus.RUNNING)
        );

        VmInventory.Inventory inventory = inventory(utm, qemu).build();

        VmId utmId = new VmId("utm", "ArchLinux");
        VmId qemuId = new VmId("qemu", "ArchLinux");

        assertEquals(VmStatus.STOPPED, inventory.find(utmId).orElseThrow().status());
        assertEquals(VmStatus.RUNNING, inventory.find(qemuId).orElseThrow().status());
        assertEquals("utm:ArchLinux", utmId.toString());
        assertEquals("qemu:ArchLinux", qemuId.toString());
    }

    @Test
    void sameDisplayNameAcrossProvidersRemainsDistinct() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(vm("Arch.utm", "Arch")),
                Map.of("Arch.utm", VmStatus.STOPPED)
        );
        FakeVmProvider qemu = FakeVmProvider.of(
                "qemu",
                List.of(vm("arch-qemu", "Arch")),
                Map.of("arch-qemu", VmStatus.STOPPED)
        );

        VmInventory.Inventory inventory = inventory(utm, qemu).build();

        assertEquals(2, inventory.vms().size());
        assertTrue(inventory.vms().stream().allMatch(vm -> vm.name().equals("Arch")));
        assertEquals(2, inventory.vms().stream().map(ManagedVm::id).distinct().count());
    }

    @Test
    void requestIdsWithSpacesDotsAndColonsResolve() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(vm("ArchLinux 02.27.utm", "ArchLinux 02.27")),
                Map.of("ArchLinux 02.27.utm", VmStatus.STOPPED)
        );

        VmInventory.Inventory inventory = inventory(utm).build();

        assertTrue(inventory.find(VmId.parse("utm:ArchLinux 02.27.utm")).isPresent());
        assertTrue(inventory.find(new VmId("utm", "ArchLinux 02.27.utm")).isPresent());
    }

    @Test
    void listingFailureOfOneProviderDoesNotHideAnotherProvider() {
        FakeVmProvider broken = FakeVmProvider.listingFails("qemu", new IllegalStateException("qemu dir unreadable"));
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(vm("Linux.utm", "Linux")),
                Map.of("Linux.utm", VmStatus.STOPPED)
        );

        VmInventory.Inventory inventory = inventory(broken, utm).build();

        assertEquals(List.of(new VmId("utm", "Linux.utm")), inventory.vms().stream().map(ManagedVm::id).toList());

        ProviderSnapshot failed = snapshot(inventory, "qemu");
        assertFalse(failed.listed());
        assertTrue(failed.localVms().isEmpty());
        assertTrue(failed.errors().getFirst().contains("qemu dir unreadable"));
        assertFalse(snapshot(inventory, "utm").hasErrors());
    }

    @Test
    void statusFailureKeepsVmsAsUnknownForThatProviderOnly() {
        FakeVmProvider utm = FakeVmProvider.statusFails(
                "utm",
                List.of(vm("Linux.utm", "Linux")),
                new IllegalStateException("utmctl exploded")
        );
        FakeVmProvider qemu = FakeVmProvider.of(
                "qemu",
                List.of(vm("FreeBSD", "FreeBSD")),
                Map.of("FreeBSD", VmStatus.STOPPED)
        );

        VmInventory.Inventory inventory = inventory(utm, qemu).build();

        ManagedVm linux = inventory.find(new VmId("utm", "Linux.utm")).orElseThrow();
        assertEquals(VmStatus.UNKNOWN, linux.status(), "an error must never be reported as STOPPED");

        assertEquals(VmStatus.STOPPED, inventory.find(new VmId("qemu", "FreeBSD")).orElseThrow().status());

        ProviderSnapshot failed = snapshot(inventory, "utm");
        assertTrue(failed.listed());
        assertTrue(failed.errors().getFirst().contains("utmctl exploded"));
        assertFalse(snapshot(inventory, "qemu").hasErrors());
    }

    @Test
    void duplicateLocalIdsWithinOneProviderAreExcludedAndFlagged() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(
                        vm("Same.utm", "Same"),
                        vm("Same.utm", "Same again"),
                        vm("Other.utm", "Other")
                ),
                Map.of("Same.utm", VmStatus.STOPPED, "Other.utm", VmStatus.STOPPED)
        );

        VmInventory.Inventory inventory = inventory(utm).build();

        assertEquals(
                List.of(new VmId("utm", "Other.utm")),
                inventory.vms().stream().map(ManagedVm::id).toList(),
                "ambiguous VMs must not be offered for backup or deletion"
        );

        ProviderSnapshot snapshot = snapshot(inventory, "utm");
        assertTrue(snapshot.errors().stream().anyMatch(error -> error.contains("Duplicate VM id 'Same.utm'")));
    }

    @Test
    void vmWithoutIdIsIgnoredAndFlagged() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(vm(" ", "Blank"), vm("Linux.utm", "Linux")),
                Map.of("Linux.utm", VmStatus.STOPPED)
        );

        VmInventory.Inventory inventory = inventory(utm).build();

        assertEquals(1, inventory.vms().size());
        assertTrue(snapshot(inventory, "utm").errors().getFirst().contains("without an id"));
    }

    @Test
    void providerMethodsAreCalledAtMostOncePerBuild() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(vm("Linux.utm", "Linux")),
                Map.of("Linux.utm", VmStatus.STOPPED)
        );
        FakeVmProvider qemu = FakeVmProvider.of("qemu", List.of(), Map.of());

        inventory(utm, qemu).build();

        assertEquals(1, utm.listCalls());
        assertEquals(1, utm.statusCalls());
        assertEquals(1, qemu.listCalls());
        assertEquals(1, qemu.statusCalls());
    }

    @Test
    void failingDisplayNameFallsBackToProviderIdWithoutBreakingInventory() {
        VmProvider nameless = new VmProvider() {
            @Override
            public String providerId() {
                return "qemu";
            }

            @Override
            public String displayName() {
                throw new IllegalStateException("name lookup failed");
            }

            @Override
            public List<ConfigurationEntry> configuration() {
                return List.of();
            }

            @Override
            public List<LocalVm> findAll() {
                return List.of(vm("FreeBSD", "FreeBSD"));
            }

            @Override
            public Map<String, VmStatus> findStatuses() {
                return Map.of("FreeBSD", VmStatus.STOPPED);
            }

            @Override
            public void stop(LocalVm vm) {
                throw new UnsupportedOperationException();
            }
        };
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(vm("Linux.utm", "Linux")),
                Map.of("Linux.utm", VmStatus.STOPPED)
        );

        VmInventory.Inventory inventory = new VmInventory(new VmProviderRegistry(
                List.of(nameless, utm),
                new VmProperties(List.of("qemu", "utm"), null, null)
        )).build();

        assertEquals(2, inventory.vms().size());
        assertEquals("qemu", inventory.vms().getFirst().providerName());
        assertTrue(snapshot(inventory, "qemu").errors().getFirst().contains("name lookup failed"));
    }

    @Test
    void missingStatusEntryMeansUnknown() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(vm("Linux.utm", "Linux")),
                Map.of()
        );

        VmInventory.Inventory inventory = inventory(utm).build();

        assertEquals(VmStatus.UNKNOWN, inventory.vms().getFirst().status());
    }

    @Test
    void inventoryFindReturnsEmptyForUnknownIdAndForSameLocalIdOfOtherProvider() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(vm("ArchLinux", "ArchLinux")),
                Map.of("ArchLinux", VmStatus.STOPPED)
        );

        VmInventory.Inventory inventory = inventory(utm).build();

        Optional<ManagedVm> other = inventory.find(new VmId("qemu", "ArchLinux"));
        assertTrue(other.isEmpty());
        assertTrue(inventory.find(new VmId("utm", "Missing")).isEmpty());
    }
}
