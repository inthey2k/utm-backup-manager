# UTM Backup Manager 1.5.4

## Overview

Version 1.5.4 is a maintenance release focused on user interface refinements and improved visibility of VM sizes, backup sizes and running jobs.

The overview now displays operating system icons where available, sorts VMs alphabetically within each provider and shows size information directly on VM headers and backup cards. Live backup progress includes transferred and total data sizes at both job and VM level.

This release also improves the settings menu, configuration dialog, browser-tab status feedback and protection against accidental duplicate deletion submissions. Existing backup, retention and deletion semantics remain unchanged.

## Highlights

### VM and backup size visibility

- Display the size of each individual backup directly on its backup card.
- Display the size of the current local VM in its column header.
- Cache backup sizes to avoid repeated directory traversal for unchanged backups.
- Calculate current VM sizes when the overview is rendered.
- Display sizes in decimal GB with at most one decimal place, omitting unnecessary trailing zeros.
- Use locale-aware number formatting, such as `1.2 GB` in English and `1,2 GB` in German.

### VM icons and alphabetical ordering

- Display the configured UTM icon next to the VM name, supporting both built-in operating system icons and custom images.
- Resolve built-in icons from the installed UTM application's resources using the configured `utmctl` path, including symbolic links used by Homebrew installations.
- Resolve custom icons from the VM package's `Data` directory.
- Use a consistently sized coloured circle with initials derived from the VM name when no usable icon is available. QEMU continues to use this fallback.
- Expose icon information through the provider abstraction without embedding UTM-specific logic in the frontend.
- Sort VMs alphabetically by their current display name, ignoring case, while preserving provider grouping.
- Align icons at the top of the VM name area and equalise VM header heights so that backup cards start at a consistent vertical position, even with multi-line VM names.

UTM image assets are read from the local installation or VM package and are not bundled with the Backup Manager.

### Live backup transfer progress

- Extend the existing job progress display with transferred and total data sizes, for example `34 % copied (48 of 117 GB)`.
- Display per-VM transferred and total sizes next to the provider badge of the VM currently being backed up, for example `48 / 117 GB`.
- Reuse the existing pre-copy size calculation to obtain per-VM totals without an additional directory scan during progress polling.
- Apply the same locale-aware GB formatting used throughout the overview.

### Browser-tab job status

- Added an application favicon.
- Animate the browser-tab title during backup and deletion operations so that running jobs remain visible when another tab is active.
- Use job-specific status labels for backups and deletions.
- Show a completion indicator when a job finishes while the tab is inactive, returning to the normal application title when the tab is focused.
- Use a shared frontend mechanism for job-related tab-title updates.

### Settings and configuration dialog

- Fixed the settings menu layering and positioning so that the dropdown is no longer covered by the header or overlaps its trigger button.
- Group provider-specific configuration values under their respective provider names.
- Remove the redundant VM-provider row and display global settings in a separate section.

### Duplicate deletion submission protection

- Disable the relevant confirmation button immediately after submitting a retention cleanup or manual bulk deletion request.
- Prevent accidental repeated submissions through the user interface, following the existing backup-start behaviour.

## Reliability and compatibility

- Existing Format 1 / legacy and Format 2 backups remain supported without a data migration.
- Canonical VM identity handling and existing Stable protection remain unchanged.
- Icon resolution validates file names, supported image types, file size limits and real-path containment to prevent access outside the permitted directories.
- Missing or unusable icon files fall back to the generated initials icon.
- Updated all three supported language bundles (English, German and Spanish).
- Real-system browser testing on macOS with UTM 5.0.8 confirmed the size displays, alphabetical ordering, built-in and custom UTM icons, header alignment, settings menu, grouped configuration dialog, backup progress, deletion-button locking and browser-tab job feedback.

## Build and quality

- Added or extended regression tests for VM ordering, size formatting and caching, provider icon resolution, configuration binding, per-VM progress and overview rendering.
- Added JavaScript tests for the shared browser-tab title mechanism.
- Latest verified Java test suite: **648 tests, 0 failures**
- JavaScript tab-title tests: **8 tests passed** (last verified run)
- SonarQube Quality Gate: **Passed**
- SonarQube new issues: **0**
- Updated the README, example configuration and hypervisor provider integration guide.

## Upgrade notes

No manual migration is required. Existing backup data, metadata and configuration remain compatible with 1.5.4.

Built-in UTM icons are discovered automatically from the configured `utmctl` executable. If automatic discovery is not possible, the icon directory can be configured explicitly using the optional property:

```properties
vm.utm.icons.directory=/Applications/UTM.app/Contents/Resources/Icons
```

No additional configuration is required for custom UTM icons or the generic fallback icons.

The new size displays provide informational values, not a guarantee of sufficient free space for a backup. Free-space reporting, conservative capacity checks and sparse-aware capacity estimation are planned for a subsequent release.
