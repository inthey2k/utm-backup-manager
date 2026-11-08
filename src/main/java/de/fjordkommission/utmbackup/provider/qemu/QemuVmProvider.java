package de.fjordkommission.utmbackup.provider.qemu;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.ConditionalOnVmProvider;
import de.fjordkommission.utmbackup.provider.StableIdentityRule;
import de.fjordkommission.utmbackup.provider.VmIdentitySidecar;
import de.fjordkommission.utmbackup.provider.VmProvider;
import de.fjordkommission.utmbackup.storage.VmIdentityRegistry.IdentitySource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Integrates QEMU with the backup manager.
 * Each immediate subdirectory of the configured VM root is one VM and the complete
 * directory is the backup source. Its local id is a generated id kept in the
 * {@link VmIdentitySidecar} inside the directory, so a rename keeps the identity. A
 * directory without a top-level disk image is not a VM and is not listed.
 */
@Component
@Profile("!test")
@ConditionalOnVmProvider("qemu")
public class QemuVmProvider implements VmProvider {

    /** Provider id used in {@code vm.providers} and in canonical VM ids. */
    public static final String PROVIDER_ID = "qemu";

    private static final Logger log = LoggerFactory.getLogger(QemuVmProvider.class);

    /**
     * Stable identity of QEMU: the generated id in the VM's {@link VmIdentitySidecar}.
     * QEMU has no native id, so the sidecar is the only source.
     */
    public static final StableIdentityRule STABLE_IDENTITY = new SidecarRule();
    private static final List<String> DISK_SUFFIXES = List.of(".qcow2", ".raw");

    private final Path vmRoot;
    private final Supplier<List<QemuProcess>> processSource;

    @Autowired
    public QemuVmProvider(QemuProperties properties) {
        this(properties.directory(), QemuProcess::listAll);
    }

    /**
     * Allows tests to supply the process table. A null root means the
     * configuration is missing, and that is reported when VMs are listed.
     */
    QemuVmProvider(Path vmRoot, Supplier<List<QemuProcess>> processSource) {
        this.vmRoot = vmRoot == null ? null : vmRoot.toAbsolutePath().normalize();
        this.processSource = processSource;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "QEMU";
    }

    /** Identity decision of QEMU. See {@link #STABLE_IDENTITY}. */
    @Override
    public StableIdentityRule stableIdentity() {
        return STABLE_IDENTITY;
    }

    /** Reads the generated id from the sidecar of a VM directory or of a container's VM. */
    private static final class SidecarRule implements StableIdentityRule {

        @Override
        public String providerId() {
            return PROVIDER_ID;
        }

        @Override
        public Optional<Identity> ofPackage(Path packageDirectory) {
            return VmIdentitySidecar.read(packageDirectory, PROVIDER_ID)
                    .map(id -> new Identity(id, IdentitySource.GENERATED));
        }

        @Override
        public Optional<Identity> ofContainer(Path container) {
            return VmIdentitySidecar.readContainer(container, PROVIDER_ID)
                    .map(id -> new Identity(id, IdentitySource.GENERATED));
        }
    }

    @Override
    public List<ConfigurationEntry> configuration() {
        return List.of(
                new ConfigurationEntry(
                        "localVmDirectory",
                        "config.vmDirectory.label",
                        vmRoot == null ? "" : vmRoot.toString()
                )
        );
    }

    /**
     * Lists the VM directories directly below the VM root, sorted by name.
     * Ordinary files and hidden directories are ignored. A subdirectory that
     * cannot be read fails the whole listing, because its status as a VM is unknown.
     */
    @Override
    public List<LocalVm> findAll() {
        if (vmRoot == null) {
            throw new IllegalStateException(
                    "QEMU is enabled in vm.providers, but vm.qemu.directory is not configured"
            );
        }

        if (!Files.isDirectory(vmRoot)) {
            throw new IllegalStateException(
                    "QEMU VM directory does not exist or is not a directory: " + vmRoot
            );
        }

        try (Stream<Path> children = Files.list(vmRoot)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(path -> !isHidden(path.getFileName().toString()))
                    .filter(QemuVmProvider::hasDiskImage)
                    .map(path -> {
                        String name = path.getFileName().toString();

                        return new LocalVm(localId(path, name), name, List.of(path));
                    })
                    .sorted(Comparator.comparing(LocalVm::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException(
                    "QEMU VMs could not be read: " + vmRoot,
                    e
            );
        }
    }

    /**
     * The stable local id: the id in the VM's identity sidecar, created on first
     * discovery. When the sidecar cannot be read or written, the directory name is used,
     * as in 1.5.1, and a warning is logged. That identity is not stable across a rename.
     */
    private String localId(Path vmDirectory, String directoryName) {
        return VmIdentitySidecar.readOrCreate(vmDirectory, PROVIDER_ID).orElseGet(() -> {
            log.warn("No usable identity file in QEMU VM {}; its directory name is used as id",
                    directoryName);
            return directoryName;
        });
    }

    /**
     * Derives the states from the running QEMU processes. See
     * {@link QemuRuntimeStatus} for the matching rules.
     */
    @Override
    public Map<String, VmStatus> findStatuses() {
        return QemuRuntimeStatus.resolve(findAll(), processSource.get());
    }

    @Override
    public void stop(LocalVm vm) {
        throw new UnsupportedOperationException(
                "Stopping QEMU VMs is not implemented yet"
        );
    }

    /**
     * Returns whether the directory has a top-level disk image. A directory that
     * cannot be listed is a provider error, not an empty VM.
     */
    private static boolean hasDiskImage(Path vmDirectory) {
        try (Stream<Path> entries = Files.list(vmDirectory)) {
            return entries.anyMatch(QemuVmProvider::isDiskImage);
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException(
                    "QEMU VM directory could not be read: " + vmDirectory,
                    e
            );
        }
    }

    private static boolean isDiskImage(Path path) {
        String name = path.getFileName().toString();

        if (isHidden(name) || !Files.isRegularFile(path)) {
            return false;
        }

        String lower = name.toLowerCase(Locale.ROOT);

        return DISK_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    /**
     * Hidden entries, such as macOS {@code ._} metadata files on external drives,
     * are never VMs or disk images.
     */
    private static boolean isHidden(String name) {
        return name.startsWith(".");
    }
}
