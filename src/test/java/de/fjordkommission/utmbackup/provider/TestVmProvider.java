package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * VM provider used by integration tests.
 *
 * It keeps tests independent of an installed hypervisor and prevents access
 * to real local VMs.
 */
@Component
@Profile("test")
public class TestVmProvider implements VmProvider {

    @Override
    public String providerId() {
        return "test";
    }

    @Override
    public String displayName() {
        return "Test";
    }

    @Override
    public List<LocalVm> findAll() {
        Path testRoot = Path.of(
                System.getProperty("java.io.tmpdir"),
                "utm-backup-manager-test",
                "vms"
        );

        return List.of(
                new LocalVm(
                        "test-linux-vm",
                        "Test Linux VM",
                        List.of(testRoot.resolve("TestLinux.utm"))
                ),
                new LocalVm(
                        "deletable-test-vm",
                        "Deletable Test VM",
                        List.of(testRoot.resolve("DeletableTest.utm"))
                ),
                new LocalVm(
                        "second-deletable-test-vm",
                        "Second Deletable Test VM",
                        List.of(testRoot.resolve("SecondDeletableTest.utm"))
                )
        );
    }

    @Override
    public Map<String, VmStatus> findStatuses() {
        return Map.of(
                "Test Linux VM", VmStatus.STOPPED,
                "Deletable Test VM", VmStatus.STOPPED,
                "Second Deletable Test VM", VmStatus.STOPPED
        );
    }
    @Override
    public void stop(LocalVm vm) {
        // Nothing to stop in the test environment.
    }
}