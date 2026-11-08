package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The provider-independent identity registry stored as {@code VM-IDENTITIES.json} in
 * the backup root. It maps every known provider-local id (current locator, historical
 * package names) to one canonical stable id per VM.
 *
 * <p>The registry is immutable: every change returns a new instance, so callers can
 * compare the result with the previous state and skip writing when nothing changed.
 * Nothing in here touches backup data; it only records identities.
 *
 * <p>Resolution rules:
 * <ul>
 *   <li>A stable id resolves to itself.</li>
 *   <li>An alias resolves only if exactly one entry of the same provider claims it.
 *       Ambiguous aliases resolve to nothing, so the caller keeps the raw id.</li>
 *   <li>Providers are isolated: {@code utm:X} and {@code qemu:X} never match each other.</li>
 * </ul>
 *
 * @param schemaVersion format version of the file; only {@link #SCHEMA_VERSION} is supported
 * @param identities    one entry per canonical VM, sorted by provider and stable id
 */
public record VmIdentityRegistry(int schemaVersion, List<Entry> identities) {

    public static final int SCHEMA_VERSION = 1;

    public VmIdentityRegistry {
        identities = identities.stream()
                .sorted(Comparator.comparing(Entry::providerId).thenComparing(Entry::stableLocalId))
                .toList();
    }

    public static VmIdentityRegistry empty() {
        return new VmIdentityRegistry(SCHEMA_VERSION, List.of());
    }

    /**
     * Returns the canonical id for a provider-local id, or empty when the local id is
     * unknown or ambiguous.
     */
    public Optional<VmId> canonicalOf(VmId candidate) {
        for (Entry entry : identities) {
            if (entry.providerId().equals(candidate.providerId())
                    && entry.stableLocalId().equals(candidate.localId())) {
                return Optional.of(candidate);
            }
        }

        List<Entry> claimants = identities.stream()
                .filter(entry -> entry.providerId().equals(candidate.providerId()))
                .filter(entry -> entry.aliases().contains(candidate.localId()))
                .toList();

        return claimants.size() == 1
                ? Optional.of(new VmId(candidate.providerId(), claimants.getFirst().stableLocalId()))
                : Optional.empty();
    }

    /**
     * Returns the historical and current provider-local ids of a canonical VM, excluding the
     * stable id itself. Empty for unknown VMs.
     */
    public List<VmId> aliasesOf(VmId canonical) {
        return identities.stream()
                .filter(entry -> entry.providerId().equals(canonical.providerId())
                        && entry.stableLocalId().equals(canonical.localId()))
                .flatMap(entry -> entry.aliases().stream())
                .map(alias -> new VmId(canonical.providerId(), alias))
                .toList();
    }

    /**
     * Records that a provider-local id belongs to a canonical VM. A new VM gets an entry; a
     * known VM gets the locator added as an alias. When {@code current} is true the name and
     * locator become the current ones, and the previous name is kept as historical.
     */
    public VmIdentityRegistry observe(Observation observation) {
        VmId canonical = observation.canonical();
        List<Entry> updated = new ArrayList<>();
        boolean found = false;

        for (Entry entry : identities) {
            if (entry.providerId().equals(canonical.providerId())
                    && entry.stableLocalId().equals(canonical.localId())) {
                updated.add(merge(entry, observation));
                found = true;
            } else {
                updated.add(entry);
            }
        }

        if (!found) {
            updated.add(createEntry(observation));
        }

        return new VmIdentityRegistry(schemaVersion, updated);
    }

    /** Applies observations in order. */
    public VmIdentityRegistry observeAll(List<Observation> observations) {
        VmIdentityRegistry result = this;

        for (Observation observation : observations) {
            result = result.observe(observation);
        }

        return result;
    }

    private static Entry createEntry(Observation observation) {
        VmId canonical = observation.canonical();
        List<String> aliases = observation.locator().equals(canonical.localId())
                ? List.of()
                : List.of(observation.locator());

        return new Entry(
                canonical.providerId(),
                canonical.localId(),
                observation.source(),
                aliases,
                observation.locator(),
                observation.name(),
                List.of(),
                null
        );
    }

    private static Entry merge(Entry entry, Observation observation) {
        List<String> aliases = new ArrayList<>(entry.aliases());
        List<String> historical = new ArrayList<>(entry.historicalNames());
        String currentLocator = entry.currentLocator();
        String currentName = entry.currentName();

        if (!observation.locator().equals(entry.stableLocalId())) {
            aliases.add(observation.locator());
        }

        if (observation.current()) {
            if (currentName != null && !currentName.equals(observation.name())) {
                historical.add(currentName);
            }

            currentLocator = observation.locator();
            currentName = observation.name();
        } else if (!observation.name().equals(currentName)) {
            historical.add(observation.name());
        }

        historical.remove(currentName);

        return new Entry(
                entry.providerId(),
                entry.stableLocalId(),
                entry.source(),
                aliases,
                currentLocator,
                currentName,
                historical,
                entry.fingerprint()
        );
    }

    /** Where an identity came from. */
    public enum IdentitySource {
        /** The provider reports a stable id itself, such as the UTM UUID. */
        PROVIDER_NATIVE,
        /** Generated by the backup manager and persisted in this registry. */
        GENERATED
    }

    /**
     * One canonical VM.
     *
     * @param providerId       provider that owns the VM
     * @param stableLocalId    the canonical local id, never changed once recorded
     * @param source           how the stable id was obtained
     * @param aliases          every other provider-local id seen for this VM, such as old package names
     * @param currentLocator   provider-local locator seen most recently
     * @param currentName      display name seen most recently
     * @param historicalNames  earlier display names
     * @param fingerprint      optional provider-specific data; null when unused
     */
    public record Entry(
            String providerId,
            String stableLocalId,
            IdentitySource source,
            List<String> aliases,
            String currentLocator,
            String currentName,
            List<String> historicalNames,
            String fingerprint
    ) {

        /** Sorted and de-duplicated, so the file content does not depend on scan order. */
        public Entry {
            aliases = aliases.stream().distinct().sorted().toList();
            historicalNames = historicalNames.stream().distinct().sorted().toList();
        }
    }

    /**
     * A provider-local id seen during a scan.
     *
     * @param canonical stable id of the VM the locator belongs to
     * @param source    source of the stable id
     * @param locator   provider-local id as seen (package name, manifest id or stable id)
     * @param name      display name seen with the locator
     * @param current   true for a local VM that exists now; false for a historical backup
     */
    public record Observation(
            VmId canonical,
            IdentitySource source,
            String locator,
            String name,
            boolean current
    ) {
    }
}
