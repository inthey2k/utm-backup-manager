package de.fjordkommission.utmbackup;

import java.nio.file.Path;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class AbstractIntegrationTest {

    protected static final Path TEST_ROOT =
            Path.of(System.getProperty("java.io.tmpdir"), "utm-backup-manager-test");

    protected static final Path TEST_BACKUP_ROOT =
            TEST_ROOT.resolve("backups");

    @DynamicPropertySource
    static void registerTestPaths(DynamicPropertyRegistry registry) {
        registry.add("vm.provider", () -> "test");
        registry.add("backup.root", TEST_BACKUP_ROOT::toString);
        registry.add(
                "backup.metadata-db",
                () -> TEST_ROOT.resolve("backup-manager.db").toString()
        );
        registry.add(
                "backup.legacy-state-file",
                () -> TEST_BACKUP_ROOT.resolve("backup-state.tsv").toString()
        );
    }
}