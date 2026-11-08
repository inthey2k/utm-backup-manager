package de.fjordkommission.utmbackup.provider.utm;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.ConditionalOnVmProvider;
import de.fjordkommission.utmbackup.provider.StableIdentityRule;
import de.fjordkommission.utmbackup.provider.VmProvider;
import de.fjordkommission.utmbackup.storage.VmIdentityRegistry.IdentitySource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Integrates UTM with the backup manager.
 * UTM stores each VM as a .utm package directory. The complete package is
 * therefore exposed as the backup source for that VM. The local id is the native
 * UUID from {@code config.plist}, so renaming a package keeps the VM's identity.
 */
@Component
@Profile("!test")
@ConditionalOnVmProvider("utm")
public class UtmVmProvider implements VmProvider {

    /** Provider id used in {@code vm.providers} and in canonical VM ids. */
    public static final String PROVIDER_ID = "utm";

    private static final Logger log = LoggerFactory.getLogger(UtmVmProvider.class);

    /**
     * Stable identity of UTM: the native UUID in {@code config.plist}. Packages and
     * containers without a readable UUID carry no identity.
     */
    public static final StableIdentityRule STABLE_IDENTITY = new NativeUuidRule();

    /** Groups: 1 = UUID, 2 = state, 3 = display name (not used for identity). */
    private static final Pattern LIST_ROW =
            Pattern.compile("^([0-9A-Fa-f-]{36})\\s++(\\S++)\\s++(.+)$");

    private final Path vmDirectory;
    private final Path executable;
    private final String packageSuffix;

    public UtmVmProvider(UtmProperties utm) {
        // Only created when UTM is enabled, so missing settings are a configuration error.
        if (utm.directory() == null || utm.cli() == null) {
            throw new IllegalStateException(
                    "UTM is enabled in vm.providers, but vm.utm.directory and "
                            + "vm.utm.cli must both be configured"
            );
        }

        this.vmDirectory = utm.directory();
        this.executable = utm.cli();
        this.packageSuffix = utm.packageSuffix();
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "UTM";
    }

    /** Identity decision of UTM. See {@link #STABLE_IDENTITY}. */
    @Override
    public StableIdentityRule stableIdentity() {
        return STABLE_IDENTITY;
    }

    @Override
    public List<LocalVm> findAll() {
        if (!Files.isDirectory(vmDirectory)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.list(vmDirectory)) {
            return paths
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName()
                            .toString()
                            .endsWith(packageSuffix))
                    .map(path -> new LocalVm(
                            localId(path),
                            displayName(path),
                            List.of(path)
                    ))
                    .sorted(Comparator.comparing(
                            LocalVm::name,
                            String.CASE_INSENSITIVE_ORDER
                    ))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Local UTM VMs could not be read: " + vmDirectory,
                    e
            );
        }
    }

    @Override
    public void stop(LocalVm vm) {
        throw new UnsupportedOperationException(
                "Stopping UTM VMs is not implemented yet"
        );
    }

    @Override
    public List<ConfigurationEntry> configuration() {
        return List.of(
                new ConfigurationEntry(
                        "localVmDirectory",
                        "config.vmDirectory.label",
                        vmDirectory.toString()
                ),
                new ConfigurationEntry(
                        "packageSuffix",
                        "config.packageSuffix",
                        packageSuffix
                ),
                new ConfigurationEntry(
                        "providerCli",
                        "config.providerCli",
                        executable.toString()
                )
        );
    }


    /**
     * Returns the runtime state of each local VM, keyed by its local id (the native
     * UUID). utmctl rows are matched by UUID, never by display name. A VM is reported
     * UNKNOWN when several utmctl rows carry its UUID. A VM without any utmctl row is
     * omitted, which also counts as UNKNOWN.
     */
    @Override
    public Map<String, VmStatus> findStatuses() {
        List<LocalVm> localVms;

        try {
            localVms = findAll();
        } catch (IllegalStateException e) {
            return Map.of();
        }

        return resolveStatuses(localVms, readUtmctlEntries());
    }

    /**
     * Resolves utmctl entries against local VMs by UUID. A UUID reported by several
     * utmctl rows, or shared by several local packages, is ambiguous and yields UNKNOWN.
     */
    static Map<String, VmStatus> resolveStatuses(
            List<LocalVm> localVms,
            List<UtmctlEntry> entries
    ) {
        Map<String, Long> localCountById = localVms.stream()
                .collect(Collectors.groupingBy(LocalVm::id, Collectors.counting()));

        Map<String, List<VmStatus>> statusesByUuid = entries.stream()
                .collect(Collectors.groupingBy(
                        entry -> entry.uuid().toUpperCase(Locale.ROOT),
                        Collectors.mapping(
                                entry -> mapStatus(entry.state()),
                                Collectors.toList()
                        )
                ));

        Map<String, VmStatus> statuses = new HashMap<>();

        for (LocalVm vm : localVms) {
            List<VmStatus> candidates = statusesByUuid.getOrDefault(
                    vm.id().toUpperCase(Locale.ROOT),
                    List.of()
            );

            if (candidates.isEmpty()) {
                continue;
            }

            boolean ambiguous = localCountById.get(vm.id()) > 1
                    || candidates.size() > 1;

            statuses.put(
                    vm.id(),
                    ambiguous ? VmStatus.UNKNOWN : candidates.getFirst()
            );
        }

        return Collections.unmodifiableMap(statuses);
    }

    /**
     * Runs utmctl once and parses its rows. Any failure yields no entries, so
     * every VM falls back to UNKNOWN.
     */
    private List<UtmctlEntry> readUtmctlEntries() {
        Process process;

        try {
            process = new ProcessBuilder(executable.toString(), "list")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return List.of();
        }

        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }

            if (process.exitValue() != 0) {
                return List.of();
            }

            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );

            return output.lines()
                    .map(String::trim)
                    .map(LIST_ROW::matcher)
                    .filter(Matcher::matches)
                    .map(matcher -> new UtmctlEntry(
                            matcher.group(1),
                            matcher.group(2)
                    ))
                    .toList();
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** Reads the native UUID of UTM packages and of the packages inside containers. */
    private static final class NativeUuidRule implements StableIdentityRule {

        @Override
        public String providerId() {
            return PROVIDER_ID;
        }

        @Override
        public Optional<Identity> ofPackage(Path packageDirectory) {
            return UtmPackageIdentity.readPackageUuid(packageDirectory)
                    .map(id -> new Identity(id, IdentitySource.PROVIDER_NATIVE));
        }

        @Override
        public Optional<Identity> ofContainer(Path container) {
            return UtmPackageIdentity.readContainerUuid(container)
                    .map(id -> new Identity(id, IdentitySource.PROVIDER_NATIVE));
        }
    }

    /**
     * One row of {@code utmctl list}: the UUID and the raw state text.
     */
    record UtmctlEntry(String uuid, String state) {
    }

    /**
     * Maps UTM-specific runtime states to the generic backup safety states.
     */
    private static VmStatus mapStatus(String status) {
        if (status == null || status.isBlank()) {
            return VmStatus.UNKNOWN;
        }

        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "STOPPED" -> VmStatus.STOPPED;
            case "STARTED", "STARTING", "PAUSED", "STOPPING" -> VmStatus.RUNNING;
            default -> VmStatus.UNKNOWN;
        };
    }

    /**
     * The stable local id: the native UUID of the package. A package whose
     * {@code config.plist} cannot be read keeps its folder name, the 1.5.1 identity, and
     * a warning is logged. Identity is never guessed from anything else.
     */
    private static String localId(Path path) {
        String folderName = path.getFileName().toString();

        return UtmPackageIdentity.readPackageUuid(path).orElseGet(() -> {
            log.warn("No readable UUID in {}/config.plist; the VM keeps its folder name as id",
                    folderName);
            return folderName;
        });
    }

    /**
     * Derives the VM display name from its package directory name
     * by removing the configured package suffix.
     */
    private String displayName(Path path) {
        String name = path.getFileName().toString();

        return name.endsWith(packageSuffix)
                ? name.substring(0, name.length() - packageSuffix.length())
                : name;
    }


}
