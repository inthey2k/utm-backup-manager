package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.repository.VmStateRepository;
import de.fjordkommission.utmbackup.repository.VmStateRepository.Acknowledgement;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import de.fjordkommission.utmbackup.storage.VmIdentityFile;
import de.fjordkommission.utmbackup.storage.VmIdentityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Change tracking after an identity change: a state stored under the historical package
 * name is still used for the VM's stable id, so the VM does not appear falsely changed.
 */
class VmChangeStatusAliasTest {

    private static final String UUID = "6C1F2A3B-0000-4000-8000-00000000A002";
    private static final long ACKNOWLEDGED = 1_700_000_000L;

    @TempDir
    Path tempDir;

    private Path root;
    private Path pkg;
    private VmStateRepository repository;
    private VmChangeStatusService service;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tempDir.resolve("backups"));
        pkg = Files.createDirectories(tempDir.resolve("vms").resolve("Linux Mint 27.utm"));
        Path config = Files.writeString(pkg.resolve("config.plist"), "configuration");
        FileTime time = FileTime.fromMillis(ACKNOWLEDGED * 1000);
        Files.setLastModifiedTime(config, time);
        Files.setLastModifiedTime(pkg, time);

        BackupStorageGuard guard = mock(BackupStorageGuard.class);
        repository = mock(VmStateRepository.class);
        service = new VmChangeStatusService(repository,
                new VmIdentityService(new BackupProperties(root, null, null), guard));
    }

    private void recordAlias() throws IOException {
        VmIdentityFile.write(root, VmIdentityRegistry.empty().observe(new VmIdentityRegistry.Observation(
                new VmId("utm", UUID),
                VmIdentityRegistry.IdentitySource.PROVIDER_NATIVE,
                "Linux Mint 23.utm",
                "Linux Mint 23",
                false)));
    }

    @Test
    void stateStoredUnderTheHistoricalIdIsUsedForTheStableId() throws IOException {
        recordAlias();
        when(repository.find(new VmId("utm", "Linux Mint 23.utm")))
                .thenReturn(Optional.of(new VmStateRepository.State(ACKNOWLEDGED, Acknowledgement.BACKED_UP, "t")));
        LocalVm vm = new LocalVm(UUID, "Linux Mint 27", List.of(pkg));

        assertEquals(VmChangeStatus.UNCHANGED, service.determine(new VmId("utm", UUID), vm, true));
    }

    @Test
    void aChangedVmStillShowsAsChangedThroughTheAlias() throws IOException {
        recordAlias();
        when(repository.find(new VmId("utm", "Linux Mint 23.utm")))
                .thenReturn(Optional.of(new VmStateRepository.State(ACKNOWLEDGED, Acknowledgement.BACKED_UP, "t")));
        Files.setLastModifiedTime(pkg.resolve("config.plist"), FileTime.fromMillis((ACKNOWLEDGED + 100) * 1000));

        assertEquals(VmChangeStatus.CHANGED,
                service.determine(new VmId("utm", UUID), new LocalVm(UUID, "Linux Mint 27", List.of(pkg)), true));
    }

    @Test
    void canonicalStateWinsOverAnAlias() throws IOException {
        recordAlias();
        when(repository.find(new VmId("utm", UUID)))
                .thenReturn(Optional.of(new VmStateRepository.State(ACKNOWLEDGED + 1, Acknowledgement.BACKED_UP, "t")));

        assertEquals(VmChangeStatus.CHANGED,
                service.determine(new VmId("utm", UUID), new LocalVm(UUID, "Linux Mint 27", List.of(pkg)), true));
    }

    @Test
    void acknowledgementIsWrittenUnderTheCanonicalIdOnly() throws IOException {
        recordAlias();

        service.acknowledgeBackup(new VmId("utm", UUID), new LocalVm(UUID, "Linux Mint 27", List.of(pkg)));

        verify(repository).save(new VmId("utm", UUID), ACKNOWLEDGED, Acknowledgement.BACKED_UP);
        verify(repository, never()).save(new VmId("utm", "Linux Mint 23.utm"), ACKNOWLEDGED, Acknowledgement.BACKED_UP);
    }
}
