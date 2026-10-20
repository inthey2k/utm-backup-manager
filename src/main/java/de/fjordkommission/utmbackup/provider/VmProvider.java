package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;

import java.util.List;
import java.util.Map;

/**
 * Integrates a VM hypervisor with the backup manager.
 *
 * A provider is responsible only for discovering VMs, determining their
 * running state and requesting a clean shutdown.
 *
 * Backup creation, retention, metadata and change tracking are handled by
 * the backup manager itself.
 *
 * Implementations should expose all files and directories required to restore
 * a VM through the LocalVm backup sources.
 */
public interface VmProvider {

    /**
     * Returns the unique configuration identifier of this provider.
     * The value is used by the vm.provider application property.
     */
    String providerId();

    /**
     * Returns the human-readable name of this provider.
     */
    String displayName();


    /**
     * Returns provider-specific configuration values for display in the UI.
     */
    List<ConfigurationEntry> configuration();

    /**
     * Returns all locally available VMs managed by this provider.
     */
    List<LocalVm> findAll();

    /**
     * Returns the runtime states of the locally available VMs.
     *
     * The map is keyed by the provider-local {@link LocalVm#id()}, never by the
     * display name. Missing entries are treated as UNKNOWN. A provider must
     * report UNKNOWN, or omit the VM, whenever its state cannot be matched to
     * exactly one local VM. Providers should retrieve multiple states in a single
     * operation where supported by the underlying hypervisor, and must not throw
     * because of ambiguous or unexpected runtime data.
     */
    Map<String, VmStatus> findStatuses();

    /**
     * Requests a clean shutdown of a VM.
     *
     * The method only initiates the shutdown. Waiting for the VM to stop,
     * including timeout handling, is the responsibility of the backup manager.
     */
    void stop(LocalVm vm);

    /**
     * Representation of provider-specific configurations, to be
     * handed over to ApplicationConfigurationView
     * */
    record ConfigurationEntry(
            String key,
            String labelKey,
            String value
    ) {
    }

}