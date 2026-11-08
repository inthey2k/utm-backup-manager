package de.fjordkommission.utmbackup.storage;

import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.storage.VmIdentityRegistry.IdentitySource;
import de.fjordkommission.utmbackup.storage.VmIdentityRegistry.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading and writing {@code VM-IDENTITIES.json}: a missing file is empty, a file that is
 * not usable is reported and left untouched, and writes round-trip exactly.
 */
class VmIdentityFileTest {

    @TempDir
    Path root;

    private VmIdentityRegistry sample() {
        return VmIdentityRegistry.empty().observe(new Observation(
                new VmId("utm", "6C1F2A3B-0000-4000-8000-000000000001"),
                IdentitySource.PROVIDER_NATIVE,
                "Linux Mint 23.utm",
                "Linux Mint 23",
                true));
    }

    @Test
    void missingFileIsReportedAsAbsent() {
        assertInstanceOf(VmIdentityFile.ReadResult.Absent.class, VmIdentityFile.read(root));
    }

    @Test
    void writtenRegistryReadsBackUnchanged() throws IOException {
        VmIdentityRegistry registry = sample();

        VmIdentityFile.write(root, registry);

        assertEquals(new VmIdentityFile.ReadResult.Loaded(registry), VmIdentityFile.read(root));
        assertTrue(Files.readString(root.resolve(VmIdentityFile.FILE_NAME)).contains("\"schemaVersion\""));
    }

    @Test
    void generatedIdentityPersistsAndIsFoundAgainAfterReload() throws IOException {
        VmId generated = new VmId("qemu", "3f1c0a2e-0000-4000-8000-00000000c001");
        VmIdentityFile.write(root, VmIdentityRegistry.empty().observe(new Observation(
                generated, IdentitySource.GENERATED, "Ubuntu", "Ubuntu", true)));

        VmIdentityFile.ReadResult.Loaded loaded = (VmIdentityFile.ReadResult.Loaded) VmIdentityFile.read(root);
        assertEquals(java.util.Optional.of(generated),
                loaded.registry().canonicalOf(new VmId("qemu", "Ubuntu")));
    }

    @Test
    void newerSchemaIsUnusableAndTheFileIsNotOverwritten() throws IOException {
        String newer = "{\"schemaVersion\":2,\"identities\":[]}";
        Path file = root.resolve(VmIdentityFile.FILE_NAME);
        Files.writeString(file, newer);

        VmIdentityFile.ReadResult result = VmIdentityFile.read(root);

        assertInstanceOf(VmIdentityFile.ReadResult.Unusable.class, result);
        assertEquals(newer, Files.readString(file), "reading must never change the file");
    }

    @Test
    void corruptFileIsUnusable() throws IOException {
        Files.writeString(root.resolve(VmIdentityFile.FILE_NAME), "{ this is not json");

        assertInstanceOf(VmIdentityFile.ReadResult.Unusable.class, VmIdentityFile.read(root));
    }

    @Test
    void unknownFieldsAreUnusableRatherThanSilentlyDropped() throws IOException {
        Files.writeString(root.resolve(VmIdentityFile.FILE_NAME),
                "{\"schemaVersion\":1,\"identities\":[],\"futureField\":true}");

        assertInstanceOf(VmIdentityFile.ReadResult.Unusable.class, VmIdentityFile.read(root),
                "a rewrite would drop the unknown field, so the file must be left alone");
    }

    @Test
    void duplicateIdentityIsUnusable() throws IOException {
        String entry = "{\"providerId\":\"utm\",\"stableLocalId\":\"X\",\"source\":\"PROVIDER_NATIVE\","
                + "\"aliases\":[],\"currentLocator\":\"X\",\"currentName\":\"X\",\"historicalNames\":[],"
                + "\"fingerprint\":null}";
        Files.writeString(root.resolve(VmIdentityFile.FILE_NAME),
                "{\"schemaVersion\":1,\"identities\":[" + entry + "," + entry + "]}");

        assertInstanceOf(VmIdentityFile.ReadResult.Unusable.class, VmIdentityFile.read(root));
    }

    @Test
    void writeLeavesNoTemporaryFilesBehind() throws IOException {
        VmIdentityFile.write(root, sample());
        VmIdentityFile.write(root, sample());

        try (var names = Files.list(root)) {
            assertEquals(1, names.count(), "only the registry file may remain");
        }
    }
}
