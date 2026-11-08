package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.provider.qemu.QemuVmProvider;
import de.fjordkommission.utmbackup.provider.utm.UtmVmProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The stable-identity rules of all known providers, for attribution of backups whose
 * provider may not be enabled.
 *
 * <p>Each provider declares its rule through {@link VmProvider#stableIdentity()}, and the
 * rule is listed here. {@code VmProviderIdentityContractTest} fails the build when a
 * provider class is missing from this list or its rule differs, so a new provider cannot
 * be added without an identity decision. Unknown provider ids have no rule. Their
 * backups keep the id they were stored with, and nothing is guessed.
 */
public final class StableIdentityRules {

    /** One rule per provider implementation. */
    static final List<StableIdentityRule> RULES = List.of(
            UtmVmProvider.STABLE_IDENTITY,
            QemuVmProvider.STABLE_IDENTITY
    );

    private StableIdentityRules() {
    }

    /** The rule of a provider id, if the provider is known. */
    public static Optional<StableIdentityRule> forProvider(String providerId) {
        return RULES.stream()
                .filter(rule -> rule.providerId().equals(providerId))
                .findFirst();
    }

    /** Identity of a VM package under the rule of its provider. */
    public static Optional<StableIdentityRule.Identity> ofPackage(String providerId, Path packageDirectory) {
        return forProvider(providerId).flatMap(rule -> rule.ofPackage(packageDirectory));
    }

    /** Identity of a container's VM package under the rule of its provider. */
    public static Optional<StableIdentityRule.Identity> ofContainer(String providerId, Path container) {
        return forProvider(providerId).flatMap(rule -> rule.ofContainer(container));
    }
}
