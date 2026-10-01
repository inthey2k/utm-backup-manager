package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.LocalVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VmSizeServiceTest {

    @TempDir
    Path tempDirectory;

    private final VmSizeService service = new VmSizeService();

    @Test
    void calculatesSizeOfDirectoryTree() throws Exception {
        Path vm = tempDirectory.resolve("Test.utm");
        Path data = vm.resolve("Data");

        Files.createDirectories(data);

        Files.write(vm.resolve("config.plist"), new byte[100]);
        Files.write(data.resolve("disk.img"), new byte[1000]);

        LocalVm localVm = new LocalVm(
                "test-vm",
                "Test VM",
                List.of(vm)
        );

        assertEquals(1100, service.sizeOf(localVm));
    }

    @Test
    void calculatesSizeOfMultipleBackupSources() throws Exception {
        Path first = tempDirectory.resolve("First");
        Path second = tempDirectory.resolve("Second");

        Files.createDirectories(first);
        Files.createDirectories(second);

        Files.write(first.resolve("one.dat"), new byte[100]);
        Files.write(second.resolve("two.dat"), new byte[200]);

        LocalVm vm = new LocalVm(
                "test-vm",
                "Test VM",
                List.of(first, second)
        );

        assertEquals(300, service.sizeOf(vm));
    }

    @Test
    void failsWhenBackupSourceDoesNotExist() {
        LocalVm vm = new LocalVm(
                "test-vm",
                "Test VM",
                List.of(tempDirectory.resolve("Missing.utm"))
        );

        assertThrows(
                UncheckedIOException.class,
                () -> service.sizeOf(vm)
        );
    }
}