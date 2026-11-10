package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.AbstractIntegrationTest;
import de.fjordkommission.utmbackup.model.RetentionCleanupJob;
import de.fjordkommission.utmbackup.model.RetentionCleanupJobStatus;
import de.fjordkommission.utmbackup.service.BackupService;
import de.fjordkommission.utmbackup.service.RetentionCleanupJobService;
import de.fjordkommission.utmbackup.service.RetentionService;
import de.fjordkommission.utmbackup.storage.BackupStorageCompatibility;
import de.fjordkommission.utmbackup.storage.BackupStorageReadOnlyException;
import de.fjordkommission.utmbackup.storage.BackupStorageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP behaviour of the manual cleanup endpoints: redirects, banners, refusals and the
 * rules the services enforce. The services are stubbed here; their rules are tested with
 * real files in {@code ManualBackupDeletionTest} and {@code RetentionServiceTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackupManualCleanupControllerTest extends AbstractIntegrationTest {

    private static final String CLEANUP_URL = "/retention/delete-candidates";
    private static final String SELECTION_URL = "/backups/delete-selected";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackupService backupService;

    @MockitoBean
    private RetentionService retentionService;

    @MockitoBean
    private RetentionCleanupJobService retentionCleanupJobService;

    // ------------------------------------------------------------------
    // Manual retention cleanup
    // ------------------------------------------------------------------

    @Test
    void retentionCleanupPostDeletesTheCandidatesAndShowsSuccess() throws Exception {
        when(retentionService.deleteCurrentDeletionCandidates()).thenReturn(2);

        mockMvc.perform(post(CLEANUP_URL))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("message"));

        verify(retentionService).deleteCurrentDeletionCandidates();
    }

    @Test
    void retentionCleanupWithoutCandidatesShowsAnInformationBanner() throws Exception {
        when(retentionService.deleteCurrentDeletionCandidates()).thenReturn(0);

        mockMvc.perform(post(CLEANUP_URL))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("info"))
                .andExpect(flash().attributeCount(1));
    }

    @Test
    void retentionCleanupFailureIsShownAsAnError() throws Exception {
        when(retentionService.deleteCurrentDeletionCandidates())
                .thenThrow(new IllegalStateException("Backup could not be deleted: /backups/x"));

        mockMvc.perform(post(CLEANUP_URL))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("error", "Backup could not be deleted: /backups/x"));
    }

    @Test
    void retentionCleanupOnReadOnlyStorageIsRefused() throws Exception {
        when(retentionService.deleteCurrentDeletionCandidates())
                .thenThrow(readOnlyStorage());

        mockMvc.perform(post(CLEANUP_URL))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("error"))
                .andExpect(flash().attributeCount(1));
    }

    @Test
    void retentionCleanupIsRefusedWhileAutomaticCleanupRuns() throws Exception {
        runningRetentionCleanup();

        mockMvc.perform(post(CLEANUP_URL))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("error"));

        verify(retentionService, never()).deleteCurrentDeletionCandidates();
    }

    @Test
    void retentionCleanupCannotBeTriggeredByGet() throws Exception {
        mockMvc.perform(get(CLEANUP_URL))
                .andExpect(status().isMethodNotAllowed());

        verify(retentionService, never()).deleteCurrentDeletionCandidates();
    }

    // ------------------------------------------------------------------
    // Manual multi-delete
    // ------------------------------------------------------------------

    @Test
    void selectedBackupsAreDeletedAsOneRequest() throws Exception {
        when(backupService.deleteAll(anyCollection())).thenReturn(2);

        mockMvc.perform(post(SELECTION_URL)
                        .param("backupIds", "backup-a", "backup-b"))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("message"));

        verify(backupService).deleteAll(List.of("backup-a", "backup-b"));
    }

    @Test
    void emptySelectionShowsAnErrorAndDeletesNothing() throws Exception {
        mockMvc.perform(post(SELECTION_URL))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("error"));

        verify(backupService, never()).deleteAll(any());
    }

    @Test
    void stableBackupInTheSelectionIsRejectedWithItsReason() throws Exception {
        doThrow(new IllegalStateException(
                "Stable backups cannot be deleted. Remove stable protection first. Nothing was deleted."))
                .when(backupService).deleteAll(anyCollection());

        mockMvc.perform(post(SELECTION_URL).param("backupIds", "stable-backup"))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("error"))
                .andExpect(flash().attributeCount(1));
    }

    @Test
    void unknownBackupInTheSelectionIsRejected() throws Exception {
        doThrow(new IllegalArgumentException("A selected backup does not exist. Nothing was deleted."))
                .when(backupService).deleteAll(anyCollection());

        mockMvc.perform(post(SELECTION_URL).param("backupIds", "missing"))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void selectionOnReadOnlyStorageIsRefused() throws Exception {
        doThrow(readOnlyStorage()).when(backupService).deleteAll(anyCollection());

        mockMvc.perform(post(SELECTION_URL).param("backupIds", "backup-a"))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void selectionIsRefusedWhileAutomaticCleanupRuns() throws Exception {
        runningRetentionCleanup();

        mockMvc.perform(post(SELECTION_URL).param("backupIds", "backup-a"))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("error"));

        verify(backupService, never()).deleteAll(any());
    }

    @Test
    void selectedBackupsCannotBeDeletedByGet() throws Exception {
        mockMvc.perform(get(SELECTION_URL).param("backupIds", "backup-a"))
                .andExpect(status().isMethodNotAllowed());

        verify(backupService, never()).deleteAll(any());
    }

    private void runningRetentionCleanup() {
        when(retentionCleanupJobService.currentJob()).thenReturn(Optional.of(
                new RetentionCleanupJob(RetentionCleanupJobStatus.RUNNING, Instant.now(), null, null)
        ));
    }

    private static BackupStorageReadOnlyException readOnlyStorage() {
        return new BackupStorageReadOnlyException(new BackupStorageStatus(
                BackupStorageCompatibility.READ_ONLY_NEWER_FORMAT,
                "Read-only, newer format"
        ));
    }
}
