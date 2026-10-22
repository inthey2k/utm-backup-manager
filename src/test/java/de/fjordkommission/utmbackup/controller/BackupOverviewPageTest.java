package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.AbstractIntegrationTest;
import de.fjordkommission.utmbackup.model.BackupBatchIssue;
import de.fjordkommission.utmbackup.model.BackupOverview;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.ProviderSnapshot;
import de.fjordkommission.utmbackup.model.VmBackup;
import de.fjordkommission.utmbackup.model.VmChangeStatus;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmOverview;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.service.BackupScanner;
import de.fjordkommission.utmbackup.storage.BackupStorageMetadataReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the overview with a stubbed scanner, so the page can show providers that the
 * test profile does not run: a non-UTM VM, a failing provider and batch issues.
 * The real storage guard reads the test backup root.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackupOverviewPageTest extends AbstractIntegrationTest {

    private static final String UTM = "utm";
    private static final String QEMU = "qemu";
    private static final String RELIC = "relic";
    private static final String UTM_NAME = "Linux";
    private static final String BATCH = "2026-10-20_1200";

    private static final VmId UTM_VM = new VmId(UTM, UTM_NAME + ".utm");
    private static final VmId QEMU_VM = new VmId(QEMU, "ArchLinux");
    private static final VmId QEMU_OLD_VM = new VmId(QEMU, "Retired");
    private static final VmId RELIC_VM = new VmId(RELIC, "Gone");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackupScanner scanner;

    @AfterEach
    void removeStorageMarker() throws IOException {
        Files.deleteIfExists(TEST_BACKUP_ROOT.resolve(BackupStorageMetadataReader.FILE_NAME));
    }

    @Test
    void showsProviderBadgesAndBlocksBackupOfNonUtmVms() throws Exception {
        VmOverview utm = localVm(UTM_VM, UTM_NAME, VmStatus.STOPPED);
        VmOverview qemu = localVm(QEMU_VM, "ArchLinux", VmStatus.STOPPED);
        VmOverview retired = backupOnlyVm(QEMU_OLD_VM);
        VmOverview relic = backupOnlyVm(RELIC_VM);

        stubOverview(
                List.of(utm, qemu, retired, relic),
                List.of(healthy(UTM, "UTM"), healthy(QEMU, "QEMU")),
                List.of()
        );

        String html = render();

        assertThat(html, containsString(">UTM</span>"));
        assertThat(html, containsString(">QEMU</span>"));
        assertThat(html, containsString(">RELIC</span>"));
        assertThat(html, containsString("Not supported yet"));
        // Backup-only VM of an enabled provider versus one whose provider is not enabled.
        assertThat(html, containsString("No local VM"));
        assertThat(html, containsString("Provider not enabled"));
        assertThat(html, containsString(">Active</span>"));
        // Canonical ids must not appear as visible text.
        assertThat(html, not(containsString(">qemu:")));
        assertThat(html, not(containsString(">utm:")));

        assertFalse(checkboxDisabled(html, UTM_VM.toString()));
        assertTrue(checkboxDisabled(html, QEMU_VM.toString()));
    }

    @Test
    void providerFailuresAreReportedWithoutBreakingThePage() throws Exception {
        stubOverview(
                List.of(localVm(UTM_VM, UTM_NAME, VmStatus.STOPPED)),
                List.of(
                        healthy(UTM, "UTM"),
                        new ProviderSnapshot(QEMU, "QEMU", false, List.of(), Map.of(),
                                List.of("Could not list VMs: qemu daemon is not running")),
                        new ProviderSnapshot(RELIC, "Relic", true, List.of(), Map.of(),
                                List.of("Could not determine VM states, so these VMs are shown as UNKNOWN: timeout"))
                ),
                List.of()
        );

        String html = render();

        assertThat(html, containsString("The VMs of this provider could not be listed."));
        assertThat(html, containsString("Could not list VMs: qemu daemon is not running"));
        assertThat(html, containsString("Live VM information is incomplete."));
        assertThat(html, containsString(">QEMU</span>"));
        // The header reports the configured UTM provider as active.
        assertThat(html, containsString(">Active</span>"));
        // The UTM VM is still shown and still backupable.
        assertThat(html, containsString(UTM_NAME));
        assertFalse(checkboxDisabled(html, UTM_VM.toString()));
    }

    @Test
    void readOnlyStorageIsShownAndBackupControlsAreDisabled() throws Exception {
        // Written by hand: the writer refuses formats this application cannot write.
        Files.createDirectories(TEST_BACKUP_ROOT);
        Files.writeString(
                TEST_BACKUP_ROOT.resolve(BackupStorageMetadataReader.FILE_NAME),
                "format.version=3\ncreated.at=2026-10-20T12:00:00+02:00\n"
        );

        stubOverview(
                List.of(localVm(UTM_VM, UTM_NAME, VmStatus.STOPPED, backup(UTM_VM))),
                List.of(healthy(UTM, "UTM")),
                List.of()
        );

        String html = render();

        assertThat(html, containsString("Read-only, newer format"));
        assertThat(html, containsString("Changes are blocked"));
        assertTrue(buttonDisabled(html, "id=\"create-backup-button\""));
        assertTrue(buttonDisabled(html, "icon-button note-button"));
        assertTrue(buttonDisabled(html, "icon-button delete delete-button"));
        assertTrue(buttonDisabled(html, "icon-button pin"));
        assertTrue(checkboxDisabled(html, UTM_VM.toString()));
    }

    @Test
    void batchIssuesAreShownAndMarkTheAffectedBackup() throws Exception {
        stubOverview(
                List.of(localVm(UTM_VM, UTM_NAME, VmStatus.STOPPED, backup(UTM_VM))),
                List.of(healthy(UTM, "UTM")),
                List.of(new BackupBatchIssue(BATCH, "VM utm:Linux.utm has an unreadable state, so it is kept as Stable"))
        );

        String html = render();

        assertThat(html, containsString("Backup batch issues"));
        assertThat(html, containsString("VM utm:Linux.utm has an unreadable state, so it is kept as Stable"));
        assertThat(html, containsString(">BATCH ISSUE</div>"));
    }

    private String render() throws Exception {
        return mockMvc.perform(get("/").param("lang", "en"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void stubOverview(List<VmOverview> vms, List<ProviderSnapshot> providers, List<BackupBatchIssue> issues)
            throws IOException {
        Files.createDirectories(TEST_BACKUP_ROOT);
        when(scanner.scan()).thenReturn(new BackupOverview(
                vms,
                Set.of(),
                Set.of(),
                3,
                providers,
                issues
        ));
    }

    private static ProviderSnapshot healthy(String providerId, String providerName) {
        return new ProviderSnapshot(providerId, providerName, true, List.of(), Map.of(), List.of());
    }

    private static VmOverview localVm(VmId id, String name, VmStatus status, VmBackup... backups) {
        LocalVm local = new LocalVm(id.localId(), name, List.of());
        return new VmOverview(
                id,
                VmId.providerBadge(id.providerId()),
                name,
                local,
                List.of(backups),
                status,
                VmChangeStatus.UNKNOWN
        );
    }

    private static VmOverview backupOnlyVm(VmId id) {
        return new VmOverview(
                id,
                VmId.providerBadge(id.providerId()),
                id.localId(),
                null,
                List.of(backup(id)),
                VmStatus.UNKNOWN,
                VmChangeStatus.UNKNOWN
        );
    }

    private static VmBackup backup(VmId id) {
        return new VmBackup(
                "backup-" + id.localId(),
                BATCH,
                id.localId(),
                Path.of("VMs", id.localId()),
                LocalDateTime.of(2026, 10, 20, 12, 0),
                "Integration backup",
                false,
                null
        );
    }

    private static boolean checkboxDisabled(String html, String value) {
        return tagContaining(html, "<input", "value=\"" + value + "\"").contains("disabled");
    }

    private static boolean buttonDisabled(String html, String marker) {
        return tagContaining(html, "<button", marker).contains("disabled");
    }

    /** Returns the start tag that contains the given marker, so attribute order does not matter. */
    private static String tagContaining(String html, String tagStart, String marker) {
        var matcher = Pattern.compile(Pattern.quote(tagStart) + "[^>]*>", Pattern.DOTALL).matcher(html);

        while (matcher.find()) {
            if (matcher.group().contains(marker)) {
                return matcher.group();
            }
        }

        throw new AssertionError("No " + tagStart + " tag containing " + marker);
    }
}
