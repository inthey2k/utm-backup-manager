package de.fjordkommission.utmbackup.controller;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import de.fjordkommission.utmbackup.AbstractIntegrationTest;
import de.fjordkommission.utmbackup.service.BackupJobService;
import de.fjordkommission.utmbackup.service.BackupJobStatus;
import de.fjordkommission.utmbackup.service.BackupScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackupControllerIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    private Path backupRoot;
    @Autowired
    private BackupScanner backupScanner;
    @Autowired
    private BackupJobService backupJobService;
    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        Path testRoot = Path.of(
                System.getProperty("java.io.tmpdir"),
                "utm-backup-manager-test"
        );

        registry.add(
                "backup.root",
                () -> testRoot.resolve("backups").toString()
        );
        registry.add(
                "backup.metadata-db",
                () -> testRoot.resolve("backup-manager.db").toString()
        );
        registry.add(
                "backup.legacy-state-file",
                () -> testRoot.resolve("backups/backup-state.tsv").toString()
        );
    }

    @BeforeEach
    void cleanTestBackups() throws IOException {
        backupRoot = TEST_BACKUP_ROOT;

        if (Files.exists(backupRoot)) {
            try (var paths = Files.walk(backupRoot)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }

        Files.createDirectories(backupRoot);

        Path testVm = TEST_ROOT.resolve("vms").resolve("TestLinux.utm");

        Files.createDirectories(testVm.resolve("Data"));
        Files.writeString(
                testVm.resolve("config.plist"),
                "integration test configuration"
        );
        Files.writeString(
                testVm.resolve("Data/disk.img"),
                "integration test disk"
        );
    }

    @Test
    void overviewPageShowsBackupForLocalVm() throws Exception {
        createTestBackup();

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(content().string(containsString("Test Linux VM")))
                .andExpect(content().string(containsString("Integration test backup")));
    }

    @Test
    void overviewPageShowsLocalVm() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(content().string(containsString("Test Linux VM")));
    }

    @Test
    void usesTestPaths() {
        assertEquals(
                TEST_BACKUP_ROOT.toString(),
                environment.getProperty("backup.root")
        );

        assertEquals(
                TEST_BACKUP_ROOT.resolve("backup-state.tsv").toString(),
                environment.getProperty("backup.legacy-state-file")
        );

        assertEquals(
                TEST_ROOT.resolve("backup-manager.db").toString(),
                environment.getProperty("backup.metadata-db")
        );

        assertEquals(
                TEST_BACKUP_ROOT,
                backupScanner.root()
        );
    }
    @Test
    void startsBackupForStoppedVmAndReportsStatus() throws Exception {
        mockMvc.perform(post("/backup")
                        .param("vmIds", "test-linux-vm")
                        .param("comment", "Integration test backup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(view().name("redirect:/"))
                .andExpect(flash().attribute(
                        "message",
                        startsWith("Backup started ")
                ));

        waitForBackupJob();

        mockMvc.perform(get("/backup/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.backupDirectoryName").isNotEmpty())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        try (var directories = Files.list(backupRoot)) {
            Path createdBackup = directories
                    .filter(Files::isDirectory)
                    .findFirst()
                    .orElseThrow();

            assertEquals(
                    "integration test configuration",
                    Files.readString(
                            createdBackup.resolve(
                                    "VMs/TestLinux.utm/config.plist"
                            )
                    )
            );

            assertEquals(
                    "integration test disk",
                    Files.readString(
                            createdBackup.resolve(
                                    "VMs/TestLinux.utm/Data/disk.img"
                            )
                    )
            );

            String backupInfo = Files.readString(
                    createdBackup.resolve("BACKUP-INFO.txt")
            );

            assertTrue(
                    backupInfo.contains("Integration test backup")
            );
        }
    }

    @Test
    void usesCookieOnlySessionTracking() {
        assertEquals(
                "cookie",
                environment.getProperty("server.servlet.session.tracking-modes")
        );
    }

    @Test
    void rejectsBackupWithoutSelectedVm() throws Exception {
        mockMvc.perform(post("/backup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(view().name("redirect:/"))
                .andExpect(redirectedUrl("/"))
                .andExpect(header().string("Location", "/"))
                .andExpect(flash().attributeExists("error"));

        assertTrue(isDirectoryEmpty(backupRoot));
    }

    @Test
    void deletingOneBackupViaControllerKeepsOtherBackupInSameBatch() throws Exception {

        String directoryName = "2026-09-30_1500";

        Path batchDirectory = backupRoot.resolve(directoryName);
        Path vmDirectory = batchDirectory.resolve("VMs");

        Path firstVmPath = vmDirectory.resolve("deletable-test-vm");
        Path secondVmPath = vmDirectory.resolve("second-deletable-test-vm");

        Files.createDirectories(firstVmPath);
        Files.createDirectories(secondVmPath);

        Files.writeString(
                firstVmPath.resolve("test.txt"),
                "This VM backup must be deleted."
        );

        Files.writeString(
                secondVmPath.resolve("test.txt"),
                "This VM backup must remain."
        );

        var overview = backupScanner.scan();

        var firstVm = overview.vms().stream()
                .filter(vm -> vm.name().equals("Deletable Test VM"))
                .findFirst()
                .orElseThrow();

        var backupToDelete = firstVm.backups().stream()
                .filter(backup ->
                        backup.backupDirectoryName().equals(directoryName))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(
                        post("/delete/{id}", backupToDelete.id())
                )
                .andExpect(status().is3xxRedirection())
                .andExpect(view().name("redirect:/"));

        assertFalse(Files.exists(firstVmPath));

        assertTrue(Files.isDirectory(secondVmPath));
        assertTrue(Files.exists(secondVmPath.resolve("test.txt")));
        assertTrue(Files.isDirectory(batchDirectory));

        var afterDeletion = backupScanner.scan();

        var secondVm = afterDeletion.vms().stream()
                .filter(vm ->
                        vm.name().equals("Second Deletable Test VM"))
                .findFirst()
                .orElseThrow();

        assertTrue(
                secondVm.backups().stream()
                        .anyMatch(backup ->
                                backup.backupDirectoryName()
                                        .equals(directoryName))
        );
    }

    /**
     * Waits only for the real background job; file-copy correctness is still
     * verified below as part of this end-to-end controller integration test.
     */
    private void waitForBackupJob() {
        await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> backupJobService.currentJob()
                        .map(job -> job.status() != BackupJobStatus.RUNNING)
                        .orElse(true));
    }

    private void createTestBackup() throws IOException {

        Path backupDirectory = backupRoot.resolve("2026-09-29_1335");

        Files.createDirectories(
                backupDirectory.resolve("VMs/test-linux-vm")
        );

        Files.writeString(
                backupDirectory.resolve("BACKUP-INFO.txt"),
                """
                VMs:
                test-linux-vm
    
                Comment:
                Integration test backup
                """
        );
    }

    private boolean isDirectoryEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }
}