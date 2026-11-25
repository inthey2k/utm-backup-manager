package de.fjordkommission.utmbackup.model;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VmIconTest {

    @Test
    void customImageHasNoInitialsAndIsMarkedAsCustom() {
        VmIcon icon = VmIcon.customImage();

        assertTrue(icon.custom());
        assertEquals("", icon.initials());
    }

    @Test
    void singleWordContributesItsFirstTwoCharacters() {
        VmIcon icon = VmIcon.fallback("Linux");

        assertFalse(icon.custom());
        assertEquals("LI", icon.initials());
    }

    @Test
    void wordsContributeTheirFirstLettersAndSeparatorsAreIgnored() {
        assertEquals("AL", VmIcon.fallback("Arch Linux 02.27").initials());
        assertEquals("W2", VmIcon.fallback("Windows11 26H2").initials());
        assertEquals("ÉV", VmIcon.fallback("élan_vital").initials());
    }

    @Test
    void blankNameFallsBackToAQuestionMark() {
        assertEquals("?", VmIcon.fallback("   ").initials());
        assertEquals("?", VmIcon.fallback(null).initials());
    }

    @Test
    void colourIsStableAndWithinThePalette() {
        for (String name : new String[] {"Linux", "Windows11 26H2", "ArchLinux", "x"}) {
            int color = VmIcon.fallback(name).colorIndex();

            assertTrue(color >= 0 && color < VmIcon.COLOR_COUNT, name);
            assertEquals(color, VmIcon.fallback(name).colorIndex(), name);
        }
    }

    @Test
    void colourFollowsTheJavaStringHashSoItDoesNotChangeBetweenRuns() {
        // String.hashCode() is specified by Java, so this value holds on every JVM.
        String name = "Linux";

        assertEquals(
                Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), VmIcon.COLOR_COUNT),
                VmIcon.fallback(name).colorIndex()
        );
    }
}
