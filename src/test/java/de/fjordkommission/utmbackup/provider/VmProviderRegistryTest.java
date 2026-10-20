package de.fjordkommission.utmbackup.provider;

import de.fjordkommission.utmbackup.config.VmProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class VmProviderRegistryTest {

    private static VmProperties vmProviders(String... providers) {
        return new VmProperties(List.of(providers), null, null);
    }

    private static VmProperties legacyProvider(String provider) {
        return new VmProperties(null, provider, null);
    }

    @Test
    void enabledProviderIsReturned() {
        StubVmProvider utm = new StubVmProvider("utm");

        VmProviderRegistry registry = new VmProviderRegistry(
                List.of(utm),
                vmProviders("utm")
        );

        assertEquals(List.of(utm), registry.enabledProviders());
    }

    @Test
    void disabledImplementationsAreNotReturned() {
        StubVmProvider utm = new StubVmProvider("utm");
        StubVmProvider qemu = new StubVmProvider("qemu");

        VmProviderRegistry registry = new VmProviderRegistry(
                List.of(utm, qemu),
                vmProviders("qemu")
        );

        assertEquals(List.of(qemu), registry.enabledProviders());
    }

    @Test
    void enabledProvidersKeepConfiguredOrder() {
        StubVmProvider utm = new StubVmProvider("utm");
        StubVmProvider qemu = new StubVmProvider("qemu");

        VmProviderRegistry registry = new VmProviderRegistry(
                List.of(utm, qemu),
                vmProviders("qemu", "utm")
        );

        assertEquals(List.of(qemu, utm), registry.enabledProviders());
    }

    @Test
    void multipleEnabledProvidersAreAllReturned() {
        StubVmProvider utm = new StubVmProvider("utm");
        StubVmProvider qemu = new StubVmProvider("qemu");

        VmProviderRegistry registry = new VmProviderRegistry(
                List.of(utm, qemu),
                vmProviders("utm", "qemu")
        );

        assertEquals(List.of(utm, qemu), registry.enabledProviders());
    }

    @Test
    void legacyProviderPropertyStillSelectsItsProvider() {
        StubVmProvider utm = new StubVmProvider("utm");

        VmProviderRegistry registry = new VmProviderRegistry(
                List.of(utm),
                legacyProvider("UTM")
        );

        assertEquals(List.of(utm), registry.enabledProviders());
    }

    @Test
    void duplicateImplementationIdsAreRejected() {
        List<VmProvider> implementations = List.of(new StubVmProvider("utm"), new StubVmProvider("utm"));
        VmProperties properties = vmProviders("utm");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new VmProviderRegistry(implementations, properties)
        );

        assertTrue(exception.getMessage().contains("Duplicate VM provider implementations for id 'utm'"));
    }

    @Test
    void implementationIdsThatDifferOnlyInCaseAreDuplicates() {
        List<VmProvider> implementations = List.of(new StubVmProvider("UTM"), new StubVmProvider("utm"));
        VmProperties properties = vmProviders("utm");

        assertThrows(
                IllegalStateException.class,
                () -> new VmProviderRegistry(implementations, properties)
        );
    }

    @Test
    void invalidImplementationIdIsRejected() {
        List<VmProvider> implementations = List.of(new StubVmProvider("bad_id"));
        VmProperties properties = vmProviders("bad_id");

        assertThrows(
                IllegalStateException.class,
                () -> new VmProviderRegistry(implementations, properties)
        );
    }

    @Test
    void configuredIdWithoutImplementationFailsClearly() {
        List<VmProvider> implementations = List.of(new StubVmProvider("utm"));
        VmProperties properties = vmProviders("freebsd");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new VmProviderRegistry(implementations, properties)
        );

        assertTrue(exception.getMessage().contains("'freebsd' has no implementation"));
        assertTrue(exception.getMessage().contains("Available providers: utm"));
    }

    @Test
    void enabledProvidersListIsImmutable() {
        VmProviderRegistry registry = new VmProviderRegistry(
                List.of(new StubVmProvider("utm")),
                vmProviders("utm")
        );

        List<VmProvider> enabled = registry.enabledProviders();

        assertThrows(
                UnsupportedOperationException.class,
                enabled::clear
        );
    }

    /**
     * Guards the Phase 3 migration: production code must reach providers through
     * VmInventory. A registry-level "current" accessor would reintroduce the
     * single-provider assumption.
     */
    @Test
    void noProductionClassCallsRegistryCurrent() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<String> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        String source = readSource(path);
                        return source.contains("VmProviderRegistry") && source.contains(".current()");
                    })
                    .map(Path::toString)
                    .toList();

            assertTrue(offenders.isEmpty(), "Production code still uses current(): " + offenders);
        }
    }

    private static String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }
}
