package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.repository.StableRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

@Service
public class BackupService {

 private final BackupScanner scanner;
 private final StableRepository repo;

 public BackupService(BackupScanner scanner, StableRepository repo) {
  this.scanner = scanner;
  this.repo = repo;
 }

 public void stable(String id, boolean value) {
  VmBackup backup = require(id);
  repo.saveStable(backup.id(), value);
 }

 public void note(String id, String note) {
  VmBackup backup = require(id);
  repo.saveNote(backup.id(), note);
 }

 public void delete(String id) {
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