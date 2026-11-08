package de.fjordkommission.utmbackup.provider;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-VM identity file: created once and safely, read back exactly, and ignored
 * whenever it is unreadable, malformed or owned by another provider. Disk images are
 * never touched.
 */
class VmIdentitySidecarTest {

    private static final String QEMU = "qemu";

    @TempDir
    Path vm;

    @Test
    void createsAnIdOnceAndReadsTheSameIdAfterwards() throws IOException {
        Files.writeString(vm.resolve("disk.qcow2"), "disk");

        String created = VmIdentitySidecar.readOrCreate(vm, QEMU).orElseThrow();

        assertTrue(created.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertEquals(Optional.of(created), VmIdentitySidecar.read(vm, QEMU), "a later read returns the same id");
        assertEquals(Optional.of(created), VmIdentitySidecar.readOrCreate(vm, QEMU), "creation is not repeated");
        assertEquals("format=1\nprovider=qemu\nid=" + created + "\n",
                Files.readString(vm.resolve(VmIdentitySidecar.FILE_NAME)));
    }

    @Test
    void diskImagesAndOtherFilesAreNotChanged() throws IOException {
        Path disk = vm.resolve("disk.qcow2");
        Files.write(disk, new byte[] {1, 2, 3, 4});
        Files.writeString(vm.resolve("config.txt"), "keep");

        VmIdentitySidecar.readOrCreate(vm, QEMU);

        assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(disk));
        assertEquals("keep", Files.readString(vm.resolve("config.txt")));
        try (var names = Files.list(vm)) {
            assertEquals(Set.of("disk.qcow2", "config.txt", VmIdentitySidecar.FILE_NAME),
                    names.map(path -> path.getFileName().toString()).collect(Collectors.toSet()),
                    "no temporary file may remain");
        }
    }

    @Test
    void concurrentCreationYieldsOneIdForEveryCaller() throws Exception {
        ExecutorService pool = newFixedThreadPool(8);

        try {
            List<Callable<Optional<String>>> callers = IntStream.range(0, 8)
                    .<Callable<Optional<String>>>mapToObj(i -> () -> VmIdentitySidecar.readOrCreate(vm, QEMU))
                    .toList();

            Set<Optional<String>> results = new HashSet<>();

            for (Future<Optional<String>> future : pool.invokeAll(callers)) {
                results.add(future.get());
            }

            assertEquals(1, results.size(), "every caller must see the same identity");
            assertTrue(results.iterator().next().isPresent());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unreadableOrMalformedFileIsIgnoredAndNotOverwritten() throws IOException {
        String garbage = "this is not an identity file";
        Files.writeString(vm.resolve(VmIdentitySidecar.FILE_NAME), garbage);

        assertEquals(Optional.empty(), VmIdentitySidecar.read(vm, QEMU));
        assertEquals(Optional.empty(), VmIdentitySidecar.readOrCreate(vm, QEMU));
        assertEquals(garbage, Files.readString(vm.resolve(VmIdentitySidecar.FILE_NAME)),
                "a damaged file is left for the user, never replaced");
    }

    @Test
    void invalidUuidIsIgnored() throws IOException {
        Files.writeString(vm.resolve(VmIdentitySidecar.FILE_NAME),
                "format=1\nprovider=qemu\nid=not-a-uuid\n");

        assertEquals(Optional.empty(), VmIdentitySidecar.read(vm, QEMU));
    }

    @Test
    void anotherProvidersFileIsNeverUsedForQemu() throws IOException {
        Files.writeString(vm.resolve(VmIdentitySidecar.FILE_NAME),
                "format=1\nprovider=utm\nid=6c1f2a3b-0000-4000-8000-00000000d001\n");

        assertEquals(Optional.empty(), VmIdentitySidecar.read(vm, QEMU), "provider isolation");
        assertEquals(Optional.empty(), VmIdentitySidecar.readOrCreate(vm, QEMU));
        assertTrue(Files.readString(vm.resolve(VmIdentitySidecar.FILE_NAME)).contains("provider=utm"));
    }

    @Test
    void missingFileIsEmptyForReadButCreatable() {
        assertEquals(Optional.empty(), VmIdentitySidecar.read(vm, QEMU));
        assertTrue(VmIdentitySidecar.readOrCreate(vm, QEMU).isPresent());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void readOnlyDirectoryYieldsNoIdentityAndChangesNothing() throws IOException {
        Files.setPosixFilePermissions(vm, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Assumptions.assumeFalse(Files.isWritable(vm), "write permission cannot be removed for this user");

            assertEquals(Optional.empty(), VmIdentitySidecar.readOrCreate(vm, QEMU));
            try (var names = Files.list(vm)) {
                assertEquals(0, names.count());
            }
        } finally {
            Files.setPosixFilePermissions(vm, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void containerAgreesOnOneIdOrYieldsNothing() throws IOException {
        Path container = Files.createDirectories(vm.resolve("container"));
        Path first = Files.createDirectories(container.resolve("First"));
        Path second = Files.createDirectories(container.resolve("Second"));
        String id = VmIdentitySidecar.readOrCreate(first, QEMU).orElseThrow();
        Files.copy(first.resolve(VmIdentitySidecar.FILE_NAME), second.resolve(VmIdentitySidecar.FILE_NAME));

        assertEquals(Optional.of(id), VmIdentitySidecar.readContainer(container, QEMU));

        Files.writeString(second.resolve(VmIdentitySidecar.FILE_NAME),
                "format=1\nprovider=qemu\nid=3f1c0a2e-0000-4000-8000-00000000c002\n");

        assertEquals(Optional.empty(), VmIdentitySidecar.readContainer(container, QEMU),
                "packages that disagree make the identity unknown");
        assertEquals(Optional.empty(), VmIdentitySidecar.readContainer(vm.resolve("missing"), QEMU));
    }
}
