package de.fjordkommission.utmbackup.provider;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Stable-identity rule of the test doubles. It reads no identity, so their VMs keep the
 * local ids they report. Tests that need a real rule use the production providers.
 */
final class NoStableIdentity implements StableIdentityRule {

    private final String providerId;

    NoStableIdentity(String providerId) {
        this.providerId = providerId;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Optional<Identity> ofPackage(Path packageDirectory) {
        return Optional.empty();
    }

    @Override
    public Optional<Identity> ofContainer(Path container) {
        return Optional.empty();
    }
}
