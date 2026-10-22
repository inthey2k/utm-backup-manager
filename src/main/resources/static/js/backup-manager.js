"use strict";

// ---------------------------------------------------------------------------
// DOM references and shared states
// ---------------------------------------------------------------------------
const backupProgressVms =
    document.getElementById("backup-progress-vms");
const backupCancelButton =
    document.getElementById("backup-cancel-button");
const backupCancelDialog =
    document.getElementById("backup-cancel-dialog");
const backupCancelConfirm =
    document.getElementById("backup-cancel-confirm");
const retentionConfirmationDialog =
    document.getElementById("retention-confirmation-dialog");
const retentionConfirmationVms =
    document.getElementById("retention-confirmation-vms");
const retentionConfirmationCancel =
    document.getElementById("retention-confirmation-cancel");
const retentionConfirmationStart =
    document.getElementById("retention-confirmation-start");
const backupCompletionMessage =
    document.getElementById("backup-completion-message");
const backupCompletionTitle =
    document.getElementById("backup-completion-title");
const backupCompletionStartedLabel =
    document.getElementById("backup-completion-started-label");
const backupCompletionStarted =
    document.getElementById("backup-completion-started");
const backupCompletionFinishedLabel =
    document.getElementById("backup-completion-finished-label");
const backupCompletionFinished =
    document.getElementById("backup-completion-finished");
const backupCompletionDurationLabel =
    document.getElementById("backup-completion-duration-label");
const backupCompletionDuration =
    document.getElementById("backup-completion-duration");
const backupCompletionClose =
    document.getElementById("backup-completion-close");

const BACKUP_COMPLETION_STORAGE_KEY = "backupCompletion";
let retentionConfirmed = false;

const headerMenuButton = document.getElementById("header-menu-button");
const headerMenu = document.getElementById("header-menu");
const configurationButton = document.getElementById("configuration-button");
const configurationDialog = document.getElementById("configuration-dialog");
const configurationClose = document.getElementById("configuration-close");

document.querySelectorAll(".note-button").forEach(button => {
    button.addEventListener("click", () => {
        const dialog = document.getElementById(button.dataset.dialogId);

        if (dialog) {
            dialog.showModal();
        }
    });
});

document.querySelectorAll(".dialog-close, .dialog-cancel").forEach(button => {
    button.addEventListener("click", () => {
        button.closest("dialog").close();
    });
});

const createBackupButton = document.getElementById("create-backup-button");
const backupDialog = document.getElementById("backup-dialog");
const backupForm = backupDialog?.querySelector("form");
const backupCheckboxes = backupDialog?.querySelectorAll(
    'input[name="vmIds"]:not(:disabled)'
);
const backupSubmitButton = backupDialog?.querySelector(
    'button[type="submit"]'
);

// ---------------------------------------------------------------------------
// Dialog handling
// ---------------------------------------------------------------------------
retentionConfirmationCancel?.addEventListener("click", () => {
    retentionConfirmationDialog.close();
});

retentionConfirmationStart?.addEventListener("click", () => {
    retentionConfirmed = true;
     // Store the confirmed retention decision in the submitted form in hidden field.
    document.getElementById("retention-confirmed").value = "true";
    retentionConfirmationDialog.close();
    backupForm?.requestSubmit();
});

// ---------------------------------------------------------------------------
// Backup selection and retention confirmation
// ---------------------------------------------------------------------------
backupForm?.addEventListener("submit", event => {
    const selectedCheckboxes = Array.from(
        backupCheckboxes || []
    ).filter(checkbox => checkbox.checked);
    const affectedVms = selectedCheckboxes.filter(
        checkbox =>
            checkbox.dataset.retentionCleanupRequired === "true"
    );
    if (affectedVms.length > 0 && !retentionConfirmed) {
        event.preventDefault();
        retentionConfirmationVms.replaceChildren();
        affectedVms.forEach(checkbox => {
            const item = document.createElement("li");
            item.textContent = checkbox.dataset.vmName;
            retentionConfirmationVms.appendChild(item);
        });
        retentionConfirmationDialog.showModal();
        retentionConfirmationCancel.focus();
        return;
    }
    if (!backupSubmitButton) {
        return;
    }
    backupSubmitButton.disabled = true;
    backupSubmitButton.textContent =
        backupSubmitButton.dataset.startingLabel;
});

// ---------------------------------------------------------------------------
// Backup progress and polling
// ---------------------------------------------------------------------------
const backupSelectionSize = document.getElementById("backup-selection-size");
const backupProgress = document.getElementById("backup-progress");
const backupProgressTitle = document.getElementById("backup-progress-title");
const backupProgressPercent = document.getElementById("backup-progress-percent");
const backupProgressTrack = document.getElementById("backup-progress-track");
const backupProgressBar = document.getElementById("backup-progress-bar");
const backupProgressVmNumber = document.getElementById("backup-progress-vm-number");
const backupProgressPercentInline = document.getElementById("backup-progress-percent-inline");
const backupProgressElapsed = document.getElementById("backup-progress-elapsed");
const backupProgressRemaining = document.getElementById("backup-progress-remaining");
const vmSizes = new Map();

let backupJobRunning = createBackupButton?.dataset.jobRunning === "true";
let backupJobPollTimer = null;
let displayedElapsedSeconds = 0;
let displayedRemainingSeconds = null;
let backupTimeTimer = null;

function updateCreateBackupButton() {
    if (!createBackupButton) {
        return;
    }

    // Read-only storage is rendered server-side; the backend guard still rejects the job.
    createBackupButton.disabled =
        backupJobRunning
        || !backupDialog
        || createBackupButton.dataset.storageBlocked === "true";

    createBackupButton.textContent = backupJobRunning
        ? createBackupButton.dataset.labelRunning
        : createBackupButton.dataset.labelReady;
}

function updateBackupProgress(job) {
    if (!backupProgress) {
        return;
    }

    if (job.status !== "RUNNING") {
        backupProgress.classList.add("hidden");
        return;
    }

    backupProgress.classList.remove("hidden");

    const hasProgress = job.totalBytes > 0;
    const percent = hasProgress ? job.progressPercent : 0;
    backupProgressTitle.textContent = backupProgress.dataset.labelRunning;

    updateBackupProgressVms(job);

    backupProgressPercent.textContent = hasProgress ? percent + " %" : "";

    backupProgressBar.style.width = percent + "%";
    backupProgressTrack.setAttribute("aria-valuenow", String(percent));

    if (backupProgressVmNumber) {
        backupProgressVmNumber.textContent =
            job.currentVmNumber > 0 && job.vmCount > 0
                ? job.currentVmNumber + "/" + job.vmCount
                : "0/0";
    }

    if (backupProgressPercentInline) {
        backupProgressPercentInline.textContent = hasProgress ? String(percent) : "0";
    }

    displayedElapsedSeconds = job.elapsedSeconds || 0;

    displayedRemainingSeconds = calculateRemainingSeconds(displayedElapsedSeconds,percent);

    updateBackupTimes();
    startBackupTimeTimer();

}

function updateBackupProgressVms(job) {
    if (!backupProgressVms) {
        return;
    }

    backupProgressVms.replaceChildren();

    const vmNames = job.vmNames || [];

    vmNames.forEach((vmName, index) => {
        const vmNumber = index + 1;

        const vmElement = document.createElement("span");
        vmElement.className = "backup-progress-vm";

        const icon = document.createElement("span");
        icon.className = "backup-progress-vm-icon";
        icon.setAttribute("aria-hidden", "true");

        if (job.status === "COMPLETED"
            || vmNumber < job.currentVmNumber) {
            vmElement.classList.add("completed");
            icon.classList.add("completed");
            icon.textContent = "✓";
        } else if (vmNumber === job.currentVmNumber) {
            vmElement.classList.add("current");
            icon.classList.add("current", "spinner");
        } else {
            vmElement.classList.add("waiting");
            icon.classList.add("waiting");
            icon.textContent = "◷";
        }

        const label = document.createElement("span");
        label.textContent = vmName;

        vmElement.append(icon, label);
        backupProgressVms.appendChild(vmElement);
    });
}

function updateRetentionPendingDeletions(job) {
    const pendingIds =
        new Set(job.retentionPendingDeletionIds || []);

    document.querySelectorAll(".card[data-backup-id]").forEach(card => {
        const pending =
            job.status === "RUNNING"
            && pendingIds.has(card.dataset.backupId);

        card.classList.toggle(
            "retention-pending-deletion-card",
            pending
        );

        const marker =
            card.querySelector(".retention-pending-deletion");

        marker?.classList.toggle("hidden", !pending);
    });
}

function formatDuration(seconds) {
    const totalSeconds = Math.max(0, Math.round(seconds));
    const minutes = Math.floor(totalSeconds / 60);
    const secs = totalSeconds % 60;

    return String(minutes).padStart(2, "0")
        + ":"
        + String(secs).padStart(2, "0");
}

function calculateRemainingSeconds(elapsedSeconds, percent) {
    if (elapsedSeconds < 10 || percent < 5 || percent >= 100) {
        return null;
    }

    return Math.round(
        elapsedSeconds * (100 - percent) / percent
    );
}

function updateBackupTimes() {
    if (backupProgressElapsed) {
        backupProgressElapsed.textContent =
            formatDuration(displayedElapsedSeconds);
    }

    if (backupProgressRemaining) {
        backupProgressRemaining.textContent =
            displayedRemainingSeconds === null
                ? "--:--"
                : formatDuration(displayedRemainingSeconds);
    }
}

function startBackupTimeTimer() {
    if (backupTimeTimer !== null) {
        return;
    }

    backupTimeTimer = window.setInterval(() => {
        if (!backupJobRunning) {
            return;
        }

        displayedElapsedSeconds++;

        if (displayedRemainingSeconds !== null
            && displayedRemainingSeconds > 0) {
            displayedRemainingSeconds--;
        }

        updateBackupTimes();
    }, 1000);
}

function stopBackupTimeTimer() {
    if (backupTimeTimer !== null) {
        clearInterval(backupTimeTimer);
        backupTimeTimer = null;
    }
}

async function pollBackupJob() {
    if (!backupJobRunning) {
        return;
    }

    try {
        const response = await fetch("/backup/status", {
            cache: "no-store"
        });

        if (!response.ok) {
            return;
        }

        const job = await response.json();

        updateBackupProgress(job);
        // mark deletion candidates
        updateRetentionPendingDeletions(job);

        if (job.status === "RUNNING") {
            return;
        }

        backupJobRunning = false;
        updateCreateBackupButton();

        if (backupJobPollTimer !== null) {
            clearInterval(backupJobPollTimer);
            backupJobPollTimer = null;
        }

        stopBackupTimeTimer();

        if (job.status === "COMPLETED") {
            storeBackupCompletion(job);

            window.location.reload();
            return;
        }

        if (job.status === "CANCELLED") {
            window.location.reload();
            return;
        }

        if (job.status === "FAILED") {
            window.alert(job.errorMessage || "Backup failed.");
        }
    } catch (error) {
        // A temporary polling failure must not affect the running backup.
    }
}

// after backup is complete, check for cleanup job
const retentionCleanupStatus =
    document.getElementById("retention-cleanup-status");
const retentionCleanupStatusText =
    document.getElementById("retention-cleanup-status-text");
const RETENTION_CLEANUP_RELOAD_KEY =
    "retentionCleanupReloadedAt";

let retentionCleanupPollTimer = null;

function updateRetentionCleanupStatus(job) {
    if (!retentionCleanupStatus || !retentionCleanupStatusText) {
        return;
    }

    retentionCleanupStatus.classList.remove(
        "hidden",
        "cleanup-status-running",
        "cleanup-status-completed",
        "cleanup-status-failed"
    );

    if (job.status === "IDLE") {
        retentionCleanupStatus.classList.add("hidden");
        return;
    }

    if (job.status === "RUNNING") {
        retentionCleanupStatus.classList.add(
            "cleanup-status-running"
        );

        retentionCleanupStatusText.textContent =
            retentionCleanupStatus.dataset.labelRunning;

        return;
    }

    if (job.status === "COMPLETED") {
        retentionCleanupStatus.classList.add(
            "cleanup-status-completed"
        );

        retentionCleanupStatusText.textContent =
            retentionCleanupStatus.dataset.labelCompleted;

        return;
    }

    if (job.status === "FAILED") {
        retentionCleanupStatus.classList.add(
            "cleanup-status-failed"
        );

        retentionCleanupStatusText.textContent =
            retentionCleanupStatus.dataset.labelFailed
            + " "
            + (job.errorMessage || "");
    }
}
async function pollRetentionCleanupJob() {
    try {
        const response = await fetch(
            "/retention-cleanup/status",
            {cache: "no-store"}
        );
        if (!response.ok) {
            return;
        }
        const job = await response.json();

        updateRetentionCleanupStatus(job);

        if (job.status === "COMPLETED") {
            const reloadedAt =
                sessionStorage.getItem(
                    RETENTION_CLEANUP_RELOAD_KEY
                );

            if (job.startedAt
                && reloadedAt !== job.startedAt) {
                sessionStorage.setItem(
                    RETENTION_CLEANUP_RELOAD_KEY,
                    job.startedAt
                );
                window.location.reload();
                return;
            }
        }

        if (job.status !== "RUNNING"
            && retentionCleanupPollTimer !== null) {
            clearInterval(retentionCleanupPollTimer);
            retentionCleanupPollTimer = null;
        }

    } catch (error) {
        // A temporary polling failure must not affect cleanup execution.
    }
}

function startRetentionCleanupPolling() {
    if (retentionCleanupPollTimer !== null) {
        return;
    }

    pollRetentionCleanupJob();

    retentionCleanupPollTimer =
        window.setInterval(pollRetentionCleanupJob, 2000);
}

function startBackupJobPolling() {
    if (!backupJobRunning || backupJobPollTimer !== null) {
        return;
    }

    // Show the current state immediately, then continue with a low-cost poll.
    pollBackupJob();
    backupJobPollTimer = window.setInterval(pollBackupJob, 5000);
}

function formatBytes(bytes) {
    const gigabytes = bytes / 1_000_000_000;

    return new Intl.NumberFormat(document.documentElement.lang, {
        minimumFractionDigits: 1,
        maximumFractionDigits: 1
    }).format(gigabytes) + " GB";
}

function updateBackupSubmitButton() {
    if (!backupSubmitButton || !backupCheckboxes) {
        return;
    }

    backupSubmitButton.disabled =
        !Array.from(backupCheckboxes).some(checkbox => checkbox.checked);
}

// ---------------------------------------------------------------------------
// Backup completion message
// ---------------------------------------------------------------------------

function formatDateTime(value) {
    if (!value) {
        return "";
    }

    return new Intl.DateTimeFormat(
        document.documentElement.lang,
        {
            dateStyle: "short",
            timeStyle: "medium"
        }
    ).format(new Date(value));
}

function formatCompletionDuration(seconds) {
    const totalSeconds = Math.max(0, Math.round(seconds || 0));
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const secs = totalSeconds % 60;

    if (hours > 0) {
        return String(hours).padStart(2, "0")
            + ":"
            + String(minutes).padStart(2, "0")
            + ":"
            + String(secs).padStart(2, "0");
    }

    return String(minutes).padStart(2, "0")
        + ":"
        + String(secs).padStart(2, "0");
}

function showBackupCompletionMessage(job) {
    if (!backupCompletionMessage) {
        return;
    }

    backupCompletionTitle.textContent =
        backupCompletionMessage.dataset.labelTitle;

    backupCompletionStartedLabel.textContent =
        backupCompletionMessage.dataset.labelStarted;

    backupCompletionStarted.textContent =
        formatDateTime(job.startedAt);

    backupCompletionFinishedLabel.textContent =
        backupCompletionMessage.dataset.labelFinished;

    backupCompletionFinished.textContent =
        formatDateTime(job.finishedAt);

    backupCompletionDurationLabel.textContent =
        backupCompletionMessage.dataset.labelDuration;

    backupCompletionDuration.textContent =
        formatCompletionDuration(job.durationSeconds);

    backupCompletionMessage.classList.remove("hidden");
}

function storeBackupCompletion(job) {
    sessionStorage.setItem(
        BACKUP_COMPLETION_STORAGE_KEY,
        JSON.stringify({
            startedAt: job.startedAt,
            finishedAt: job.finishedAt,
            durationSeconds: job.durationSeconds
        })
    );
}

function restoreBackupCompletionMessage() {
    const stored =
        sessionStorage.getItem(BACKUP_COMPLETION_STORAGE_KEY);

    if (!stored) {
        return;
    }

    try {
        const job = JSON.parse(stored);

        if (!job.startedAt
            || !job.finishedAt
            || typeof job.durationSeconds !== "number") {
            sessionStorage.removeItem(BACKUP_COMPLETION_STORAGE_KEY);
            return;
        }

        showBackupCompletionMessage(job);

    } catch (error) {
        sessionStorage.removeItem(BACKUP_COMPLETION_STORAGE_KEY);
    }
}

backupCompletionClose?.addEventListener("click", () => {
    sessionStorage.removeItem(BACKUP_COMPLETION_STORAGE_KEY);
});

// ---------------------------------------------------------------------------
// VM size handling
// ---------------------------------------------------------------------------
function updateSelectedSize() {
    if (!backupSelectionSize || !backupCheckboxes) {
        return;
    }

    const selected = Array.from(backupCheckboxes)
        .filter(checkbox => checkbox.checked);

    if (selected.length === 0) {
        backupSelectionSize.textContent = "";
        return;
    }

    const allSizesKnown = selected.every(
        checkbox => vmSizes.has(checkbox.value)
    );

    if (!allSizesKnown) {
        backupSelectionSize.textContent =
            backupSelectionSize.dataset.loading;
        return;
    }

    const totalBytes = selected.reduce(
        (sum, checkbox) => sum + vmSizes.get(checkbox.value),
        0
    );

    backupSelectionSize.textContent =
        backupSelectionSize.dataset.label + ": " + formatBytes(totalBytes);
}

async function loadVmSize(sizeElement) {
    const vmId = sizeElement.dataset.vmId;

    sizeElement.innerHTML =
        '<span class="vm-size-spinner" aria-hidden="true"></span>';

    try {
        const response = await fetch(
            "/vm/" + encodeURIComponent(vmId) + "/size"
        );

        if (!response.ok) {
            throw new Error("Could not determine VM size");
        }

        const result = await response.json();

        vmSizes.set(vmId, result.bytes);
        sizeElement.textContent = "(" + formatBytes(result.bytes) + ")";
    } catch (error) {
        sizeElement.textContent = "…";
    } finally {
        updateSelectedSize();
    }
}

function loadVmSizes() {
    if (!backupDialog) {
        return;
    }

    backupDialog.querySelectorAll(".backup-vm-size").forEach(sizeElement => {
        const vmId = sizeElement.dataset.vmId;

        if (!vmSizes.has(vmId)) {
            loadVmSize(sizeElement);
        }
    });
}

// ---------------------------------------------------------------------------
// Header menu and configuration dialog
// ---------------------------------------------------------------------------
headerMenuButton?.addEventListener("click", event => {
    event.stopPropagation();

    const opening = headerMenu.hidden;
    headerMenu.hidden = !opening;
    headerMenuButton.setAttribute("aria-expanded", String(opening));
});

headerMenu?.addEventListener("click", event => {
    event.stopPropagation();
});

document.addEventListener("click", () => {
    if (headerMenu && !headerMenu.hidden) {
        headerMenu.hidden = true;
        headerMenuButton?.setAttribute("aria-expanded", "false");
    }
});

configurationButton?.addEventListener("click", () => {
    headerMenu.hidden = true;
    headerMenuButton?.setAttribute("aria-expanded", "false");
    configurationDialog?.showModal();
});

configurationClose?.addEventListener("click", () => {
    configurationDialog?.close();
});

configurationDialog?.addEventListener("click", event => {
    if (event.target === configurationDialog) {
        configurationDialog.close();
    }
});

createBackupButton?.addEventListener("click", () => {
    if (backupJobRunning) {
        return;
    }
    // Reset the retention confirmation for each new backup selection.
    retentionConfirmed = false;
    const retentionConfirmedInput =
        document.getElementById("retention-confirmed");
    if (retentionConfirmedInput) {
        retentionConfirmedInput.value = "false";
    }

    backupDialog?.showModal();
    loadVmSizes();
});

backupDialog?.addEventListener("click", event => {
    if (event.target === backupDialog) {
        backupDialog.close();
    }
});

backupCheckboxes?.forEach(checkbox => {
    checkbox.addEventListener("change", () => {
        updateBackupSubmitButton();
        updateSelectedSize();
    });
});

// Initialize page on reload
updateBackupSubmitButton();
updateSelectedSize();
updateCreateBackupButton();
startBackupJobPolling();
restoreBackupCompletionMessage();
startRetentionCleanupPolling();

// ---------------------------------------------------------------------------
// Backup cancellation
// ---------------------------------------------------------------------------
backupCancelButton?.addEventListener("click", () => {
    if (!backupJobRunning) {
        return;
    }

    backupCancelDialog?.showModal();
});

backupCancelConfirm?.addEventListener("click", async () => {
    backupCancelConfirm.disabled = true;
    backupCancelConfirm.textContent =
        backupCancelConfirm.dataset.cancellingLabel;

    try {
        const response = await fetch("/backup/cancel", {
            method: "POST"
        });

        if (!response.ok) {
            throw new Error("Could not cancel backup");
        }

        backupCancelDialog?.close();
    } catch (error) {
        backupCancelConfirm.disabled = false;
        window.alert(error.message);
    }
});

// ---------------------------------------------------------------------------
// Backup note and delete dialogs
// ---------------------------------------------------------------------------
document.querySelectorAll(".note-dialog").forEach(dialog => {
    dialog.addEventListener("click", event => {
        if (event.target === dialog) {
            dialog.close();
        }
    });
});

document.querySelectorAll(".delete-button").forEach(button => {
    button.addEventListener("click", () => {
        const dialog = document.getElementById(button.dataset.dialogId);

        if (dialog) {
            dialog.showModal();

            const cancelButton = dialog.querySelector(".cancel-delete");

            if (cancelButton) {
                cancelButton.focus();
            }
        }
    });
});

document.querySelectorAll(".delete-cancel").forEach(button => {
    button.addEventListener("click", () => {
        button.closest("dialog").close();
    });
});

document.querySelectorAll(".delete-dialog").forEach(dialog => {

    dialog.addEventListener("click", event => {
        if (event.target === dialog) {
            dialog.close();
        }
    });

    dialog.addEventListener("keydown", event => {
        if (event.key === "Enter") {
            event.preventDefault();
            dialog.close();
        }
    });
});

// ---------------------------------------------------------------------------
// Flash messages and startup dialogs
// ---------------------------------------------------------------------------

// show status messages for five seconds by default
document.querySelectorAll(".flash-message").forEach(message => {
    const closeButton = message.querySelector(".flash-close");
    const timeout = Number(message.dataset.timeout);

    const close = () => {
        message.classList.add("hiding");
        setTimeout(() => message.remove(), 250);
    };

    closeButton?.addEventListener("click", close);

    if (timeout > 0) {
        setTimeout(close, timeout);
    }
});

const backupRootDialog = document.getElementById("backup-root-dialog");
backupRootDialog?.showModal();
