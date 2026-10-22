package de.fjordkommission.utmbackup.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BackupOverviewTest {

    private static final String UTM = "utm";
    private static final String QEMU = "qemu";
    private static final String EXAMPLE = "example";

    @Test
    void providerBadgeIsDerivedFromProviderId() {
        assertEquals("UTM", VmId.providerBadge(UTM));
        assertEquals("QEMU", VmId.providerBadge(QEMU));

        VmOverview overview = overview(new VmId(QEMU, "ArchLinux"));

        assertEquals("QEMU", overview.providerBadge());
    }

    @Test
    void providerHealthRequiresListedVmsAndNoErrors() {
        BackupOverview overview = new BackupOverview(
                List.of(),
                Set.of(),
                Set.of(),
                3,
                List.of(
                        new ProviderSnapshot(UTM, "UTM", true, List.of(), Map.of(), List.of()),
                        new ProviderSnapshot(QEMU, "QEMU", false, List.of(), Map.of(),
                                List.of("Could not list VMs: daemon is not running")),
                        new ProviderSnapshot(EXAMPLE, "Example", true, List.of(), Map.of(),
                                List.of("Could not determine VM states"))
                ),
                List.of(new BackupBatchIssue("2026-10-20_1200", "reason"))
        );

        assertTrue(overview.providerEnabled(QEMU));
        assertFalse(overview.providerEnabled("missing"));
        assertTrue(overview.providerHealthy(UTM));
        assertFalse(overview.providerHealthy(QEMU));
        assertFalse(overview.providerHealthy(EXAMPLE));
        assertTrue(overview.hasBatchIssue("2026-10-20_1200"));
        assertFalse(overview.hasBatchIssue("2026-10-21_1200"));
    }

    private static VmOverview overview(VmId id) {
        return new VmOverview(
                id,
                id.providerId(),
                id.localId(),
                null,
                List.of(),
                VmStatus.UNKNOWN,
                VmChangeStatus.UNKNOWN
        );
    }
}
