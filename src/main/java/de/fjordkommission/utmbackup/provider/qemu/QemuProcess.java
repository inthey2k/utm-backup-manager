package de.fjordkommission.utmbackup.provider.qemu;

import java.util.List;

/**
 * A process as the operating system reports it to this user.
 * A null field means the operating system did not expose that information.
 * This is common for processes that belong to another user, for example a QEMU
 * process started with sudo for macOS vmnet bridging. Such processes are never
 * treated as stopped.
 *
 * @param command   full path of the executable, or null when unavailable
 * @param arguments full argument vector including the executable, or null when unavailable
 */
record QemuProcess(String command, List<String> arguments) {

    /**
     * Lists the processes visible to this JVM. Uses the ProcessHandle API, so no
     * external command is started and no elevated privileges are needed.
     */
    static List<QemuProcess> listAll() {
        return ProcessHandle.allProcesses()
                .map(handle -> {
                    var info = handle.info();

                    return new QemuProcess(
                            info.command().orElse(null),
                            info.arguments().map(List::of).orElse(null)
                    );
                })
                .toList();
    }
}
