# UTM Backup Manager 1.5.0

Version 1.5.0 introduces the first real multi-provider support in UTM Backup Manager and adds QEMU as a second hypervisor provider alongside UTM.

The release also strengthens provider isolation, backup safety, VM identity handling, and the overview UI.

## Highlights

### Multi-provider architecture

- Multiple VM providers can now be enabled at the same time with `vm.providers`.
- UTM and QEMU can be used side by side in one application instance.
- The legacy single-provider property `vm.provider` remains supported as a deprecated compatibility fallback.
- Provider-specific configuration is now isolated in each provider package.
- Disabled providers no longer require their configuration to be present.
- Provider failures are isolated so that one unavailable provider does not necessarily prevent the other providers from working.

Example:

```properties
vm.providers=utm,qemu

vm.utm.directory=/path/to/utm/vms
vm.utm.package-suffix=.utm
vm.utm.cli=/path/to/utmctl

vm.qemu.directory=/path/to/qemu/vms
```

## QEMU provider

QEMU is now available as the second built-in VM provider.

The provider:

- discovers VMs from subdirectories below `vm.qemu.directory`
- recognizes top-level `.qcow2` and `.raw` disk images
- uses the VM directory name as the provider-local VM id
- determines runtime state by inspecting running processes
- reports a VM as `RUNNING` when a visible QEMU process references its VM directory or disk image
- reports uncertain runtime state as `UNKNOWN` instead of incorrectly assuming that the VM is stopped
- handles paths that resolve through symbolic links

Only VMs reported as `STOPPED` are eligible for backup.

Automatic shutdown of QEMU VMs is not implemented yet.

## Stable VM identity

VM identity is now provider-aware.

The application internally uses canonical VM ids in the form:

```text
<providerId>:<localId>
```

Examples:

```text
utm:Linux Mint 23.utm
qemu:ArchLinux
```

This prevents collisions when different providers contain VMs with identical local names and allows provider-neutral services to work with a stable technical identity.

Runtime status lookup is keyed by the provider-local VM id rather than by the display name.

## Backup layout and compatibility

The backup system now supports provider-aware backup locations while preserving compatibility with historical UTM backups.

Legacy UTM backup directories remain unchanged.

New provider-aware backups can use a layout such as:

```text
VMs/<provider>/<localId>/
```

Existing backup ids and historical UTM backups remain compatible.

The VM state migration prefixes legacy state keys with the UTM provider id where required.

## Backup safety

Backup creation remains deliberately conservative:

- only VMs in `STOPPED` state can be backed up
- `RUNNING` and `UNKNOWN` are both rejected
- a provider that cannot determine runtime state safely must not report `STOPPED`
- provider-specific failures must not silently make a VM appear safe to back up

QEMU runtime-state detection received additional regression coverage for symbolic-link path handling.

## Overview UI

The backup overview has been updated for multiple providers.

Changes include:

- VMs are grouped by provider
- provider badges identify each VM
- provider availability is visible in the header
- unavailable providers do not hide backups that already exist
- each provider receives its own horizontal VM carousel
- carousel navigation advances exactly one VM column at a time
- navigation wraps continuously in both directions
- carousel controls remain aligned across provider rows
- providers without overflow keep the same table alignment
- the main page header remains visible while scrolling so the title, **Create backup** action, and menu stay accessible

## Provider configuration

Provider configuration is now owned by each provider.

UTM uses:

```text
UtmProperties
UtmProviderConfiguration
```

QEMU uses:

```text
QemuProperties
QemuProviderConfiguration
```

Both providers are activated conditionally through the configured provider list.

Adding another provider should not require changes to the application bootstrap class or to global VM configuration properties.

## Documentation

The hypervisor provider integration documentation has been expanded to cover:

- multi-provider activation
- canonical VM ids
- provider-local status keys
- provider-specific configuration
- conservative runtime-state handling
- process-based status detection
- QEMU behavior
- testing and safety requirements for additional providers

## Compatibility notes

The preferred provider configuration is now:

```properties
vm.providers=utm,qemu
```

The old property:

```properties
vm.provider=utm
```

is deprecated but remains available as a compatibility fallback for a single provider.

Existing UTM-only installations can continue to operate without enabling QEMU.

## Known limitations

- Automatic VM shutdown is not yet implemented.
- QEMU runtime state is inferred from process information. If the operating system hides the arguments of a relevant QEMU process, affected VMs may be reported as `UNKNOWN`.
- Only UTM and QEMU are currently implemented as VM providers.
- Only one backup job can run at a time.
- Change detection remains timestamp-based and is intended as an operational hint rather than a cryptographic comparison.
- Sparse virtual disk images may consume more physical space on the backup destination depending on the filesystem and mount implementation.

## Quality

The release was verified with the complete Maven build and automated test suite on the final `main` branch.
