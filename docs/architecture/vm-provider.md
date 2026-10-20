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
| `UtmVmProvider` | `provider.utm` | The only production provider in 1.4.0 |
| `TestVmProvider` | `provider` (test sources) | Fake provider for the `test` profile |
| `VmProperties` | `config` | Binds `vm.providers`, the deprecated `vm.provider` and `vm.utm.*` |
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
- **Temporary backup restriction.** The backup writer still uses the legacy UTM
  layout, so only UTM VMs can be backed up. A selection that contains any other
  provider's VM is rejected before anything is written. Format 2 will lift this.

Profiles add a second guard. `UtmVmProvider` is annotated with `@Profile("!test")`,
and `TestVmProvider` with `@Profile("test")`, so the integration tests never touch a
real hypervisor, even when a real provider id is configured.

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
  backup manager stores it in backup IDs and in the `vm_state` table, so changing
  it makes existing backups and change-tracking state appear unrelated.
- **`name`**: the display name, for presentation only. It is not used for identity or status lookup.
- **`backupSources`**: every file or directory that belongs to the VM. Each source
  is copied into `VMs/<file name>/` of the backup batch. File names must be unique
  across all VMs in one batch, or backup creation rejects the batch.

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

2. **Bind configuration.** `VmProperties` binds `vm.providers`, the deprecated
   `vm.provider` and the nested `Utm` record. Give your provider its own
   `@ConfigurationProperties` record under its own prefix, and document the new
   properties in `application.properties.example` and the README.

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
- [ ] Backup sources contain every file needed to restore the VM, with unique file names per batch.
- [ ] `stop` either implements shutdown or throws a clear `UnsupportedOperationException`.
- [ ] Message keys exist in every `messages*.properties` file.
- [ ] Tests run against temporary directories or fixtures, never real VMs.

## Known limitations

- Provider-specific properties are validated only by the provider itself. An
  enabled UTM without `vm.utm.directory` or `vm.utm.cli` fails at startup with a
  clear message.
- Only UTM VMs can be backed up until the format-2 backup layout is implemented.
  Other providers' VMs appear in the overview and can be inspected.
- Duplicate provider IDs fail at startup. Ambiguous display names in utmctl output
  no longer break the overview: the affected VMs are reported as `UNKNOWN`.
- `VmProvider` is not yet a plugin system. Providers must be compiled into the
  application. Loading external providers is not supported.
