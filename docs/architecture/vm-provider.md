# VM Provider Architecture

This document describes how UTM Backup Manager integrates virtualization
platforms through the `VmProvider` abstraction, and how to add a new provider.

The provider layer is intentionally narrow. A provider only answers three
questions: *which VMs exist locally*, *what state are they in*, and *how is
their configuration described*. Backup creation, retention, metadata, and change
tracking live in the backup manager itself.

## Components

| Type | Package | Role |
|------|---------|------|
| `VmProvider` | `provider` | Interface every provider implements |
| `VmProviderRegistry` | `provider` | Spring component that holds the enabled providers from `vm.providers` |
| `EnabledVmProviders` | `provider` | Resolves the enabled provider ids: `vm.providers`, then deprecated `vm.provider`, then `utm` |
| `VmInventory` | `service` | The only generic service that calls providers. Builds the aggregated VM inventory |
| `VmId` | `model` | Canonical identity, serialized as `providerId:localId` |
| `ManagedVm`, `ProviderSnapshot` | `model` | A VM after aggregation, and the per-provider result including errors |
| `LegacyBackupLayout` | `service` | Maps format-1 backup folders to `utm:<folder>` |
| `ConditionalOnVmProvider` | `provider` | Activates a provider bean only when its id is enabled |
| `UtmVmProvider` | `provider.utm` | UTM provider: one `.utm` package per VM |
| `QemuVmProvider` | `provider.qemu` | QEMU provider: one directory per VM, states from running `qemu-system` processes |
| `QemuRuntimeStatus`, `QemuProcess` | `provider.qemu` | Pure status rules and the process view they are applied to |
| `QemuProperties`, `QemuProviderConfiguration` | `provider.qemu` | Bind `vm.qemu.*`; the configuration registers them only when QEMU is enabled |
| `UtmProperties`, `UtmProviderConfiguration` | `provider.utm` | Bind `vm.utm.*`; registered only when UTM is enabled |
| `TestVmProvider` | `provider` (test sources) | Fake provider for the `test` profile |
| `VmProperties` | `config` | Binds the global `vm.providers` and the deprecated `vm.provider` only |
| `LocalVm` | `model` | Value object a provider returns for each VM |
| `VmStatus` | `model` | Generic runtime state: `RUNNING`, `STOPPED`, `UNKNOWN` |

## Provider selection

1. `EnabledVmProviders` resolves the enabled ids, in this order: `vm.providers`
   (comma-separated, configured order kept), then the deprecated `vm.provider`
   (logs a warning), then `utm`. Ids are trimmed and lower-cased, then validated with
   the same rule as `VmId`. Blank entries, duplicates and invalid ids are rejected
   at startup.
2. A provider bean annotated with `@ConditionalOnVmProvider("<id>")` is created
   only when its id is enabled. A disabled provider is never instantiated, so its
   settings may be absent.
3. `VmProviderRegistry` indexes all provider beans by `providerId()`. Duplicate
   implementation ids fail at startup. Each enabled id must have exactly one
   implementation, otherwise startup fails and names the missing id.
4. `VmProviderRegistry.enabledProviders()` returns all enabled providers in
   configured order. There is no single-provider accessor.

Generic code never calls providers directly. `VmInventory.build()` queries each
enabled provider once and returns the complete inventory. Callers are
`BackupScanner` (overview), `BackupStartController` (start validation),
`VmController` (VM size), and `ApplicationConfigurationView` reads the enabled
providers and their configuration.

## Runtime aggregation

- **Identity.** Generic code uses `VmId`, that is `providerId:localId`, for example
  `utm:ArchLinux 02.27.utm` and `qemu:ArchLinux`. `LocalVm.id()` stays
  provider-local. The same local id in two providers yields two distinct VMs.
  Display names are never used for identity.
- **Status.** `findStatuses()` is keyed by the provider-local id. A VM without an
  entry is `UNKNOWN`.
- **Failures are isolated.** If `findAll()` fails, that provider contributes no
  local VMs, and its error is recorded in its `ProviderSnapshot`. If `findStatuses()`
  fails, its VMs are kept and reported as `UNKNOWN`. Other providers and existing
  backups are not affected.
- **Duplicate local ids** within one provider are excluded and flagged in the
  provider's errors, because they cannot be told apart.
- **Legacy backups.** A format-1 folder `VMs/<folder>` belongs to
  `utm:<folder>`, even when UTM is not enabled. Its backup id is unchanged.
- **Backup eligibility.** Every provider's VMs are written by the same format-2
  writer. A VM is backed up only when its status is `STOPPED`, whatever its provider.
  `CreateBackupService` checks this itself, so the rule does not depend on the web layer.

Profiles add a second guard. `UtmVmProvider` and `QemuVmProvider` are annotated with
`@Profile("!test")`, and `TestVmProvider` with `@Profile("test")`, so the integration
tests never touch a real hypervisor, even when a real provider id is configured.

## The `VmProvider` contract

| Method | Used for | Requirements |
|--------|----------|--------------|
| `providerId()` | Selection via `vm.providers` | Stable, unique, lower-case identifier |
| `displayName()` | Configuration page | Human-readable name |
| `configuration()` | Configuration page | Entries with `key`, `labelKey` (a message key), and `value` |
| `findAll()` | Overview, backup start, VM size | Returns all local VMs with a stable `id`, a display `name`, and backup sources |
| `findStatuses()` | Overview, backup start | Map from local VM **id** to `VmStatus`; missing entries count as `UNKNOWN` |
| `stop(LocalVm)` | Reserved for future shutdown support | Only initiates shutdown; the manager handles waiting and timeouts. Not called today. |

### `LocalVm` fields

- **`id`**: a technical identifier that must stay the same across restarts. The
  backup manager combines it with the provider id into the canonical `VmId`
  (`providerId:localId`). Change-tracking state in the `vm_state` table is keyed by
  that canonical id, so changing the local id makes an existing VM's state appear
  unrelated.
- **`name`**: the display name, for presentation only. It is not used for identity or status lookup.
- **`backupSources`**: every file or directory that belongs to the VM. Each VM gets
  its own container directory in the batch, and its sources are copied into that
  container under their own names. File names must be unique within one VM, not across
  the batch.

### Status matching

`findStatuses()` is keyed by the local **`id`**. The scanner and the start
controller both look up `LocalVm.id()`. A provider must not key statuses by
display name. A VM without an entry is reported as `UNKNOWN` and cannot be backed
up. Only `STOPPED` allows a backup. Any other state, including `UNKNOWN`, blocks
it. This is the fail-safe default.

## How `UtmVmProvider` works

- **Discovery (`findAll`)**: lists the directories directly under
  `vm.utm.directory` whose names end with `vm.utm.package-suffix` (default
  `.utm`). Each package becomes one `LocalVm`:
  - `id` is the package folder name including the suffix, e.g. `Linux.utm`.
  - `name` is the same folder name with the suffix removed, e.g. `Linux`.
  - `backupSources` contains the package directory itself.
  - Results are sorted case-insensitively by `name`. A missing directory yields an empty list.
- **States (`findStatuses`)**: runs `utmctl list` once, with a 5-second timeout.
  Each output row matching `<UUID> <status> <name>` is matched by display name
  against the discovered packages. A VM is reported only if exactly one package
  and exactly one utmctl row carry its name; otherwise it is `UNKNOWN`. The
  entry is keyed by the package folder name (the local id). The status is mapped
  as follows:
  - `STOPPED` → `STOPPED`
  - `STARTED`, `STARTING`, `PAUSED`, `STOPPING` → `RUNNING`
  - anything else → `UNKNOWN`

  If the command fails, times out, or is missing, the method returns an empty map,
  so every VM is reported as `UNKNOWN`.
- **Configuration**: exposes the VM directory, package suffix, and `utmctl`
  path with message keys `config.vmDirectory.label`, `config.packageSuffix`, and
  `config.providerCli`.
- **`stop`**: not implemented and throws `UnsupportedOperationException`. The
  README lists automated shutdown as a known limitation.

## How `QemuVmProvider` works

- **Configuration**: `vm.qemu.directory` (`QemuProperties`) is the VM root. QEMU is
  enabled with `vm.providers=utm,qemu`. It never needs UTM settings. A missing or
  unusable root is reported as a provider error and does not stop UTM.
- **Discovery (`findAll`)**: each immediate subdirectory of the root is one VM when it
  contains a top-level regular file ending in `.qcow2` or `.raw` (case-insensitive).
  - `id` and `name` are the directory name. The disk file name need not match it.
  - `backupSources` contains the VM directory, so the whole directory is copied.
  - Ordinary files in the root, hidden entries (names starting with `.`, including
    macOS `._` files), directories without a top-level disk, and nested directories
    are ignored. The provider never recurses.
  - A missing root, or a root that is not a directory, is an error naming the path.
    A subdirectory that cannot be listed fails the whole QEMU listing and names that
    directory. Other providers and backups are unaffected.
- **States (`findStatuses`)**: uses `ProcessHandle` only, so it starts no external
  command and needs no elevated privileges. Matching is by path, never by name.
  - `RUNNING`: a visible process whose arguments reference the VM directory, as a path,
    as the value of a `key=value` option, or inside a `file:`/`unix:` style URI. Any
    process counts, so a launcher such as `sudo` also marks the VM running. A false
    `RUNNING` only blocks a backup.
  - `UNKNOWN`: a `qemu-system-*` process whose arguments the operating system does not
    expose, which is typical for processes started by another user, such as with sudo
    on macOS. Also a `qemu-system-*` process that refers to a disk by a relative path,
    because the working directory is not visible. In these cases every QEMU VM without
    a matching process is `UNKNOWN`, because that process cannot be attributed.
  - `STOPPED`: otherwise.
  - Symbolic links: both the configured directory and its resolved real path are
    matched, so either spelling in the QEMU arguments is recognised.
  - Limitations: detection depends on what the JVM can read from the process table.
    A VM is reported `STOPPED` only when no readable argument list references its
    directory and no hidden `qemu-system-*` process exists. Hidden processes that are
    not `qemu-system-*` are not inspected. Verify on the target Mac before relying on
    this, especially for VMs started with sudo.
- **Backup**: uses the format-2 writer unchanged. The manifest records
  `Provider: qemu`, `VM-ID: <directory name>`, and the container `qemu__<name>`.
  Only `STOPPED` QEMU VMs can be backed up. Live QEMU snapshots and QMP are not used.
- **Change tracking**: keyed by `qemu:<localId>`. The newest modification time across
  the VM directory is compared with the acknowledged state, exactly as for UTM.
- **Stable, notes, deletion and retention**: the same provider-independent format-2
  rules. State is stored in `BACKUP-STATE.properties` inside the container.
- **Configuration**: exposes the VM root with the message key `config.vmDirectory.label`.
- **`stop`**: not implemented and throws `UnsupportedOperationException`.

## Implementing a new provider

The steps below assume a hypervisor named `Example`.

1. **Create the implementation** in `de.fjordkommission.utmbackup.provider.example`:

   ```java
   @Component
   @Profile("!test")
   @ConditionalOnVmProvider("example")
   public class ExampleVmProvider implements VmProvider {

       @Override
       public String providerId() {
           return "example";
       }

       @Override
       public String displayName() {
           return "Example";
       }

       @Override
       public List<ConfigurationEntry> configuration() {
           return List.of(new ConfigurationEntry(
                   "vmDirectory",
                   "config.vmDirectory.label",
                   vmDirectory.toString()));
       }

       @Override
       public List<LocalVm> findAll() {
           // Return one LocalVm per VM: stable id, display name, backup sources.
       }

       @Override
       public Map<String, VmStatus> findStatuses() {
           // Keys must equal LocalVm.id(). Return an empty map on failure.
       }

       @Override
       public void stop(LocalVm vm) {
           throw new UnsupportedOperationException("Stopping Example VMs is not implemented yet");
       }
   }
   ```

2. **Bind configuration.** `VmProperties` binds only `vm.providers` and the
   deprecated `vm.provider`. Give your provider its own `@ConfigurationProperties`
   record under its own prefix, and a `@Configuration` class annotated with
   `@ConditionalOnVmProvider` that enables it with `@EnableConfigurationProperties`.
   Document the new properties in `application.properties.example` and the README.

3. **Add message keys.** Each `ConfigurationEntry.labelKey` is passed through
   `messageSource` in `index.html`. Add the key to `messages.properties`, and to
   `messages_de.properties` and `messages_es.properties` as well.

4. **Test it.**
   - Unit-test `findAll` and the status mapping with temporary directories and
     fake command output.
   - Add a provider-level test in the style of `BackupScannerIntegrationTest`.
   - Make sure the `test` profile still uses `TestVmProvider`.

5. **Verify the safety rules.** Run `mvn clean test`, then start the application
   against a non-production directory. Confirm that a VM with an unknown status
   cannot be backed up.

### Checklist

- [ ] `providerId()` is unique and matches the value users put in `vm.providers`.
- [ ] The provider is annotated with `@ConditionalOnVmProvider` using the same id.
- [ ] `LocalVm.id` is stable across restarts and renames where possible.
- [ ] `findStatuses()` keys match `LocalVm.id()`.
- [ ] Failures return `UNKNOWN` (or an empty map), not `STOPPED`.
- [ ] Backup sources contain every file needed to restore the VM, with unique file names within each VM.
- [ ] `stop` either implements shutdown or throws a clear `UnsupportedOperationException`.
- [ ] Message keys exist in every `messages*.properties` file.
- [ ] Tests run against temporary directories or fixtures, never real VMs.

## Known limitations

- Provider-specific properties are validated only by the provider itself. An
  enabled UTM without `vm.utm.directory` or `vm.utm.cli` fails at startup with a
  clear message.
- Backup eligibility is `STOPPED` only. There are no live snapshots, and the provider
  never stops a VM. A VM that starts while it is being copied is not detected.
- Duplicate provider IDs fail at startup. Ambiguous display names in utmctl output
  no longer break the overview: the affected VMs are reported as `UNKNOWN`.
- `VmProvider` is not yet a plugin system. Providers must be compiled into the
  application. Loading external providers is not supported.
