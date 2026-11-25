"use strict";

// Run with: node --test src/test/js/tab-title.test.js
// The title logic is pure, so it runs without a browser.

const { test } = require("node:test");
const assert = require("node:assert/strict");
const {
    resolveTabTitle,
    isTabTitleRunning,
    stateAfterCompletion,
    TAB_TITLE_SPINNER_FRAMES
} = require("../../main/resources/static/js/tab-title.js");

const labels = {
    app: "UTM Backup Manager",
    backup: "Backup läuft",
    deletion: "Löschen läuft",
    done: "Fertig"
};

test("idle shows the plain application title", () => {
    assert.equal(resolveTabTitle("idle", 0, labels), "UTM Backup Manager");
});

test("running backup shows a spinner frame and the backup label", () => {
    assert.equal(
        resolveTabTitle("backup", 0, labels),
        TAB_TITLE_SPINNER_FRAMES[0] + " Backup läuft – UTM Backup Manager"
    );
});

test("running deletion shows a spinner frame and the deletion label", () => {
    assert.equal(
        resolveTabTitle("deletion", 2, labels),
        TAB_TITLE_SPINNER_FRAMES[2] + " Löschen läuft – UTM Backup Manager"
    );
});

test("spinner frames cycle and wrap around", () => {
    const frames = TAB_TITLE_SPINNER_FRAMES.length;

    assert.equal(
        resolveTabTitle("backup", frames, labels),
        resolveTabTitle("backup", 0, labels)
    );
    assert.equal(
        resolveTabTitle("backup", frames + 1, labels),
        resolveTabTitle("backup", 1, labels)
    );
});

test("completed state shows the check mark and the done label", () => {
    assert.equal(
        resolveTabTitle("done", 0, labels),
        "✓ Fertig – UTM Backup Manager"
    );
});

test("only backup and deletion are animated", () => {
    assert.equal(isTabTitleRunning("backup"), true);
    assert.equal(isTabTitleRunning("deletion"), true);
    assert.equal(isTabTitleRunning("done"), false);
    assert.equal(isTabTitleRunning("idle"), false);
});

test("a completion in a focused tab returns to idle without a notice", () => {
    assert.equal(stateAfterCompletion(true), "idle");
});

test("a completion in an unfocused tab is announced until the tab is focused", () => {
    assert.equal(stateAfterCompletion(false), "done");
});
