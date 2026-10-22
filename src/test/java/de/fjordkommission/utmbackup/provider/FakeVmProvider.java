package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configurable provider for aggregation tests. It can fail on demand and counts
 * how often each query is made, so tests can check the once-per-build contract.
 */
public class FakeVmProvider implements VmProvider {

    private final String providerId;
    private final String displayName;
    private final List<LocalVm> vms;
    private final Map<String, VmStatus> statuses;
    private final RuntimeException listFailure;
    private final RuntimeException statusFailure;

    private final AtomicInteger listCalls = new AtomicInteger();
    private final AtomicInteger statusCalls = new AtomicInteger();

    private FakeVmProvider(
            String providerId,
            String displayName,
            List<LocalVm> vms,
            Map<String, VmStatus> statuses,
            RuntimeException listFailure,
            RuntimeException statusFailure
    ) {
        this.providerId = providerId;
        this.displayName = displayName;
        this.vms = vms;
        this.statuses = statuses;
        this.listFailure = listFailure;
        this.statusFailure = statusFailure;
    }

    public static FakeVmProvider of(
            String providerId,
            List<LocalVm> vms,
            Map<String, VmStatus> statuses
    ) {
        return new FakeVmProvider(
                providerId,
                providerId.toUpperCase() + " (fake)",
                vms,
                statuses,
                null,
                null
        );
    }

    public static FakeVmProvider listingFails(String providerId, RuntimeException failure) {
        return new FakeVmProvider(providerId, providerId + " (fake)", List.of(), Map.of(), failure, null);
    }

    public static FakeVmProvider statusFails(
            String providerId,
            List<LocalVm> vms,
            RuntimeException failure
    ) {
        return new FakeVmProvider(providerId, providerId + " (fake)", vms, Map.of(), null, failure);
    }

    public int listCalls() {
        return listCalls.get();
    }

    public int statusCalls() {
        return statusCalls.get();
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public List<ConfigurationEntry> configuration() {
        return List.of();
    }

    @Override
    public List<LocalVm> findAll() {
        listCalls.incrementAndGet();

        if (listFailure != null) {
            throw listFailure;
        }

        return vms;
    }

    @Override
    public Map<String, VmStatus> findStatuses() {
        statusCalls.incrementAndGet();

        if (statusFailure != null) {
            throw statusFailure;
        }

        return statuses;
    }

    @Override
    public void stop(LocalVm vm) {
        throw new UnsupportedOperationException("Not used by aggregation tests");
    }
}
