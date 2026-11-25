"use strict";

// Browser tab title for the job state. Every job type reports through setState() and
// markCompleted(), so the title logic exists only once. The functions that build titles
// are pure and exported for the node tests in src/test/js.
//
// States:
//   idle      - the plain application title
//   backup    - a backup is running: animated spinner and label
//   deletion  - a deletion is running: animated spinner and label
//   done      - a job completed while the tab was not focused; cleared when the tab gets focus

const TAB_TITLE_SPINNER_FRAMES = ["◐", "◓", "◑", "◒"];
const TAB_TITLE_SPINNER_INTERVAL_MS = 400;
const TAB_TITLE_DONE_KEY = "utm-backup-manager.tabTitleDone";

/**
 * Returns the title for a state. The spinner frame is only used for the running states.
 * labels: {app, backup, deletion, done}
 */
function resolveTabTitle(state, frameIndex, labels) {
    const frame = TAB_TITLE_SPINNER_FRAMES[frameIndex % TAB_TITLE_SPINNER_FRAMES.length];

    switch (state) {
        case "backup":
            return frame + " " + labels.backup + " – " + labels.app;
        case "deletion":
            return frame + " " + labels.deletion + " – " + labels.app;
        case "done":
            return "✓ " + labels.done + " – " + labels.app;
        default:
            return labels.app;
    }
}

/**
 * Returns whether a state shows an animated spinner.
 */
function isTabTitleRunning(state) {
    return state === "backup" || state === "deletion";
}

/**
 * Returns the state after a job completed. A completion in a focused tab needs no
 * notice, so it returns to idle. Otherwise the user is told until the tab is focused.
 */
function stateAfterCompletion(focused) {
    return focused ? "idle" : "done";
}

const TabTitle = (() => {
    let labels = null;
    let state = "idle";
    let frameIndex = 0;
    let spinnerTimer = null;

    function render() {
        document.title = resolveTabTitle(state, frameIndex, labels);
    }

    function stopSpinner() {
        if (spinnerTimer !== null) {
            clearInterval(spinnerTimer);
            spinnerTimer = null;
        }
    }

    function setState(newState) {
        if (!labels) {
            return;
        }

        if (newState === state && (isTabTitleRunning(state) === (spinnerTimer !== null))) {
            return;
        }

        state = newState;
        frameIndex = 0;
        stopSpinner();

        if (isTabTitleRunning(state)) {
            spinnerTimer = window.setInterval(() => {
                frameIndex = (frameIndex + 1) % TAB_TITLE_SPINNER_FRAMES.length;
                render();
            }, TAB_TITLE_SPINNER_INTERVAL_MS);
        }

        render();
    }

    function focused() {
        return typeof document.hasFocus === "function" && document.hasFocus();
    }

    /**
     * Called when a job has completed. If the tab is not focused, the completion is stored
     * in sessionStorage, so it survives the page reload that follows a completed job.
     */
    function markCompleted() {
        const next = stateAfterCompletion(focused());

        if (next === "done") {
            sessionStorage.setItem(TAB_TITLE_DONE_KEY, "1");
        }

        setState(next);
    }

    /**
     * Reads the labels from the body and restores a completion that happened before a reload.
     */
    function init(bodyDataset) {
        labels = {
            app: bodyDataset.titleApp || "UTM Backup Manager",
            backup: bodyDataset.titleBackup || "",
            deletion: bodyDataset.titleDeletion || "",
            done: bodyDataset.titleDone || ""
        };

        if (sessionStorage.getItem(TAB_TITLE_DONE_KEY) !== null && !focused()) {
            setState("done");
        } else {
            sessionStorage.removeItem(TAB_TITLE_DONE_KEY);
            render();
        }

        const clearDone = () => {
            if (state === "done") {
                setState("idle");
            }

            sessionStorage.removeItem(TAB_TITLE_DONE_KEY);
        };

        window.addEventListener("focus", clearDone);
        document.addEventListener("visibilitychange", () => {
            if (!document.hidden) {
                clearDone();
            }
        });
    }

    return {
        init,
        setState,
        markCompleted,
        currentState: () => state
    };
})();

if (typeof window !== "undefined") {
    window.TabTitle = TabTitle;

    // Deferred scripts run before DOMContentLoaded, so the labels are read here, before
    // backup-manager.js reports any job state.
    TabTitle.init(document.body.dataset);
}

if (typeof module !== "undefined" && module.exports) {
    module.exports = {
        resolveTabTitle,
        isTabTitleRunning,
        stateAfterCompletion,
        TAB_TITLE_SPINNER_FRAMES
    };
}
