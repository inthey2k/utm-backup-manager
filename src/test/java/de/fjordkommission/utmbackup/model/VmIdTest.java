package de.fjordkommission.utmbackup.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class VmIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"utm", "qemu", "a", "my-provider-2", "0"})
    void acceptsProviderIdsOfLowerCaseLettersDigitsAndHyphens(String providerId) {
        VmId id = new VmId(providerId, "local");

        assertEquals(providerId, id.providerId());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "UTM", "Utm", "qemu_x", "my provider", "a:b", "utm.x",
            "ü", " utm", "utm ", "utm/x", "utm__x"
    })
    void rejectsProviderIdsWithInvalidCharactersOrNull(String providerId) {
        assertInvalid(() -> new VmId(providerId, "local"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void rejectsBlankLocalIds(String localId) {
        assertInvalid(() -> new VmId("utm", localId));
    }

    @Test
    void serializesAsProviderIdColonLocalId() {
        assertEquals("utm:ArchLinux 02.27.utm", new VmId("utm", "ArchLinux 02.27.utm").toString());
        assertEquals("qemu:ArchLinux", new VmId("qemu", "ArchLinux").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "utm:ArchLinux 02.27.utm",
            "qemu:ArchLinux",
            "utm:Linux.utm",
            "qemu:name:with:colons",
            "utm:  leading and trailing spaces  ",
            "utm:Ünïcode Böx.utm"
    })
    void parseAndToStringRoundTrip(String serialized) {
        VmId parsed = VmId.parse(serialized);

        assertEquals(serialized, parsed.toString());
        assertEquals(parsed, VmId.parse(parsed.toString()));
    }

    @Test
    void parseSplitsOnTheFirstColonOnlyAndKeepsColonsInLocalId() {
        VmId id = VmId.parse("qemu:ArchLinux:x:y");

        assertEquals("qemu", id.providerId());
        assertEquals("ArchLinux:x:y", id.localId());
    }

    @Test
    void localIdMayContainSpacesDotsAndColonsWithoutLoss() {
        VmId id = new VmId("utm", "a b.c:d");

        assertEquals("a b.c:d", id.localId());
        assertEquals(id, VmId.parse(id.toString()));
    }

    @Test
    void equalComponentsProduceEqualValues() {
        assertEquals(new VmId("utm", "Linux.utm"), VmId.parse("utm:Linux.utm"));
        assertNotEquals(new VmId("utm", "Linux.utm"), new VmId("qemu", "Linux.utm"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"Linux.utm", ":Linux.utm", "UTM:Linux.utm", "utm:"})
    void parseRejectsValuesWithoutSeparatorOrWithInvalidParts(String value) {
        assertInvalid(() -> VmId.parse(value));
    }

    private static void assertInvalid(Executable executable) {
        assertThrows(IllegalArgumentException.class, executable);
    }
}
