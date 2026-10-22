package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;

import java.util.List;
import java.util.Map;

/**
 * Minimal provider with a configurable id, used to test provider registration
 * and selection without any hypervisor.
 */
public class StubVmProvider implements VmProvider {

    private final String providerId;

    public StubVmProvider(String providerId) {
        this.providerId = providerId;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public String displayName() {
        return "Stub " + providerId;
    }

    @Override
    public List<ConfigurationEntry> configuration() {
        return List.of();
    }

    @Override
    public List<LocalVm> findAll() {
        return List.of();
    }

    @Override
    public Map<String, VmStatus> findStatuses() {
        return Map.of();
    }

    @Override
    public void stop(LocalVm vm) {
        // Not used by these tests.
    }
}
