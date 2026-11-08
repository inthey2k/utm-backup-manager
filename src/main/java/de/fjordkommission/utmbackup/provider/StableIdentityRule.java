package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.storage.VmIdentityRegistry.IdentitySource;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A provider's rule for reading the stable identity that is stored with a VM package or
 * with a backup container.
 *
 * <p>Every {@link VmProvider} returns one rule from {@link VmProvider#stableIdentity()}. The
 * rule is stateless and does not need the provider to be enabled, so historical backups of a
 * disabled provider are still attributed. A rule answers "what identity does this
 * directory carry?", and must return empty rather than guess when it cannot tell.
 */
public interface StableIdentityRule {

    /** The provider this rule belongs to. Must equal the provider's {@code providerId()}. */
    String providerId();

    /**
     * The identity carried by a VM package directory: a current local VM, or a format-1
     * VM folder. Empty when the package carries no usable identity.
     */
    Optional<Identity> ofPackage(Path packageDirectory);

    /**
     * The identity carried by the VM package inside a format-2 container. Empty when the
     * container has no package, or its packages do not agree on one identity.
     */
    Optional<Identity> ofContainer(Path container);

    /**
     * A stable id and where it came from.
     *
     * @param id     the provider-local id to use as the canonical local id
     * @param source how the id was obtained
     */
    record Identity(String id, IdentitySource source) {
    }
}
