package de.fjordkommission.utmbackup.provider.utm;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.ConditionalOnVmProvider;
import de.fjordkommission.utmbackup.provider.VmProvider;

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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Integrates UTM with the backup manager.
 * UTM stores each VM as a .utm package directory. The complete package is
 * therefore exposed as the backup source for that VM.
 */
@Component
@Profile("!test")
@ConditionalOnVmProvider("utm")
public class UtmVmProvider implements VmProvider {

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
        return "utm";
    }

    @Override
    public String displayName() {
        return "UTM";
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
                            path.getFileName().toString(),
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
     * Returns the runtime state of each local VM, keyed by its package folder
     * name (the local id). utmctl reports display names, which are resolved
     * against the discovered packages here and never leave this class.
     * A VM is reported UNKNOWN when its display name matches several local
     * packages or several utmctl rows. A VM without any utmctl row is omitted,
     * which also counts as UNKNOWN.
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
     * Resolves utmctl entries against local VMs by display name.
     */
    static Map<String, VmStatus> resolveStatuses(
            List<LocalVm> localVms,
            List<UtmctlEntry> entries
    ) {
        Map<String, Long> localCountByName = localVms.stream()
                .collect(Collectors.groupingBy(LocalVm::name, Collectors.counting()));

        Map<String, List<VmStatus>> statusesByName = entries.stream()
                .collect(Collectors.groupingBy(
                        UtmctlEntry::name,
                        Collectors.mapping(
                                entry -> mapStatus(entry.state()),
                                Collectors.toList()
                        )
                ));

        Map<String, VmStatus> statuses = new HashMap<>();

        for (LocalVm vm : localVms) {
            List<VmStatus> candidates = statusesByName.getOrDefault(vm.name(), List.of());

            if (candidates.isEmpty()) {
                continue;
            }

            boolean ambiguous = localCountByName.get(vm.name()) > 1
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
                            matcher.group(3).trim(),
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

    /**
     * One row of {@code utmctl list}: the display name and the raw state text.
     */
    record UtmctlEntry(String name, String state) {
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
