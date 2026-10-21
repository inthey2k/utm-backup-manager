package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.storage.BackupFormat;
import de.fjordkommission.utmbackup.storage.BackupManifest;
import de.fjordkommission.utmbackup.storage.BackupManifestReader;
import de.fjordkommission.utmbackup.storage.BackupManifestResult;
import de.fjordkommission.utmbackup.storage.BackupManifestWriter;
import de.fjordkommission.utmbackup.repository.StableRepository;
import de.fjordkommission.utmbackup.storage.BackupStorageGuard;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

@Service
public class BackupService {

 private final BackupScanner scanner;
 private final StableRepository repo;
 private final BackupStorageGuard storageGuard;
 private final BackupManifestReader manifestReader = new BackupManifestReader();
 private final BackupManifestWriter manifestWriter = new BackupManifestWriter();

 public BackupService(BackupScanner scanner, StableRepository repo, BackupStorageGuard storageGuard) {
  this.scanner = scanner;
  this.repo = repo;
  this.storageGuard = storageGuard;
 }

 public void stable(String id, boolean value) {
  storageGuard.assertWritable();
  VmBackup backup = require(id);
  repo.saveStable(backup.id(), value);
 }

 public void note(String id, String note) {
  storageGuard.assertWritable();
  VmBackup backup = require(id);
  repo.saveNote(backup.id(), note);
 }

 public void delete(String id) {
  storageGuard.assertWritable();
  VmBackup backup = require(id);

  if (backup.stable()) {
   throw new IllegalStateException(
           "Stable backups cannot be deleted. Remove stable protection first."
   );
  }

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

  BackupManifestResult manifestResult = manifestReader.read(batchDirectory);

  if (manifestResult instanceof BackupManifestResult.Parsed(BackupManifest manifest)
          && manifest.formatVersion() == BackupFormat.PROVIDER_AWARE) {
   deleteFormatTwoVm(id, path, batchDirectory, manifest);
   return;
  }

  try {
   deleteRecursively(path);

   if (isDirectoryEmpty(vmDirectory)) {
    deleteRecursively(batchDirectory);
   }

   repo.delete(id);

  } catch (IOException e) {
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

   if (remaining.isEmpty()) {
    deleteRecursively(batchDirectory);
   }

   repo.delete(id);

  } catch (IOException e) {
   throw new IllegalStateException(
           "Backup could not be deleted: " + container,
           e
   );
  }
 }

 private boolean isDirectoryEmpty(Path directory) throws IOException {
  try (var entries = Files.list(directory)) {
   return entries.findAny().isEmpty();
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

 private VmBackup require(String id) {
  return scanner.find(id)
          .orElseThrow(() ->
                  new IllegalArgumentException(
                          "Backup not found."
                  )
          );
 }
}