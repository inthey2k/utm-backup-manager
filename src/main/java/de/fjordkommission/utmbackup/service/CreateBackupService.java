package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.storage.BackupContainers;
import de.fjordkommission.utmbackup.storage.BackupFormat;
import de.fjordkommission.utmbackup.storage.BackupManifest;
import de.fjordkommission.utmbackup.storage.BackupManifestWriter;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Creates new VM backups in the configured backup directory.
 */
@Service
public class CreateBackupService {

    public static final String INCOMPLETE = ".incomplete-";
    private static final DateTimeFormatter DIRECTORY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");

    // Large enough to avoid excessive progress events while still updating
    // continuously during multi-gigabyte virtual disk copies.
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private static final ProgressListener NO_PROGRESS = progress -> {
    };
    private static final CancellationCheck NEVER_CANCELLED = () -> false;
    private final Path backupRoot;
    private final VmChangeStatusService vmChangeStatusService;
    private final BackupStorageGuard storageGuard;
    private final BackupManifestWriter manifestWriter = new BackupManifestWriter();
    private final String applicationVersion;

    public CreateBackupService(
            BackupProperties properties,
            VmChangeStatusService vmChangeStatusService,
            BackupStorageGuard storageGuard,
            BuildProperties buildProperties
    ) {
        this.backupRoot = properties.root();
        this.vmChangeStatusService = vmChangeStatusService;
        this.storageGuard = storageGuard;
        this.applicationVersion = buildProperties == null || buildProperties.getVersion() == null
                ? "unknown"
                : buildProperties.getVersion();
    }

    /** Uses a null value for the comment string */
    public Result create(LocalVm vm) {
        return create(List.of(vm), null);
    }

    /** Uses the constant value NO_PROGRESS for the ProgressListener **/
    public Result create(List<LocalVm> vms, String comment) {
        return create(vms, comment, NO_PROGRESS, NEVER_CANCELLED);
    }

    /**
     * Uses the constant value NEVER_CANCELLED as Cancellation Check
     */
    public Result create(
            List<LocalVm> vms,
            String comment,
            ProgressListener progressListener
    ) {
        return create(
                vms,
                comment,
                progressListener,
                NEVER_CANCELLED
        );
    }
    /**
     * Creates a backup and reports copy progress to the supplied listener.
     * The listener is deliberately independent of the web layer and job model,
     * so this service can still be used without the background-job mechanism.
     */
    public Result create(
            List<LocalVm> vms,
            String comment,
            ProgressListener progressListener,
            CancellationCheck cancellationCheck
    ) {
        storageGuard.assertWritable();
        validateVms(vms);
        validateBackupRoot();

        if (progressListener == null) {
            throw new IllegalArgumentException(
                    "Progress listener must not be null"
            );
        }

        if (cancellationCheck == null) {
            throw new IllegalArgumentException(
                    "Cancellation check must not be null"
            );
        }

        // Everything that can fail on the input is checked before the file system is touched.
        OffsetDateTime backupTime = OffsetDateTime.now(ZoneId.systemDefault());
        LocalDateTime localBackupTime = backupTime.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        String directoryName = findAvailableDirectoryName(localBackupTime);
        Map<VmId, String> containers = BackupContainers.assign(vms.stream()
                .map(vm -> LegacyBackupLayout.vmIdForFolder(vm.id()))
                .toList());
        BackupManifest manifest = buildManifest(vms, containers, comment, backupTime);

        Path backupDirectory = backupRoot.resolve(directoryName);
        Path incompleteDirectory = backupRoot.resolve(
                INCOMPLETE + directoryName
        );

        try {
            checkCancelled(cancellationCheck);

            Files.createDirectories(incompleteDirectory);

            Path vmDirectory = incompleteDirectory.resolve("VMs");
            Files.createDirectories(vmDirectory);

            long totalBytes = determineTotalBytes(vms);

            ProgressTracker progress = new ProgressTracker(
                    progressListener,
                    totalBytes,
                    vms.size()
            );

            for (int vmIndex = 0; vmIndex < vms.size(); vmIndex++) {
                checkCancelled(cancellationCheck);

                LocalVm vm = vms.get(vmIndex);
                progress.startVm(vm.name(), vmIndex + 1);

                // One container per VM: its sources are copied inside it, under their own names.
                Path container = vmDirectory.resolve(containers.get(LegacyBackupLayout.vmIdForFolder(vm.id())));
                Files.createDirectories(container);

                for (Path source : vm.backupSources()) {
                    copySource(
                            source,
                            container.resolve(source.getFileName()),
                            progress,
                            cancellationCheck
                    );
                }
            }

            checkCancelled(cancellationCheck);

            // The manifest is written only after all data it refers to is in place.
            manifestWriter.writeFile(incompleteDirectory, manifest);

            checkCancelled(cancellationCheck);

            // The marker is established only once the batch is complete, and before it is published.
            storageGuard.ensureFormatTwoMarker(applicationVersion);

            Files.move(incompleteDirectory, backupDirectory);

            for (LocalVm vm : vms) {
                vmChangeStatusService.acknowledgeBackup(vm.id(), vm);
            }

            return new Result(
                    directoryName,
                    backupDirectory,
                    localBackupTime
            );
        } catch (BackupCancelledException e) {
            throw e;
        } catch (IOException e) {

            throw new UncheckedIOException(
                    "Could not create backup",
                    e
            );
        } finally {
            cleanupIncompleteDirectories();
        }
    }

    private void checkCancelled(CancellationCheck cancellationCheck) {
        if (cancellationCheck.isCancellationRequested()) {
            throw new BackupCancelledException();
        }
    }

    private void validateVms(List<LocalVm> vms) {
        if (vms == null || vms.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one VM must be selected"
            );
        }

        Set<String> ids = new HashSet<>();

        for (LocalVm vm : vms) {
            if (vm == null) {
                throw new IllegalArgumentException(
                        "VM must not be null"
                );
            }

            if (!ids.add(vm.id())) {
                throw new IllegalArgumentException(
                        "VM selected more than once: " + vm.id()
                );
            }

            if (vm.backupSources().isEmpty()) {
                throw new IllegalArgumentException(
                        "VM has no backup sources: " + vm.id()
                );
            }

            // Sources share one container, so their file names must be unique within the VM.
            Set<Path> targets = new HashSet<>();

            for (Path source : vm.backupSources()) {
                Path fileName = source.getFileName();

                if (fileName == null || !targets.add(fileName)) {
                    throw new IllegalArgumentException(
                            "Duplicate backup target in VM " + vm.id() + ": " + fileName
                    );
                }
            }
        }
    }

    /**
     * Ensures that the configured backup storage is currently available.
     * The backup root must already exist. It must never be created implicitly,
     * because it may represent a temporarily unavailable external drive or
     * network mount.
     */
    private void validateBackupRoot() {
        if (!Files.isDirectory(backupRoot)) {
            throw new BackupRootUnavailableException(backupRoot);
        }
    }

    private long determineTotalBytes(List<LocalVm> vms) throws IOException {
        long totalBytes = 0;

        for (LocalVm vm : vms) {
            for (Path source : vm.backupSources()) {
                totalBytes = Math.addExact(totalBytes, sizeOf(source));
            }
        }

        return totalBytes;
    }

    private long sizeOf(Path source) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup source does not exist: " + source);
        }

        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            return Files.readAttributes(
                    source,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            ).size();
        }

        long[] total = {0};

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
            ) {
                total[0] = Math.addExact(total[0], attributes.size());
                return FileVisitResult.CONTINUE;
            }
        });

        return total[0];
    }

    private void copySource(
            Path source,
            Path target,
            ProgressTracker progress,
            CancellationCheck cancellationCheck
    ) throws IOException {
        checkCancelled(cancellationCheck);

        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Backup source does not exist: " + source
            );
        }

        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            copyDirectory(
                    source,
                    target,
                    progress,
                    cancellationCheck
            );
        } else {
            BasicFileAttributes attributes = Files.readAttributes(
                    source,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );

            copyFile(
                    source,
                    target,
                    attributes,
                    progress,
                    cancellationCheck
            );
        }
    }


    @SuppressWarnings("NullableProblems")
    private void copyDirectory(
            Path source,
            Path target,
            ProgressTracker progress,
            CancellationCheck cancellationCheck
    ) throws IOException {

        Files.walkFileTree(source, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes
            ) throws IOException {
                checkCancelled(cancellationCheck);

                Path relative = source.relativize(directory);
                Files.createDirectories(target.resolve(relative));

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
            ) throws IOException {
                checkCancelled(cancellationCheck);

                Path relative = source.relativize(file);

                copyFile(
                        file,
                        target.resolve(relative),
                        attributes,
                        progress,
                        cancellationCheck
                );

                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void copyFile(
            Path source,
            Path target,
            BasicFileAttributes attributes,
            ProgressTracker progress,
            CancellationCheck cancellationCheck
    ) throws IOException {

        checkCancelled(cancellationCheck);

        // Preserve symbolic links as links instead of following their target.
        if (attributes.isSymbolicLink()) {
            Files.copy(
                    source,
                    target,
                    LinkOption.NOFOLLOW_LINKS,
                    StandardCopyOption.COPY_ATTRIBUTES
            );

            progress.addCopiedBytes(attributes.size());
            return;
        }

        byte[] buffer = new byte[COPY_BUFFER_SIZE];

        try (InputStream input = Files.newInputStream(source);
             OutputStream output = Files.newOutputStream(target)) {

            int bytesRead;

            while ((bytesRead = input.read(buffer)) != -1) {
                checkCancelled(cancellationCheck);
                output.write(buffer, 0, bytesRead);
                progress.addCopiedBytes(bytesRead);
            }
        }

        checkCancelled(cancellationCheck);

        copyFileAttributes(source, target, attributes);
    }


    /**
     * Restores the basic timestamps and POSIX permissions after stream copying.
     * Unsupported POSIX attributes are simply absent on non-POSIX file systems.
     */
    private void copyFileAttributes(
            Path source,
            Path target,
            BasicFileAttributes attributes
    ) throws IOException {
        BasicFileAttributeView basicView = Files.getFileAttributeView(
                target,
                BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );

        if (basicView != null) {
            basicView.setTimes(
                    attributes.lastModifiedTime(),
                    attributes.lastAccessTime(),
                    attributes.creationTime()
            );
        }

        PosixFileAttributeView sourcePosix = Files.getFileAttributeView(
                source,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        PosixFileAttributeView targetPosix = Files.getFileAttributeView(
                target,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );

        if (sourcePosix != null && targetPosix != null) {
            PosixFileAttributes posixAttributes = sourcePosix.readAttributes();
            targetPosix.setPermissions(posixAttributes.permissions());
        }
    }

    /**
     * Builds the format-2 manifest. Its entries are checked by the manifest itself,
     * and the writer verifies that the text reads back unchanged, so an unsafe value
     * is refused before anything is written.
     */
    private BackupManifest buildManifest(
            List<LocalVm> vms,
            Map<VmId, String> containers,
            String comment,
            OffsetDateTime backupTime
    ) {
        List<BackupManifest.VmEntry> entries = new ArrayList<>();

        for (LocalVm vm : vms) {
            VmId id = LegacyBackupLayout.vmIdForFolder(vm.id());
            String name = vm.name() == null || vm.name().isBlank() ? vm.id() : vm.name();

            entries.add(new BackupManifest.VmEntry(id, name, containers.get(id)));
        }

        BackupManifest manifest = new BackupManifest(
                BackupFormat.PROVIDER_AWARE,
                Optional.of(backupTime),
                comment == null ? "" : comment,
                entries
        );

        // Fails before any file is created if the manifest would not read back unchanged.
        manifestWriter.write(manifest);

        return manifest;
    }

    private void cleanupIncompleteDirectories() {
        try (Stream<Path> entries = Files.list(backupRoot)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> path.getFileName()
                            .toString()
                            .startsWith(INCOMPLETE))
                    .forEach(this::deleteDirectory);
        } catch (IOException ignored) {
            // Cleanup must not affect the backup result.
        }
    }


    @SuppressWarnings("NullableProblems")
    private void cleanupIncompleteDirectory(Path directory) throws IOException {

        if (!Files.exists(directory)) {
            return;
        }

        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes
                ) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(
                        Path dir,
                        IOException exception
                ) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }

                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Preserve the original backup failure.
        }
    }

    @SuppressWarnings("NullableProblems")
    private void deleteDirectory(Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes
                ) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(
                        Path dir,
                        IOException exception
                ) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }

                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Try again after the next backup run.
        }
    }

    private String findAvailableDirectoryName(LocalDateTime backupTime) {
        String baseName = backupTime.format(DIRECTORY_TIMESTAMP);
        String candidate = baseName;
        int suffix = 2;

        while (Files.exists(backupRoot.resolve(candidate))
                || Files.exists(backupRoot.resolve(INCOMPLETE + candidate))) {
            candidate = baseName + "_" + suffix++;
        }

        return candidate;
    }

    @FunctionalInterface
    public interface CancellationCheck {
        boolean isCancellationRequested();
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(Progress progress);
    }

    public record Progress(
            long copiedBytes,
            long totalBytes,
            String currentVmName,
            int currentVmNumber,
            int vmCount
    ) {
    }

    private static final class ProgressTracker {

        private final ProgressListener listener;
        private final long totalBytes;
        private final int vmCount;

        private long copiedBytes;
        private String currentVmName;
        private int currentVmNumber;

        private ProgressTracker(
                ProgressListener listener,
                long totalBytes,
                int vmCount
        ) {
            this.listener = listener;
            this.totalBytes = totalBytes;
            this.vmCount = vmCount;
        }

        private void startVm(String vmName, int vmNumber) {
            currentVmName = vmName;
            currentVmNumber = vmNumber;
            publish();
        }

        private void addCopiedBytes(long bytes) {
            copiedBytes += bytes;
            publish();
        }

        private void publish() {
            listener.onProgress(new Progress(
                    copiedBytes,
                    totalBytes,
                    currentVmName,
                    currentVmNumber,
                    vmCount
            ));
        }
    }

    public record Result(
            String backupDirectoryName,
            Path backupDirectory,
            LocalDateTime backupTime
    ) {
    }
}
