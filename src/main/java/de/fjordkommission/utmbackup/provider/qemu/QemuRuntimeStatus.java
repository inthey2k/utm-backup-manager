package de.fjordkommission.utmbackup.provider.qemu;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Derives the runtime state of QEMU VMs from the QEMU processes that are running.
 * A VM is RUNNING when any visible process references its directory in its
 * arguments. This includes launchers such as sudo, which would otherwise hide a
 * running VM. A false RUNNING only blocks a backup, so it is the safer error.
 * Matching is by path, never by name, so {@code Arch} does not match {@code ArchLinux}.
 * Without a matching process the VM is STOPPED, unless a {@code qemu-system-*}
 * process could not be inspected or refers to a disk by a relative path. In those
 * cases the VM is UNKNOWN, because that process might belong to it.
 */
final class QemuRuntimeStatus {

    private static final String QEMU_SYSTEM_PREFIX = "qemu-system";
    private static final List<String> DISK_SUFFIXES = List.of(".qcow2", ".raw");
    private static final Pattern URI_PATH = Pattern.compile("^[a-z]+:(/.*)$");

    private QemuRuntimeStatus() {
    }

    /**
     * Returns the state of each VM, keyed by its local id. The VM directory is the
     * first backup source of each VM.
     */
    static Map<String, VmStatus> resolve(List<LocalVm> vms, List<QemuProcess> processes) {
        List<QemuProcess> qemuProcesses = processes.stream()
                .filter(QemuRuntimeStatus::isQemuSystem)
                .toList();

        // A process whose arguments are unreadable may belong to any VM.
        boolean uninspectable = qemuProcesses.stream()
                .anyMatch(process -> process.arguments() == null);

        // A relative disk path depends on the working directory, which is not visible here.
        boolean relativeDiskReference = qemuProcesses.stream()
                .filter(process -> process.arguments() != null)
                .anyMatch(process -> hasRelativeDiskReference(process.arguments()));

        // Computed once: resolving paths touches the file system.
        Set<String> referencedPaths = processes.stream()
                .flatMap(QemuRuntimeStatus::referencedPaths)
                .collect(Collectors.toSet());

        Map<String, VmStatus> statuses = new HashMap<>();

        for (LocalVm vm : vms) {
            List<String> directories = directoriesOf(vm);

            boolean running = referencedPaths.stream()
                    .anyMatch(path -> isWithinAny(path, directories));

            if (running) {
                statuses.put(vm.id(), VmStatus.RUNNING);
            } else if (uninspectable || relativeDiskReference) {
                statuses.put(vm.id(), VmStatus.UNKNOWN);
            } else {
                statuses.put(vm.id(), VmStatus.STOPPED);
            }
        }

        return Map.copyOf(statuses);
    }

    /**
     * Returns the VM directory as configured and as resolved through symbolic links,
     * because QEMU may be started with either path.
     */
    private static List<String> directoriesOf(LocalVm vm) {
        Path directory = vm.backupSources().getFirst().toAbsolutePath().normalize();
        Set<String> forms = new LinkedHashSet<>();
        forms.add(directory.toString());

        try {
            forms.add(directory.toRealPath().toString());
        } catch (IOException e) {
            // Without a real path the configured path is the only form that can match.
        }

        return List.copyOf(forms);
    }

    /**
     * Matches the executable name, which is taken from the command, or from the
     * first argument if the command is unavailable.
     */
    private static boolean isQemuSystem(QemuProcess process) {
        String executable = process.command();

        if (executable == null && process.arguments() != null && !process.arguments().isEmpty()) {
            executable = process.arguments().getFirst();
        }

        if (executable == null) {
            return false;
        }

        String name = executable.substring(executable.lastIndexOf('/') + 1);

        return name.startsWith(QEMU_SYSTEM_PREFIX);
    }

    /**
     * Returns the paths a process refers to, as written and as resolved through
     * symbolic links. QEMU and the VM root may spell the same directory differently,
     * for example through /var and /private/var on macOS.
     */
    private static Stream<String> referencedPaths(QemuProcess process) {
        if (process.arguments() == null) {
            return Stream.empty();
        }

        return process.arguments().stream()
                .flatMap(QemuRuntimeStatus::pathCandidates)
                .flatMap(path -> Stream.of(path, canonicalOf(path)));
    }

    private static boolean isWithinAny(String path, List<String> directories) {
        return directories.stream()
                .anyMatch(directory -> path.equals(directory) || path.startsWith(directory + "/"));
    }

    /**
     * Returns the real path of an absolute path that exists, or the path itself.
     * A path that cannot be resolved is kept as written, so it can still match.
     */
    private static String canonicalOf(String path) {
        if (!path.startsWith("/")) {
            return path;
        }

        try {
            return Path.of(path).toRealPath().toString();
        } catch (IOException | InvalidPathException e) {
            return path;
        }
    }

    private static boolean hasRelativeDiskReference(List<String> arguments) {
        return arguments.stream()
                .flatMap(QemuRuntimeStatus::pathCandidates)
                .anyMatch(candidate -> !candidate.startsWith("/") && hasDiskSuffix(candidate));
    }

    private static boolean hasDiskSuffix(String candidate) {
        String lower = candidate.toLowerCase(Locale.ROOT);

        return DISK_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    /**
     * Returns the values that can be a path in one QEMU argument. Options such as
     * {@code -drive file=/path,format=qcow2} carry their paths after {@code =} and
     * separate options with {@code ,}. A URI such as {@code file:/path} contributes
     * only its path, so its scheme is never mistaken for a relative file name.
     */
    private static Stream<String> pathCandidates(String argument) {
        return Arrays.stream(argument.split(",", -1))
                .flatMap(piece -> {
                    int equals = piece.indexOf('=');
                    String value = equals < 0 ? piece : piece.substring(equals + 1);
                    Matcher uri = URI_PATH.matcher(value);

                    return Stream.of(uri.matches() ? uri.group(1) : value);
                });
    }
}
