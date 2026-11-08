package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.storage.VmIdentityRegistry.IdentitySource;
import de.fjordkommission.utmbackup.storage.VmIdentityRegistry.Observation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Identity resolution rules of {@link VmIdentityRegistry}: provider-native ids, aliases,
 * renames, provider isolation, generated ids and idempotent merging.
 */
class VmIdentityRegistryTest {

    private static final String UUID = "6C1F2A3B-0000-4000-8000-000000000001";

    private static Observation native_(String locator, String name, boolean current) {
        return new Observation(new VmId("utm", UUID), IdentitySource.PROVIDER_NATIVE, locator, name, current);
    }

    @Test
    void providerNativeIdResolvesToItself() {
        VmIdentityRegistry registry = VmIdentityRegistry.empty()
                .observe(native_("Linux Mint 27.utm", "Linux Mint 27", true));

        assertEquals(Optional.of(new VmId("utm", UUID)),
                registry.canonicalOf(new VmId("utm", UUID)));
    }

    @Test
    void historicalPackageNameResolvesToTheStableId() {
        VmIdentityRegistry registry = VmIdentityRegistry.empty()
                .observe(native_("Linux Mint 23.utm", "Linux Mint 23", false));

        assertEquals(Optional.of(new VmId("utm", UUID)),
                registry.canonicalOf(new VmId("utm", "Linux Mint 23.utm")));
    }

    @Test
    void renameKeepsTheIdentityAndRecordsTheOldNameAsHistory() {
        VmIdentityRegistry registry = VmIdentityRegistry.empty()
                .observe(native_("Linux Mint 23.utm", "Linux Mint 23", true))
                .observe(native_("Linux Mint 27.utm", "Linux Mint 27", true));

        assertEquals(1, registry.identities().size(), "a rename must not create a second VM");

        VmIdentityRegistry.Entry entry = registry.identities().getFirst();
        assertEquals("Linux Mint 27.utm", entry.currentLocator());
        assertEquals("Linux Mint 27", entry.currentName());
        assertEquals(List.of("Linux Mint 23"), entry.historicalNames());
        assertEquals(List.of("Linux Mint 23.utm", "Linux Mint 27.utm"), entry.aliases());
        assertEquals(Optional.of(new VmId("utm", UUID)),
                registry.canonicalOf(new VmId("utm", "Linux Mint 23.utm")));
    }

    @Test
    void providersAreIsolatedSoEqualLocalIdsDoNotCollide() {
        VmIdentityRegistry registry = VmIdentityRegistry.empty()
                .observe(native_("Shared.utm", "Shared", true))
                .observe(new Observation(new VmId("qemu", "Shared"), IdentitySource.GENERATED,
                        "Shared", "Shared", true));

        assertEquals(2, registry.identities().size());
        assertEquals(Optional.of(new VmId("qemu", "Shared")),
                registry.canonicalOf(new VmId("qemu", "Shared")));
        assertEquals(Optional.of(new VmId("utm", UUID)),
                registry.canonicalOf(new VmId("utm", "Shared.utm")));
        assertEquals(Optional.empty(), registry.canonicalOf(new VmId("utm", "Shared")),
                "a qemu entry must never answer for a utm id");
    }

    @Test
    void aliasClaimedByTwoIdentitiesResolvesToNothing() {
        VmIdentityRegistry registry = VmIdentityRegistry.empty()
                .observe(new Observation(new VmId("utm", UUID), IdentitySource.PROVIDER_NATIVE,
                        "Reused.utm", "Reused", true))
                .observe(new Observation(new VmId("utm", "6C1F2A3B-0000-4000-8000-000000000002"),
                        IdentitySource.PROVIDER_NATIVE, "Reused.utm", "Reused", true));

        assertEquals(Optional.empty(), registry.canonicalOf(new VmId("utm", "Reused.utm")),
                "an ambiguous alias must not be guessed");
    }

    @Test
    void generatedIdentityLinksEveryLocatorItHasHadWithoutANewVm() {
        VmId generated = new VmId("qemu", "3f1c0a2e-0000-4000-8000-00000000c001");
        VmIdentityRegistry registry = VmIdentityRegistry.empty()
                .observe(new Observation(generated, IdentitySource.GENERATED, "Ubuntu", "Ubuntu", true))
                .observe(new Observation(generated, IdentitySource.GENERATED, "Ubuntu 26", "Ubuntu 26", true));

        assertEquals(1, registry.identities().size());
        assertEquals(Optional.of(generated), registry.canonicalOf(new VmId("qemu", "Ubuntu")),
                "a historical directory name still resolves to the generated id");
        assertEquals(Optional.of(generated), registry.canonicalOf(new VmId("qemu", "Ubuntu 26")));
        assertEquals(Optional.empty(), registry.canonicalOf(new VmId("utm", "Ubuntu")),
                "a generated qemu identity never answers for utm");
    }

    @Test
    void observingTheSameFactsTwiceChangesNothing() {
        VmIdentityRegistry once = VmIdentityRegistry.empty()
                .observe(native_("Linux Mint 23.utm", "Linux Mint 23", true));
        VmIdentityRegistry twice = once.observe(native_("Linux Mint 23.utm", "Linux Mint 23", true));

        assertEquals(once, twice);
    }

    @Test
    void historicalObservationsDoNotOverwriteTheCurrentName() {
        VmIdentityRegistry registry = VmIdentityRegistry.empty()
                .observe(native_("Linux Mint 27.utm", "Linux Mint 27", true))
                .observe(native_("Linux Mint 23.utm", "Linux Mint 23", false));

        VmIdentityRegistry.Entry entry = registry.identities().getFirst();
        assertEquals("Linux Mint 27", entry.currentName());
        assertEquals("Linux Mint 27.utm", entry.currentLocator());
        assertEquals(List.of("Linux Mint 23"), entry.historicalNames());
    }

    @Test
    void resultDoesNotDependOnObservationOrder() {
        Observation old = native_("Linux Mint 23.utm", "Linux Mint 23", false);
        Observation current = native_("Linux Mint 27.utm", "Linux Mint 27", true);

        assertEquals(
                VmIdentityRegistry.empty().observeAll(List.of(old, current)),
                VmIdentityRegistry.empty().observeAll(List.of(current, old))
        );
    }

    @Test
    void aliasesOfReturnsEveryLocalIdOfTheVmExceptTheStableOne() {
        VmIdentityRegistry registry = VmIdentityRegistry.empty()
                .observe(native_("Linux Mint 23.utm", "Linux Mint 23", false))
                .observe(native_("Linux Mint 27.utm", "Linux Mint 27", true));

        assertEquals(
                List.of(new VmId("utm", "Linux Mint 23.utm"), new VmId("utm", "Linux Mint 27.utm")),
                registry.aliasesOf(new VmId("utm", UUID))
        );
        assertTrue(registry.aliasesOf(new VmId("utm", "unknown")).isEmpty());
    }
}
