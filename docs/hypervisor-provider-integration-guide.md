# Hypervisor Provider Integration Guide

For general info on how to contribute to this project, please refer to [CONTRIBUTING.md](../CONTRIBUTING.md)

This document is intended for project contributors who want to add support for an additional hypervisor to **UTM Backup Manager**.

It describes the provider extension point, the responsibilities of a provider implementation, the configuration and test work that is typically required, and the quality checks expected before submitting a contribution.

The guide is based on the provider architecture in UTM Backup Manager 1.4.0.

---

## 1. Architecture in one sentence

UTM Backup Manager separates **hypervisor-specific VM discovery and runtime control** from the generic backup workflow through the `VmProvider` interface.

A provider tells the application:

- which VMs exist locally,
- which files or directories belong to each VM,
- whether each VM is running, stopped, or in an unknown state,
- which provider-specific configuration values should be shown in the UI,
- and how a clean VM shutdown can be requested.

The backup manager itself remains responsible for:

- creating backups,
- retention,
- backup metadata,
- change tracking,
- backup deletion,
- backup job lifecycle,
- and the web UI.

This separation is intentional. A new hypervisor integration should normally **not** reimplement backup creation or retention logic.

---

# 2. Main extension points

## `VmProvider`

Package:

```text
de.fjordkommission.utmbackup.provider
```

The central Service Provider Interface is:

```java
public interface VmProvider {

    String providerId();

    String displayName();

    List<ConfigurationEntry> configuration();

    List<LocalVm> findAll();

    Map<String, VmStatus> findStatuses();

    void stop(LocalVm vm);

    record ConfigurationEntry(
            String key,
            String labelKey,
            String value
    ) {
    }
}
```

A new hypervisor provider must implement this interface.

The existing UTM implementation can be used as a reference:

```text
de.fjordkommission.utmbackup.provider.utm.UtmVmProvider
```

---

## `VmProviderRegistry`

`VmProviderRegistry` receives all Spring beans that implement `VmProvider`.

The enabled providers are selected through:

```properties
vm.providers=<provider-id>[,<provider-id>...]
```

For example:

```properties
vm.providers=utm
```

Resolution order (implemented in `EnabledVmProviders`):

1. `vm.providers`, a comma-separated list in the order given. An empty value counts as absent.
2. `vm.provider`, the deprecated single-provider property. It is used only when `vm.providers` is absent, and it logs a warning at startup.
3. `utm`, the default when neither property is present.

Provider ids are trimmed and lower-cased before validation, so `UTM` is accepted as `utm`. Valid ids use only lower-case letters, digits and hyphens. Blank entries and duplicate ids are rejected at startup.

Provider IDs are normalized to lower case by the registry.

Your `providerId()` therefore needs to be:

- unique,
- stable,
- suitable for use in configuration,
- and preferably short.

Example:

```java
@Override
public String providerId() {
    return "libvirt";
}
```

No central provider list normally needs to be edited. If your implementation is a Spring component, the registry discovers it automatically.

---

# 3. The `LocalVm` contract

Providers expose VMs through:

```java
public record LocalVm(
        String id,
        String name,
        List<Path> backupSources
)
```

Each returned VM must contain three meaningful values.

## `id`

The ID is the stable technical identity of the VM, unique within its provider. The application combines it with the provider id into the canonical `VmId` (`providerId:localId`). The provider supplies only the local part.

It is used to associate:

- the local VM,
- existing backups,
- persisted VM state,
- and later scans of the same VM.

The ID must therefore remain stable across application restarts.

Good examples are:

- a hypervisor UUID,
- a stable configuration directory name,
- another persistent provider-specific identifier.

Do **not** use a temporary process ID or another value that changes between runs.

## `name`

The name is the human-readable display name.

The name is not used for identity or for status lookup. `findStatuses()` is keyed by `id`.

## `backupSources`

This list contains every file or directory that must be copied to restore the VM.

The backup manager treats these paths generically and does not need to understand their hypervisor-specific contents.

Examples:

```java
new LocalVm(
        "vm-123",
        "Development VM",
        List.of(Path.of("/var/lib/myhypervisor/vms/vm-123"))
);
```

or:

```java
new LocalVm(
        "vm-123",
        "Development VM",
        List.of(
                Path.of("/etc/myhypervisor/vm-123.xml"),
                Path.of("/var/lib/myhypervisor/disks/vm-123.qcow2")
        )
);
```

A provider should expose all sources necessary for a usable restore.

---

# 4. Runtime state mapping

The provider must translate hypervisor-specific states into:

```java
public enum VmStatus {
    RUNNING,
    STOPPED,
    UNKNOWN
}
```

The important safety rule is:

> Only `STOPPED` is considered safe for backup.

States such as these should normally map to `RUNNING`:

- running,
- starting,
- paused,
- suspending,
- stopping.

Use `UNKNOWN` when the provider cannot reliably determine the state.

Do not return `STOPPED` merely because a CLI command failed.

Example:

```java
private VmStatus mapStatus(String state) {
    return switch (state) {
        case "shutoff" -> VmStatus.STOPPED;
        case "running", "paused", "shutdown" -> VmStatus.RUNNING;
        default -> VmStatus.UNKNOWN;
    };
}
```

---

# 5. `findStatuses()`

Signature:

```java
Map<String, VmStatus> findStatuses();
```

The map is keyed by the provider-local **`LocalVm.id()`**, never by the display name. Display names are for presentation only and must not be used as technical keys.

Example:

```java
return Map.of(
        "vm-123", VmStatus.STOPPED,
        "vm-456", VmStatus.RUNNING
);
```

Missing entries are treated by the application as `UNKNOWN`. If a runtime state cannot be matched to exactly one local VM (for example because two VMs share a name in the hypervisor's output), report `UNKNOWN` for that VM instead of throwing or guessing.

Where the hypervisor supports it, retrieve the state of multiple VMs in one operation rather than starting one external process for every VM.

The UTM provider follows this pattern by running one `utmctl list` command and parsing the complete result.

Provider failures should preferably degrade safely to `UNKNOWN` rather than falsely reporting a VM as stopped.

---

# 6. `findAll()`

Signature:

```java
List<LocalVm> findAll();
```

This method discovers all locally available VMs handled by the provider.

A typical implementation should:

1. inspect the hypervisor's configuration directory or API,
2. determine a stable ID,
3. determine the display name,
4. determine the paths required for backup,
5. create `LocalVm` objects,
6. return a deterministic list where practical.

Example skeleton:

```java
@Override
public List<LocalVm> findAll() {
    return hypervisorClient.listVms().stream()
            .map(vm -> new LocalVm(
                    vm.id(),
                    vm.name(),
                    backupSourcesFor(vm)
            ))
            .sorted(Comparator.comparing(
                    LocalVm::name,
                    String.CASE_INSENSITIVE_ORDER
            ))
            .toList();
}
```

If the configured VM location is unavailable, choose behavior deliberately.

The UTM provider currently returns an empty list if its VM directory does not exist and throws an `IllegalStateException` when an existing directory cannot be read.

---

# 7. `stop(LocalVm vm)`

Signature:

```java
void stop(LocalVm vm);
```

This method should request a **clean shutdown**.

It should not implement backup logic.

The SPI contract deliberately separates:

```text
request shutdown
```

from:

```text
wait until the VM is stopped
```

The provider should therefore initiate the hypervisor-specific shutdown operation and return.

The backup manager is responsible for any future waiting or timeout behavior.

In version 1.4.0 this hook exists in the provider API, although the current UTM provider still throws `UnsupportedOperationException` because automatic shutdown has not yet been implemented.

---

# 8. Provider display name and configuration

## `displayName()`

Return a short human-readable product name:

```java
@Override
public String displayName() {
    return "libvirt";
}
```

or:

```java
return "VirtualBox";
```

The application uses this value in the UI.

## `configuration()`

Providers can expose their effective configuration as read-only values:

```java
@Override
public List<ConfigurationEntry> configuration() {
    return List.of(
            new ConfigurationEntry(
                    "connectionUri",
                    "config.libvirt.connectionUri",
                    connectionUri
            ),
            new ConfigurationEntry(
                    "cli",
                    "config.libvirt.cli",
                    executable.toString()
            )
    );
}
```

Each entry contains:

- `key`: stable internal key,
- `labelKey`: message-bundle key used by the UI,
- `value`: effective value shown to the user.

Add corresponding labels to the message bundles:

```text
src/main/resources/messages.properties
src/main/resources/messages_de.properties
src/main/resources/messages_es.properties
```

Do not expose:

- passwords,
- access tokens,
- private keys,
- credentials,
- or other secrets through `configuration()`.

---

# 9. Provider-specific configuration

Provider selection is configured under `vm.providers` (see section 2). Each provider's settings live in its own namespace, for example `vm.utm.*`.

UTM-specific configuration currently lives in `VmProperties.Utm`. A new provider normally adds its own `@ConfigurationProperties` record under its own prefix, and should not add fields to `VmProperties`.

Example:

```java
@ConfigurationProperties("vm.libvirt")
public record LibvirtProperties(
        String uri,
        Path cli
) {
}
```

The exact structure is provider-specific.

A provider implementation must not require its settings when it is disabled. Annotate it with `@ConditionalOnVmProvider` so it is only created when enabled, as shown in section 11. This is what allows a QEMU-only installation to start without any `vm.utm.*` values.

Also update:

```text
application.properties.example
```

Example:

```properties
vm.providers=libvirt
vm.libvirt.uri=qemu:///system
vm.libvirt.cli=/usr/bin/virsh
```

Never commit a real machine-specific `application.properties`.

---

# 10. Recommended package structure

Keep provider-specific implementation details below the provider package.

For example:

```text
src/main/java/de/fjordkommission/utmbackup/provider/
├── VmProvider.java
├── VmProviderRegistry.java
├── utm/
│   └── UtmVmProvider.java
└── libvirt/
    ├── LibvirtVmProvider.java
    ├── LibvirtCommandRunner.java        # optional
    └── LibvirtOutputParser.java         # optional
```

Only create helper classes when they make the implementation clearer or easier to test.

A simple provider does not need an additional service layer merely for architectural symmetry.

---

# 11. Minimal provider skeleton

```java
package de.fjordkommission.utmbackup.provider.example;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.VmProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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
        return "Example Hypervisor";
    }

    @Override
    public List<ConfigurationEntry> configuration() {
        return List.of();
    }

    @Override
    public List<LocalVm> findAll() {
        // Discover VMs and return their stable IDs,
        // display names and complete backup sources.
        return List.of();
    }

    @Override
    public Map<String, VmStatus> findStatuses() {
        // Translate provider-specific runtime states
        // into RUNNING, STOPPED or UNKNOWN.
        return Map.of();
    }

    @Override
    public void stop(LocalVm vm) {
        // Request a clean provider-specific shutdown.
    }
}
```

The `@Profile("!test")` pattern used by the UTM provider prevents a real hypervisor integration from being activated during integration tests.

---

# 12. Step-by-step recipe

## Step 1 — Understand the hypervisor's storage model

Before writing Java code, identify:

- how VMs are discovered,
- which identifier remains stable,
- which files are needed for restore,
- how runtime state is queried,
- how a clean shutdown is requested,
- whether discovery and state lookup require a CLI, filesystem scan, socket, or API.

Avoid starting with UI changes.

---

## Step 2 — Define configuration

Add only the provider-specific settings actually required.

Typical settings include:

- VM/configuration directory,
- CLI executable,
- connection URI,
- package suffix,
- socket path.

Add safe example values to `application.properties.example`.

---

## Step 3 — Implement `VmProvider`

Create one Spring component implementing `VmProvider`.

At minimum implement:

```text
providerId()
displayName()
configuration()
findAll()
findStatuses()
stop()
```

Keep hypervisor-specific code inside the provider package.

---

## Step 4 — Return correct `LocalVm` instances

For every discovered VM verify:

- ID is stable,
- name is correct,
- backup sources exist,
- backup sources contain everything required for restore.

This is the most important provider responsibility.

The generic backup engine copies exactly what the provider exposes.

---

## Step 5 — Map runtime states conservatively

Explicitly map hypervisor states to:

```text
RUNNING
STOPPED
UNKNOWN
```

Only use `STOPPED` when the hypervisor clearly reports that state.

Unexpected CLI output, timeouts, parse failures or unavailable APIs should normally result in `UNKNOWN`.

---

## Step 6 — Register the provider

Normally no manual registry change is required.

Annotate the implementation as a Spring component, with its id in the activation annotation:

```java
@Component
@Profile("!test")
@ConditionalOnVmProvider("example")
```

Then configure:

```properties
vm.providers=example
```

`VmProviderRegistry` will select the provider whose `providerId()` matches the configured value.

---

## Step 7 — Add configuration labels

If `configuration()` returns provider-specific fields, add their `labelKey` values to all supported message bundles.

Verify the values appear correctly in the configuration dialog.

---

## Step 8 — Add tests

Do not test against real production VMs.

Prefer:

- mocks,
- temporary directories,
- fixture output from the hypervisor CLI,
- fake executables or process abstractions where useful.

At minimum test:

### Discovery

- normal VM discovery,
- stable VM IDs,
- correct display names,
- correct backup sources,
- unavailable VM location,
- malformed or unexpected provider data.

### State mapping

- stopped state,
- running state,
- transitional states,
- unknown state,
- command failure,
- timeout,
- malformed output.

### Provider registration

Verify that the configured provider ID selects the correct provider.

### Safety

Verify that failure to determine state never results in `STOPPED`.

---

# 13. Integration-test support

The project contains a `TestVmProvider` under:

```text
src/test/java/de/fjordkommission/utmbackup/provider/
```

It is active under the Spring `test` profile.

Its purpose is to keep integration tests independent of:

- UTM,
- any other installed hypervisor,
- and real local VM files.

New provider implementations should preserve this separation.

Do not make general integration tests depend on a locally installed hypervisor.

Provider-specific integration tests that require the real hypervisor should be clearly separated and should not be required by the normal CI build unless the CI environment explicitly supports them.

---

# 14. Manual smoke test

After unit tests pass, perform a manual smoke test with non-critical VMs.

Recommended sequence:

1. configure the new provider,
2. start the application,
3. verify the provider name in the UI,
4. verify all expected VMs are listed,
5. verify VM names and IDs remain stable after restart,
6. verify running/stopped states,
7. verify a running VM cannot be backed up,
8. verify a stopped test VM can be backed up,
9. verify the resulting backup contains every required source,
10. verify the VM can be restored independently from that backup where practical.

Never use the first provider test against the only copy of important VM data.

---

# 15. Code conventions

Contributions should follow the existing project conventions.

## Language and platform

- Java 21
- Spring Boot patterns already used by the project
- Maven build

## Source style

- keep changes focused,
- avoid unrelated refactoring,
- prefer straightforward code over unnecessary abstractions,
- keep provider-specific code isolated,
- preserve existing backup safety behavior.

## Comments and documentation

Write:

- source comments,
- JavaDoc,
- technical documentation,
- commit messages

in **English**.

Comments should be concise.

Explain:

- what a class or method does,
- and why a non-obvious implementation choice matters.

Avoid comments that merely repeat the code.

## Configuration

- never commit real `application.properties`,
- update `application.properties.example`,
- do not commit machine-specific paths,
- do not expose credentials.

---

# 16. Quality checks

Run the complete Maven verification before submitting a pull request:

```bash
mvn clean verify
```

This runs the test suite and generates the JaCoCo coverage report.

The project uses:

- JUnit 5,
- Mockito,
- JaCoCo,
- SonarQube,
- GitHub Actions.

The normal CI workflow also executes:

```bash
mvn -B clean verify
```

New provider behavior should include focused tests where practical.

Avoid adding tests purely to increase a coverage percentage; tests should exercise meaningful provider behavior and safety decisions.

---

## Optional local SonarQube analysis

When a contributor has access to a SonarQube server, the Maven scanner can be used after the build.

The exact server URL and token are environment-specific and should never be committed.

Typical form:

```bash
mvn clean verify sonar:sonar \
  -Dsonar.projectKey=utm-backup-manager \
  -Dsonar.host.url=<sonarqube-url> \
  -Dsonar.token="$SONAR_TOKEN"
```

Review:

- new issues,
- new-code coverage,
- duplicated code,
- reliability findings,
- security findings.

---

# 17. Pull-request checklist

Before opening a pull request for a new provider, verify:

- [ ] the provider has a unique stable `providerId()`
- [ ] the provider is registered as a Spring component
- [ ] `vm.providers=<id>` activates it, and the provider starts without its settings when disabled
- [ ] `findAll()` returns stable VM IDs
- [ ] all required restore sources are present in `backupSources`
- [ ] `findStatuses()` never reports uncertain state as `STOPPED`
- [ ] `findStatuses()` keys are the `id` values returned by `findAll()`
- [ ] provider configuration is documented
- [ ] `application.properties.example` is updated
- [ ] message keys are added for displayed configuration values
- [ ] unit tests cover discovery and state mapping
- [ ] no tests depend on real production VM data
- [ ] `mvn clean verify` passes
- [ ] documentation is updated
- [ ] no credentials or machine-specific configuration are committed

---

# 18. What usually does **not** need a new implementation

A new hypervisor provider normally does **not** need to replace or duplicate:

```text
BackupScanner
VmInventory
CreateBackupService
BackupJobService
RetentionService
RetentionCleanupJobService
BackupService
VmChangeStatusService
VmSizeService
StableRepository
VmStateRepository
SettingsRepository
```

These components operate on the generic `LocalVm` model, the canonical `VmId`, and the provider abstraction. Only `VmInventory` calls provider methods. Other services receive the aggregated inventory.

If a new provider appears to require changes throughout these services, first check whether the provider contract can be extended cleanly instead.

For larger changes to the provider SPI, open an issue and discuss the design before implementing a broad rewrite.

---

# 19. Current provider API flow

The main runtime flow is:

```text
application.properties
        |
        | vm.providers=<id> (or deprecated vm.provider, else utm)
        v
VmProviderRegistry  (enabledProviders, in configured order)
        |
        v
VmInventory  (one findAll() and one findStatuses() per provider and build)
        |
        v
VmProvider
   |          |
   |          +--> findStatuses()      keyed by local id
   |
   +--> findAll()
            |
            v
         LocalVm
            |
            +--> id
            +--> name
            +--> backupSources
                    |
                    v
          generic backup services
```

The provider is deliberately a thin adapter between a hypervisor and the generic backup system.

That is the architectural principle to preserve when adding another virtualization platform.

---

# 20. Suggested first contribution

For a new hypervisor, a good first pull request should ideally contain:

1. provider-specific configuration,
2. one `VmProvider` implementation,
3. tests for discovery and state mapping,
4. updates to `application.properties.example`,
5. message-bundle labels,
6. a short README section describing prerequisites and configuration.

Automatic shutdown support can be added in the same contribution if it is reliable and safe, but it is not necessary to duplicate unrelated backup functionality.

