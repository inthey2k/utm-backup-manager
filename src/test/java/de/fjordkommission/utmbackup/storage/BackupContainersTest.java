package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BackupContainersTest {

    @Test
    void safeLocalIdKeepsItsNameWithoutAHash() {
        Map<VmId, String> names = BackupContainers.assign(List.of(new VmId("utm", "test-linux-vm")));

        assertEquals("utm__test-linux-vm", names.get(new VmId("utm", "test-linux-vm")));
    }

    @Test
    void changedLocalIdGetsAHashOfItsCanonicalId() {
        VmId id = new VmId("utm", "ArchLinux 02.27.utm");

        String name = BackupContainers.assign(List.of(id)).get(id);

        assertEquals("utm__ArchLinux-02-27-utm-6a667c24", name);
    }

    @Test
    void sameLocalIdUnderDifferentProvidersGetsDifferentContainers() {
        Map<VmId, String> names = BackupContainers.assign(List.of(
                new VmId("utm", "ArchLinux"),
                new VmId("qemu", "ArchLinux")));

        assertEquals("utm__ArchLinux", names.get(new VmId("utm", "ArchLinux")));
        assertEquals("qemu__ArchLinux", names.get(new VmId("qemu", "ArchLinux")));
    }

    @Test
    void namesThatDifferOnlyInCaseNeverCollide() {
        Map<VmId, String> names = BackupContainers.assign(List.of(
                new VmId("utm", "ArchLinux"),
                new VmId("utm", "archlinux")));

        Set<String> lowerCase = names.values().stream()
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());

        assertEquals(2, lowerCase.size(), names.toString());
    }

    @Test
    void namesAreSingleSafePathElements() {
        List<VmId> hostile = List.of(
                new VmId("utm", "../../etc"),
                new VmId("utm", "a/b\\c"),
                new VmId("utm", ".."),
                new VmId("utm", "."),
                new VmId("utm", "x y:z.utm"));

        Map<VmId, String> names = BackupContainers.assign(hostile);

        for (String name : names.values()) {
            assertEquals(1, Path.of(name).getNameCount(), name);
            assertFalse(name.contains("/") || name.contains("\\") || name.contains(".."), name);
            assertNotEquals(".", name);
        }
    }

    @Test
    void longLocalIdsAreTruncatedAndStillUnique() {
        VmId first = new VmId("utm", "x".repeat(200) + "1");
        VmId second = new VmId("utm", "x".repeat(200) + "2");

        Map<VmId, String> names = BackupContainers.assign(List.of(first, second));

        assertTrue(names.get(first).length() < 100, names.get(first));
        assertNotEquals(names.get(first), names.get(second));
    }

    @Test
    void assignmentIsDeterministicAndIndependentOfInputOrder() {
        List<VmId> ids = List.of(
                new VmId("utm", "archlinux"),
                new VmId("utm", "ArchLinux"),
                new VmId("qemu", "ArchLinux"));

        Map<VmId, String> forward = BackupContainers.assign(ids);
        Map<VmId, String> reversed = BackupContainers.assign(
                List.of(ids.get(2), ids.get(1), ids.get(0)));

        assertEquals(forward, reversed);
        assertEquals(forward, BackupContainers.assign(ids));
    }
}
