package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackupIdentityTest {

    /** Format-1 ids produced by 1.4.x. They are stored in SQLite and must never change. */
    @Test
    void legacyGoldenIdsAreUnchanged() {
        assertEquals("MjAyNi0wOS0wMV8xMjAwCkxpbnV4LnV0bQ", BackupIdentity.legacyId("2026-09-01_1200", "Linux.utm"));
        assertEquals("MjAyNi0wOS0wMl8xMjAwX3N0YWJsZQpMaW51eC51dG0", BackupIdentity.legacyId("2026-09-02_1200_stable", "Linux.utm"));
    }

    @Test
    void formatTwoIdIsDeterministicAndPinned() {
        String id = BackupIdentity.formatTwoId("2026-10-20_1200", new VmId("utm", "ArchLinux 02.27.utm"));

        assertEquals("Zm9ybWF0LTIKMjAyNi0xMC0yMF8xMjAwCnV0bTpBcmNoTGludXggMDIuMjcudXRt", id);
        assertEquals(id, BackupIdentity.formatTwoId("2026-10-20_1200", new VmId("utm", "ArchLinux 02.27.utm")));
    }

    @Test
    void sameLocalIdUnderDifferentProvidersGetsDifferentIds() {
        String utm = BackupIdentity.formatTwoId("2026-10-20_1200", new VmId("utm", "ArchLinux"));
        String qemu = BackupIdentity.formatTwoId("2026-10-20_1200", new VmId("qemu", "ArchLinux"));

        assertNotEquals(utm, qemu);
        assertEquals("Zm9ybWF0LTIKMjAyNi0xMC0yMF8xMjAwCnFlbXU6QXJjaExpbnV4", qemu);
    }

    @Test
    void formatTwoIdsAreDistinctFromFormatOneIdsOfTheSameStrings() {
        String legacy = BackupIdentity.legacyId("2026-10-20_1200", "utm:ArchLinux");
        String formatTwo = BackupIdentity.formatTwoId("2026-10-20_1200", new VmId("utm", "ArchLinux"));

        assertNotEquals(legacy, formatTwo);
    }

    @Test
    void formatTwoIdDependsOnTheBatchAsWellAsTheVm() {
        VmId vm = new VmId("utm", "Linux.utm");

        assertNotEquals(
                BackupIdentity.formatTwoId("2026-10-20_1200", vm),
                BackupIdentity.formatTwoId("2026-10-20_1300", vm)
        );
    }
}
