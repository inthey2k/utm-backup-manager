package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.ManagedVm;
import de.fjordkommission.utmbackup.model.ProviderSnapshot;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.VmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the complete inventory of local VMs from all enabled providers.
 *
 * This is the only generic service that calls {@link VmProvider} methods. Each
 * build calls {@code findAll()} and {@code findStatuses()} at most once per
 * enabled provider. A failing provider is recorded in its
 * {@link ProviderSnapshot} and does not affect the others.
 */
@Service
public class VmInventory {

    private final VmProviderRegistry registry;

    public VmInventory(VmProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * Queries every enabled provider once and returns the combined result.
     */
    public Inventory build() {
        List<ProviderSnapshot> snapshots = registry.enabledProviders().stream()
                .map(VmInventory::snapshot)
                .toList();

        List<ManagedVm> vms = new ArrayList<>();

        for (ProviderSnapshot snapshot : snapshots) {
            for (LocalVm localVm : snapshot.localVms()) {
                vms.add(new ManagedVm(
                        new VmId(snapshot.providerId(), localVm.id()),
                        snapshot.providerName(),
                        localVm,
                        snapshot.statuses().getOrDefault(localVm.id(), VmStatus.UNKNOWN)
                ));
            }
        }

        return new Inventory(snapshots, vms);
    }

    private static ProviderSnapshot snapshot(VmProvider provider) {
        String providerId = provider.providerId();
        List<String> errors = new ArrayList<>();
        String providerName = displayName(provider, errors);

        List<LocalVm> discovered;

        try {
            discovered = List.copyOf(provider.findAll());
        } catch (RuntimeException e) {
            errors.add("Could not list VMs: " + describe(e));
            return new ProviderSnapshot(
                    providerId,
                    providerName,
                    false,
                    List.of(),
                    Map.of(),
                    errors
            );
        }

        List<LocalVm> accepted = acceptUniqueIds(providerId, discovered, errors);

        Map<String, VmStatus> statuses;

        try {
            statuses = Map.copyOf(provider.findStatuses());
        } catch (RuntimeException e) {
            statuses = Map.of();
            errors.add("Could not determine VM states, so these VMs are shown as UNKNOWN: "
                    + describe(e));
        }

        return new ProviderSnapshot(
                providerId,
                providerName,
                true,
                accepted,
                statuses,
                errors
        );
    }

    /**
     * Falls back to the provider id when the display name cannot be read, so the
     * name query cannot break the inventory.
     */
    private static String displayName(VmProvider provider, List<String> errors) {
        try {
            String name = provider.displayName();
            return name == null || name.isBlank() ? provider.providerId() : name;
        } catch (RuntimeException e) {
            errors.add("Could not read the provider name, so its id is shown instead: " + describe(e));
            return provider.providerId();
        }
    }

    /**
     * Drops VMs without a usable id, and every VM whose id occurs more than once.
     * Duplicate ids cannot be told apart, so none of them may be backed up or deleted.
     */
    private static List<LocalVm> acceptUniqueIds(
            String providerId,
            List<LocalVm> discovered,
            List<String> errors
    ) {
        List<LocalVm> valid = new ArrayList<>();

        for (LocalVm vm : discovered) {
            if (vm.id() == null || vm.id().isBlank()) {
                errors.add("Ignored a VM without an id: " + vm.name());
            } else {
                valid.add(vm);
            }
        }

        Map<String, Long> counts = valid.stream()
                .collect(Collectors.groupingBy(LocalVm::id, HashMap::new, Collectors.counting()));

        Set<String> duplicates = counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        for (String duplicate : duplicates.stream().sorted().toList()) {
            errors.add("Duplicate VM id '" + duplicate + "' reported by provider '"
                    + providerId + "'; these VMs are not available until the provider reports unique ids");
        }

        return valid.stream()
                .filter(vm -> !duplicates.contains(vm.id()))
                .toList();
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : message;
    }

    /**
     * The complete VM inventory of one build.
     *
     * @param providers per-provider results, in configured order
     * @param vms       all VMs of all providers, in configured provider order
     */
    public record Inventory(List<ProviderSnapshot> providers, List<ManagedVm> vms) {

        public Inventory {
            providers = List.copyOf(providers);
            vms = List.copyOf(vms);
        }

        public Optional<ManagedVm> find(VmId id) {
            return vms.stream()
                    .filter(vm -> vm.id().equals(id))
                    .findFirst();
        }

        /**
         * Returns the display name of an enabled provider, if it is enabled.
         */
        public Optional<String> providerName(String providerId) {
            return providers.stream()
                    .filter(provider -> provider.providerId().equals(providerId))
                    .map(ProviderSnapshot::providerName)
                    .findFirst();
        }
    }
}
