package de.fjordkommission.utmbackup.provider.utm;

import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.VmProvider;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Integrates UTM with the backup manager.
 *
 * UTM stores each VM as a .utm package directory. The complete package is
 * therefore exposed as the backup source for that VM.
 */
@Component
@Profile("!test")
public class UtmVmProvider implements VmProvider {

    private static final Pattern LIST_ROW =
            Pattern.compile("^([0-9A-Fa-f-]{36})\\s+(\\S+)\\s+(.+)$");

    private final Path vmDirectory;
    private final Path executable;
    private final String packageSuffix;

    public UtmVmProvider( VmProperties vmProperties ) {
        VmProperties.Utm utm = vmProperties.utm();
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
     * Reads all UTM runtime states in a single utmctl invocation.
     */
    public Map<String, VmStatus> findStatuses() {
        Process process;

        try {
            process = new ProcessBuilder(executable.toString(), "list")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return Map.of();
        }

        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Map.of();
            }

            if (process.exitValue() != 0) {
                return Map.of();
            }

            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );

            return output.lines()
                    .map(String::trim)
                    .map(LIST_ROW::matcher)
                    .filter(Matcher::matches)
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            matcher -> matcher.group(3).trim(),
                            matcher -> mapStatus(matcher.group(2))
                    ));
        } catch (IOException e) {
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
    }

    /**
     * Maps UTM-specific runtime states to the generic backup safety states.
     */
    private VmStatus mapStatus(String status) {
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
