package de.fjordkommission.utmbackup.provider.qemu;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The matching rules that decide between RUNNING, STOPPED and UNKNOWN, tested
 * without touching the file system or the process table.
 */
class QemuRuntimeStatusTest {

    private static final String ARCH = "ArchLinux";
    private static final String FREEBSD = "FreeBSD";

    private static final String QEMU = "/opt/homebrew/bin/qemu-system-aarch64";
    private static final Path ARCH_DIR = Path.of("/Users/sascha/QEMU/VMs/ArchLinux");
    private static final Path FREEBSD_DIR = Path.of("/Users/sascha/QEMU/VMs/FreeBSD");

    private static final List<LocalVm> VMS = List.of(
            new LocalVm(ARCH, ARCH, List.of(ARCH_DIR)),
            new LocalVm(FREEBSD, FREEBSD, List.of(FREEBSD_DIR))
    );

    @Test
    void vmWithoutQemuProcessIsStopped() {
        assertEquals(
                Map.of(ARCH, VmStatus.STOPPED, FREEBSD, VmStatus.STOPPED),
                QemuRuntimeStatus.resolve(VMS, List.of(otherProcess()))
        );
    }

    @Test
    void runningVmIsIdentifiedByItsDiskInTheArguments() {
        QemuProcess running = qemu(
                "-drive", "if=none,id=hd0,file=/Users/sascha/QEMU/VMs/ArchLinux/arch-linux-disk.qcow2,format=qcow2"
        );

        assertEquals(
                Map.of(ARCH, VmStatus.RUNNING, FREEBSD, VmStatus.STOPPED),
                QemuRuntimeStatus.resolve(VMS, List.of(running))
        );
    }

    @Test
    void diskAsPlainArgumentAndUriFormsAreRecognised() {
        QemuProcess plain = qemu("-hda", "/Users/sascha/QEMU/VMs/ArchLinux/arch-linux-disk.qcow2");
        QemuProcess uri = qemu("-blockdev", "driver=file,filename=file:/Users/sascha/QEMU/VMs/FreeBSD/FreeBSD-15.1.qcow2");

        assertEquals(
                Map.of(ARCH, VmStatus.RUNNING, FREEBSD, VmStatus.STOPPED),
                QemuRuntimeStatus.resolve(VMS, List.of(plain))
        );
        assertEquals(
                Map.of(ARCH, VmStatus.STOPPED, FREEBSD, VmStatus.RUNNING),
                QemuRuntimeStatus.resolve(VMS, List.of(uri))
        );
    }

    @Test
    void similarlyNamedVmDoesNotMatchAShorterDirectoryName() {
        List<LocalVm> vms = List.of(
                new LocalVm("Arch", "Arch", List.of(Path.of("/Users/sascha/QEMU/VMs/Arch"))),
                new LocalVm(ARCH, ARCH, List.of(ARCH_DIR))
        );

        assertEquals(
                Map.of("Arch", VmStatus.STOPPED, ARCH, VmStatus.RUNNING),
                QemuRuntimeStatus.resolve(vms, List.of(qemu("-hda", ARCH_DIR + "/disk.qcow2")))
        );
    }

    @Test
    void qemuProcessWhoseArgumentsAreUnreadableMakesOtherVmsUnknown() {
        QemuProcess unreadable = new QemuProcess(QEMU, null);

        assertEquals(
                Map.of(ARCH, VmStatus.UNKNOWN, FREEBSD, VmStatus.UNKNOWN),
                QemuRuntimeStatus.resolve(VMS, List.of(unreadable))
        );
    }

    @Test
    void unreadableQemuProcessDoesNotHideAVmThatIsReportedRunning() {
        QemuProcess running = qemu("-hda", ARCH_DIR + "/disk.qcow2");
        QemuProcess unreadable = new QemuProcess(QEMU, null);

        assertEquals(
                Map.of(ARCH, VmStatus.RUNNING, FREEBSD, VmStatus.UNKNOWN),
                QemuRuntimeStatus.resolve(VMS, List.of(running, unreadable))
        );
    }

    @Test
    void unreadableProcessThatIsNotQemuIsIgnored() {
        QemuProcess launchd = new QemuProcess("/sbin/launchd", null);

        assertEquals(
                Map.of(ARCH, VmStatus.STOPPED, FREEBSD, VmStatus.STOPPED),
                QemuRuntimeStatus.resolve(VMS, List.of(launchd))
        );
    }

    @Test
    void launcherReferencingTheVmDirectoryMarksItRunning() {
        QemuProcess sudo = new QemuProcess("/usr/bin/sudo", List.of(
                "sudo", "/opt/homebrew/bin/qemu-system-aarch64", "-hda", ARCH_DIR + "/arch-linux-disk.qcow2"
        ));

        assertEquals(
                Map.of(ARCH, VmStatus.RUNNING, FREEBSD, VmStatus.STOPPED),
                QemuRuntimeStatus.resolve(VMS, List.of(sudo))
        );
    }

    @Test
    void qemuImgOnTheDiskIsConservativelyRunning() {
        QemuProcess qemuImg = new QemuProcess(
                "/opt/homebrew/bin/qemu-img",
                List.of("qemu-img", "info", ARCH_DIR + "/arch-linux-disk.qcow2")
        );

        assertEquals(
                Map.of(ARCH, VmStatus.RUNNING, FREEBSD, VmStatus.STOPPED),
                QemuRuntimeStatus.resolve(VMS, List.of(qemuImg))
        );
    }

    @Test
    void relativeDiskReferenceOfNonQemuProcessDoesNotMakeVmsUnknown() {
        QemuProcess qemuImg = new QemuProcess(
                "/opt/homebrew/bin/qemu-img",
                List.of("qemu-img", "info", "arch-linux-disk.qcow2")
        );

        assertEquals(
                Map.of(ARCH, VmStatus.STOPPED, FREEBSD, VmStatus.STOPPED),
                QemuRuntimeStatus.resolve(VMS, List.of(qemuImg))
        );
    }

    @Test
    void executableIsReadFromFirstArgumentWhenCommandIsUnavailable() {
        QemuProcess withoutCommand = new QemuProcess(null, List.of(
                QEMU, "-hda", ARCH_DIR + "/disk.qcow2"
        ));

        assertEquals(
                Map.of(ARCH, VmStatus.RUNNING, FREEBSD, VmStatus.STOPPED),
                QemuRuntimeStatus.resolve(VMS, List.of(withoutCommand))
        );
    }

    @Test
    void relativeDiskReferenceMakesVmsWithoutAMatchUnknown() {
        QemuProcess relative = qemu("-drive", "file=arch-linux-disk.qcow2,format=qcow2");

        assertEquals(
                Map.of(ARCH, VmStatus.UNKNOWN, FREEBSD, VmStatus.UNKNOWN),
                QemuRuntimeStatus.resolve(VMS, List.of(relative))
        );
    }

    @Test
    void statusMapIsKeyedByLocalId() {
        Map<String, VmStatus> statuses = QemuRuntimeStatus.resolve(VMS, List.of());

        assertEquals(Map.of(ARCH, VmStatus.STOPPED, FREEBSD, VmStatus.STOPPED), statuses);
    }

    private static QemuProcess qemu(String... arguments) {
        List<String> argv = new ArrayList<>();
        argv.add(QEMU);
        argv.addAll(List.of(arguments));

        return new QemuProcess(QEMU, argv);
    }

    private static QemuProcess otherProcess() {
        return new QemuProcess("/usr/bin/ssh", List.of("ssh", "host"));
    }
}
