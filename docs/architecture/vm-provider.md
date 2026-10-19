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
| `VmProviderRegistry` | `provider` | Spring component that returns the provider selected by `vm.provider` |
| `UtmVmProvider` | `provider.utm` | The only production provider in 1.4.0 |
| `TestVmProvider` | `provider` (test sources) | Fake provider for the `test` profile |
| `VmProperties` | `config` | Binds `vm.*` properties, including `vm.provider` and `vm.utm.*` |
| `LocalVm` | `model` | Value object a provider returns for each VM |
| `VmStatus` | `model` | Generic runtime state: `RUNNING`, `STOPPED`, `UNKNOWN` |

## Provider selection

1. `vm.provider` (default `utm`, see `VmProperties`) names the active provider.
2. Spring injects every `VmProvider` bean into `VmProviderRegistry`.
3. The registry builds a map keyed by `providerId()`, normalized with `trim()`
   and lower-casing. Duplicate IDs make the registry fail at startup.
4. `VmProviderRegistry.current()` returns the provider whose ID matches
   `vm.provider`. If none matches, it throws `IllegalStateException`, listing the
   available IDs. This happens on first use, not at startup.

Callers never hold a provider reference. They call `vmProviderRegistry.current()`
each time. Current callers are `BackupScanner` (overview), `BackupStartController`
(start validation), `VmController` (VM size), and `ApplicationConfigurationView`
(configuration page).

Profiles control which providers exist at runtime. `UtmVmProvider` is annotated
with `@Profile("!test")`, and `TestVmProvider` with `@Profile("test")`, so the
integration tests never touch a real hypervisor.

## The `VmProvider` contract

| Method | Used for | Requirements |
|--------|----------|--------------|
| `providerId()` | Selection via `vm.provider` | Stable, unique, lower-case identifier |
| `displayName()` | Configuration page | Human-readable name |
| `configuration()` | Configuration page | Entries with `key`, `labelKey` (a message key), and `value` |
| `findAll()` | Overview, backup start, VM size | Returns all local VMs with a stable `id`, a display `name`, and backup sources |
| `findStatuses()` | Overview, backup start | Map from VM **name** to `VmStatus`; missing entries count as `UNKNOWN` |
| `stop(LocalVm)` | Reserved for future shutdown support | Only initiates shutdown; the manager handles waiting and timeouts. Not called today. |

### `LocalVm` fields

- **`id`**: a technical identifier that must stay the same across restarts. The
  backup manager stores it in backup IDs and in the `vm_state` table, so changing
  it makes existing backups and change-tracking state appear unrelated.
- **`name`**: the display name. It is also the key used in `findStatuses()`.
- **`backupSources`**: every file or directory that belongs to the VM. Each source
  is copied into `VMs/<file name>/` of the backup batch. File names must be unique
  across all VMs in one batch, or backup creation rejects the batch.

### Status matching

`findStatuses()` is keyed by **display name**, not by `id`. The scanner and the
start controller both look up `LocalVm.name()`, so a provider must use the same
value there. Otherwise the VM is reported as `UNKNOWN` and cannot be backed up.
Only `STOPPED` allows a backup. Any other state, including `UNKNOWN`, blocks it.
This is the fail-safe default.

## How `UtmVmProvider` works

- **Discovery (`findAll`)**: lists the directories directly under
  `vm.utm.directory` whose names end with `vm.utm.package-suffix` (default
  `.utm`). Each package becomes one `LocalVm`:
  - `id` is the package folder name including the suffix, e.g. `Linux.utm`.
  - `name` is the same folder name with the suffix removed, e.g. `Linux`.
  - `backupSources` contains the package directory itself.
  - Results are sorted case-insensitively by `name`. A missing directory yields an empty list.
- **States (`findStatuses`)**: runs `utmctl list` once, with a 5-second timeout.
  Each output row matching `<UUID> <status> <name>` becomes an entry, with the
  status mapped as follows:
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
           // Keys must equal LocalVm.name(). Return an empty map on failure.
       }

       @Override
       public void stop(LocalVm vm) {
           throw new UnsupportedOperationException("Stopping Example VMs is not implemented yet");
       }
   }
   ```

2. **Bind configuration.** `VmProperties` currently has only a nested `Utm` record.
   Add a nested record for your provider, or a separate `@ConfigurationProperties`
   record, and document the new properties in `application.properties.example`
   and the README.

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

- [ ] `providerId()` is unique and matches the value users put in `vm.provider`.
- [ ] `LocalVm.id` is stable across restarts and renames where possible.
- [ ] `findStatuses()` keys match `LocalVm.name()`.
- [ ] Failures return `UNKNOWN` (or an empty map), not `STOPPED`.
- [ ] Backup sources contain every file needed to restore the VM, with unique file names per batch.
- [ ] `stop` either implements shutdown or throws a clear `UnsupportedOperationException`.
- [ ] Message keys exist in every `messages*.properties` file.
- [ ] Tests run against temporary directories or fixtures, never real VMs.

## Known limitations

- `vm.provider` and the provider-specific properties are not validated at startup.
  An invalid provider ID only fails when first used.
- Duplicate provider IDs fail at startup, but `UtmVmProvider.findStatuses()` also
  fails (with `IllegalStateException`) if two VMs share a display name. This breaks
  the whole overview. A provider must avoid duplicate names.
- `VmProvider` is not yet a plugin system. Providers must be compiled into the
  application. Loading external providers is not supported.
