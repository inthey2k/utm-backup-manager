package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.provider.qemu.QemuProperties;
import de.fjordkommission.utmbackup.provider.qemu.QemuVmProvider;
import de.fjordkommission.utmbackup.provider.utm.UtmPackageIdentity;
import de.fjordkommission.utmbackup.provider.utm.UtmProperties;
import de.fjordkommission.utmbackup.provider.utm.UtmVmProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stable identity is part of the provider contract. These tests check that every provider
 * states its rule, that the rule is registered for attribution of backups, that the rule
 * is the one the provider instance uses, and that rules never answer for another provider.
 */
class VmProviderIdentityContractTest {

    private static final String UUID = "6C1F2A3B-0000-4000-8000-00000000E001";
    private static final String QEMU_ID = "3f1c0a2e-0000-4000-8000-00000000e002";

    @TempDir
    Path tempDir;

    @Test
    void stableIdentityIsAnAbstractMethodSoEveryProviderMustDecide() throws NoSuchMethodException {
        assertTrue(Modifier.isAbstract(VmProvider.class.getMethod("stableIdentity").getModifiers()),
                "VmProvider.stableIdentity() must be abstract: a provider without a decision must not compile");
    }

    @Test
    void everyProductionProviderIsRegisteredInTheIdentityCatalog() throws Exception {
        Set<String> providerIds = productionProviderIds();

        assertFalse(providerIds.isEmpty(), "no production providers were found");
        assertEquals(providerIds,
                StableIdentityRules.RULES.stream().map(StableIdentityRule::providerId).collect(Collectors.toSet()),
                "every provider class must have exactly one rule in StableIdentityRules");
        assertEquals(StableIdentityRules.RULES.size(),
                StableIdentityRules.RULES.stream().map(StableIdentityRule::providerId).distinct().count(),
                "one rule per provider id");
    }

    @Test
    void theRuleOfEachProviderInstanceIsTheCatalogRule() throws IOException {
        UtmVmProvider utm = new UtmVmProvider(new UtmProperties(tempDir, ".utm", tempDir.resolve("utmctl")));
        QemuVmProvider qemu = new QemuVmProvider(new QemuProperties(tempDir));

        assertSame(StableIdentityRules.forProvider("utm").orElseThrow(), utm.stableIdentity());
        assertSame(StableIdentityRules.forProvider("qemu").orElseThrow(), qemu.stableIdentity());
        assertEquals("utm", utm.stableIdentity().providerId());
        assertEquals("qemu", qemu.stableIdentity().providerId());
    }

    @Test
    void aRuleAnswersOnlyForItsOwnProvider() throws IOException {
        Path vm = Files.createDirectories(tempDir.resolve("Both.utm"));
        Files.writeString(vm.resolve("config.plist"), plist(UUID));
        Files.writeString(vm.resolve(VmIdentitySidecar.FILE_NAME),
                "format=1\nprovider=qemu\nid=" + QEMU_ID + "\n");

        assertEquals(Optional.of(UUID), StableIdentityRules.ofPackage("utm", vm).map(i -> i.id()),
                "UTM reads its native UUID");
        assertEquals(Optional.of(QEMU_ID), StableIdentityRules.ofPackage("qemu", vm).map(i -> i.id()),
                "QEMU reads only its own sidecar");
        assertEquals(Optional.empty(), StableIdentityRules.ofPackage("utm", tempDir.resolve("none")));
    }

    @Test
    void unknownProviderHasNoRuleAndNeverFallsBackToAnyIdentity() throws IOException {
        Path vm = Files.createDirectories(tempDir.resolve("Foreign"));
        Files.writeString(vm.resolve("config.plist"), plist(UUID));

        assertEquals(Optional.empty(), StableIdentityRules.forProvider("vmware"));
        assertEquals(Optional.empty(), StableIdentityRules.ofPackage("vmware", vm),
                "an unregistered provider must not be read through another provider's rule");
        assertEquals(Optional.empty(), StableIdentityRules.ofContainer("vmware", tempDir));
    }

    /** Provider ids of the concrete provider classes in the main source tree. */
    private static Set<String> productionProviderIds() throws Exception {
        Path classes = Path.of(VmProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Assumptions.assumeTrue(Files.isDirectory(classes), "providers are not in a class directory");
        Path packageDirectory = classes.resolve("de/fjordkommission/utmbackup/provider");

        List<String> names;

        try (Stream<Path> files = Files.walk(packageDirectory)) {
            names = files
                    .filter(file -> file.toString().endsWith(".class") && !file.toString().contains("$"))
                    .map(file -> classes.relativize(file).toString()
                            .replace('/', '.')
                            .replace(".class", ""))
                    .toList();
        }

        ClassLoader loader = VmProvider.class.getClassLoader();
        Set<String> ids = new java.util.HashSet<>();

        for (String name : names) {
            Class<?> type = Class.forName(name, false, loader);

            if (VmProvider.class.isAssignableFrom(type)
                    && !type.isInterface()
                    && !Modifier.isAbstract(type.getModifiers())) {
                Field id = type.getField("PROVIDER_ID");
                ids.add((String) id.get(null));
            }
        }

        return ids;
    }

    private static String plist(String uuid) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict><key>UUID</key><string>%s</string></dict></plist>
                """.formatted(uuid);
    }
}
