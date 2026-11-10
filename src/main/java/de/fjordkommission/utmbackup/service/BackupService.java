package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.storage.BackupFormat;
import de.fjordkommission.utmbackup.storage.BackupManifest;
import de.fjordkommission.utmbackup.storage.BackupManifestReader;
import de.fjordkommission.utmbackup.storage.BackupManifestResult;
import de.fjordkommission.utmbackup.storage.BackupManifestWriter;
import de.fjordkommission.utmbackup.repository.StableRepository;
import de.fjordkommission.utmbackup.storage.BackupState;
import de.fjordkommission.utmbackup.storage.BackupStateReader;
import de.fjordkommission.utmbackup.storage.BackupStateResult;
import de.fjordkommission.utmbackup.storage.BackupStateWriter;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

@Service
public class BackupService {

 private static final Logger log = LoggerFactory.getLogger(BackupService.class);

 private final BackupScanner scanner;
 private final StableRepository repo;
 private final BackupStorageGuard storageGuard;
 private final BackupStateWriter stateWriter;
 private final BackupManifestReader manifestReader = new BackupManifestReader();
 private final BackupManifestWriter manifestWriter = new BackupManifestWriter();
 private final BackupStateReader stateReader = new BackupStateReader();

 public BackupService(
         BackupScanner scanner,
         StableRepository repo,
         BackupStorageGuard storageGuard,
         BackupStateWriter stateWriter
 ) {
  this.scanner = scanner;
  this.repo = repo;
  this.storageGuard = storageGuard;
  this.stateWriter = stateWriter;
 }

 /**
  * Changes Stable. Format-2 state is stored in the backup and SQLite is only a cache,
  * so the write goes to the backup first; see {@link #updateFormatTwoState}.
  */
 public void stable(String id, boolean value) {
  storageGuard.assertWritable();
  VmBackup backup = require(id);

  if (isFormatTwo(backup)) {
   updateFormatTwoState(backup, state -> new BackupState(value, state.note()));
   return;
  }

  repo.saveStable(backup.id(), value);
 }

 /** Changes the mutable backup note. The same rules as {@link #stable} apply. */
 public void note(String id, String note) {
  storageGuard.assertWritable();
  VmBackup backup = require(id);

  if (isFormatTwo(backup)) {
   updateFormatTwoState(backup, state -> new BackupState(state.stable(), note));
   return;
  }

  repo.saveNote(backup.id(), note);
 }

 public void delete(String id) {
  storageGuard.assertWritable();
  deleteBackup(require(id));
 }

 /**
  * Deletes several backups of one request and returns how many were removed. Every id is
  * checked before the first removal: an unknown id, a Stable backup or a backup outside the
  * expected directory layout rejects the whole request, so a faulty selection removes
  * nothing. Duplicate ids are removed once. Each removal is the one used by {@link #delete}.
  *
  * <p>Only a failure during a removal can stop the request part way. The error then names
  * the backup concerned, and the removals before it stay in effect.
  */
 public int deleteAll(Collection<String> ids) {
  storageGuard.assertWritable();

  if (ids == null || ids.isEmpty()) {
   throw new IllegalArgumentException("No backups were selected.");
  }

  Map<String, VmBackup> known = new HashMap<>();
  scanner.scan().vms().forEach(vm -> vm.backups().forEach(backup -> known.put(backup.id(), backup)));

  List<VmBackup> targets = new ArrayList<>();

  for (String id : new LinkedHashSet<>(ids)) {
   VmBackup backup = known.get(id);

   if (backup == null) {
    throw new IllegalArgumentException(
            "A selected backup does not exist. Nothing was deleted."
    );
   }

   if (backup.stable()) {
    throw new IllegalStateException(
            "Stable backups cannot be deleted. Remove stable protection first. Nothing was deleted."
    );
   }

   requireSafeLocation(backup);
   targets.add(backup);
  }

  log.info("Deleting {} selected backup(s)", targets.size());
  targets.forEach(this::deleteBackup);
  return targets.size();
 }

 /** Checks that the backup lies in the expected layout below the root. Returns its batch directory. */
 private Path requireSafeLocation(VmBackup backup) {
  Path path = backup.vmPath().normalize();
  Path root = scanner.root().normalize();

  if (!path.startsWith(root)) {
   throw new IllegalStateException(
           "Unsafe backup path. Deletion aborted."
   );
  }

  Path vmDirectory = path.getParent();
  Path batchDirectory = vmDirectory != null
          ? vmDirectory.getParent()
          : null;

  if (vmDirectory == null
          || batchDirectory == null
          || !vmDirectory.getFileName().toString().equals("VMs")
          || !batchDirectory.startsWith(root)) {
   throw new IllegalStateException(
           "Invalid backup directory structure. Deletion aborted."
   );
  }

  return batchDirectory;
 }

 /** Removes one backup after the caller has checked the write guard. */
 private void deleteBackup(VmBackup backup) {
  String id = backup.id();
  Path path = backup.vmPath().normalize();
  Path vmDirectory = path.getParent();
  Path batchDirectory = requireSafeLocation(backup);

  log.info("Deleting backup {} at {}", id, path);

  BackupManifestResult manifestResult = manifestReader.read(batchDirectory);

  if (manifestResult instanceof BackupManifestResult.Parsed(BackupManifest manifest)
          && manifest.formatVersion() == BackupFormat.PROVIDER_AWARE) {
   deleteFormatTwoVm(id, path, batchDirectory, manifest);
   return;
  }

  // Format 1: Stable is the SQLite flag, as before.
  if (backup.stable()) {
   throw new IllegalStateException(
           "Stable backups cannot be deleted. Remove stable protection first."
   );
  }

  try {
   deleteRecursively(path);
   // The VM is gone, so its metadata goes too, even if the batch cleanup below fails.
   repo.delete(id);
   log.info("Deleted VM backup {} at {}", id, path);

   // Other VM folders keep the batch. Only the batch is removed when none is left.
   // Stray files next to them, such as .DS_Store, do not count as VM backups and do not
   // keep the batch; Shared Folder content is removed with the batch.
   if (!hasVmDirectories(vmDirectory)) {
    deleteRecursively(batchDirectory);
    log.info("Removed batch {} after its last VM backup was deleted", batchDirectory);
   }

  } catch (IOException e) {
   log.error("Deletion of backup {} at {} failed", id, path, e);
   throw new IllegalStateException(
           "Backup could not be deleted: " + path,
           e
   );
  }
 }

 /**
  * Removes one VM of a format-2 batch. The manifest is rewritten first, without the
  * entry, so a crash can leave orphaned data but never a manifest entry for deleted
  * data. Then only this VM's container is removed. The batch is removed when no VM
  * is left.
  */
 private void deleteFormatTwoVm(String id, Path container, Path batchDirectory, BackupManifest manifest) {
  // Read again here, not from the scan, so the decision uses the state on the medium.
  if (readFormatTwoState(container).stable()) {
   throw new IllegalStateException(
           "Stable backups cannot be deleted. Remove stable protection first."
   );
  }

  String directory = container.getFileName().toString();

  List<BackupManifest.VmEntry> remaining = manifest.vms().stream()
          .filter(entry -> !entry.directory().equals(directory))
          .toList();

  if (remaining.size() == manifest.vms().size()) {
   throw new IllegalStateException(
           "The batch manifest does not list this VM. Deletion aborted."
   );
  }

  try {
   manifestWriter.writeFile(batchDirectory, new BackupManifest(
           BackupFormat.PROVIDER_AWARE,
           manifest.backupTime(),
           manifest.comment(),
           remaining
   ));

   deleteRecursively(container);
   repo.delete(id);
   log.info("Deleted format-2 VM backup {} at {}", id, container);

   if (remaining.isEmpty()) {
    deleteRecursively(batchDirectory);
    log.info("Removed batch {} after its last VM backup was deleted", batchDirectory);
   }

  } catch (IOException e) {
   log.error("Deletion of backup {} at {} failed", id, container, e);
   throw new IllegalStateException(
           "Backup could not be deleted: " + container,
           e
   );
  }
 }

 /** True when a VM backup folder remains below {@code VMs}, as the scanner counts them. */
 private boolean hasVmDirectories(Path vmDirectory) throws IOException {
  try (var entries = Files.list(vmDirectory)) {
   return entries.anyMatch(Files::isDirectory);
  }
 }

 private void deleteRecursively(Path path) throws IOException {
  Files.walkFileTree(path, new SimpleFileVisitor<>() {

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
           Path directory,
           IOException exception
   ) throws IOException {
    if (exception != null) {
     throw exception;
    }

    Files.delete(directory);
    return FileVisitResult.CONTINUE;
   }
  });
 }

 /**
  * Writes the new state to the backup first, then mirrors it to SQLite. A failed backup
  * write leaves SQLite unchanged. If only the mirror fails, the change is already in
  * effect on the medium, and the error says so.
  */
 private void updateFormatTwoState(VmBackup backup, UnaryOperator<BackupState> change) {
  Path container = backup.vmPath();
  BackupState updated = change.apply(readFormatTwoState(container));

  stateWriter.writeFile(container, updated);

  try {
   repo.save(backup.id(), updated.stable(), updated.note());
  } catch (IllegalStateException e) {
   throw new IllegalStateException(
           "The change was saved to the backup, but the local metadata cache could not be updated. "
                   + "The backup is authoritative, so the change is in effect.",
           e
   );
  }
 }

 /**
  * The state a format-2 change starts from. An absent file means the defaults. An
  * unreadable file stops the change, because it must not be replaced on a guess.
  */
 private BackupState readFormatTwoState(Path container) {
  return switch (stateReader.read(container)) {
   case BackupStateResult.Absent() -> BackupState.DEFAULT;
   case BackupStateResult.Parsed(BackupState state) -> state;
   case BackupStateResult.Invalid(String reason) -> throw new IllegalStateException(
           "The backup state cannot be read, so nothing was changed: " + reason);
  };
 }

 /** A backup is format 2 when its batch manifest declares the provider-aware format. */
 private boolean isFormatTwo(VmBackup backup) {
  Path vmDirectory = backup.vmPath().normalize().getParent();
  Path batchDirectory = vmDirectory != null ? vmDirectory.getParent() : null;

  return batchDirectory != null
          && manifestReader.read(batchDirectory) instanceof BackupManifestResult.Parsed(BackupManifest manifest)
          && manifest.formatVersion() == BackupFormat.PROVIDER_AWARE;
 }

 private VmBackup require(String id) {
  return scanner.find(id)
          .orElseThrow(() ->
                  new IllegalArgumentException(
                          "Backup not found."
                  )
          );
 }
}