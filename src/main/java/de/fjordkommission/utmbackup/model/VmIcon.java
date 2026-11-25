package de.fjordkommission.utmbackup.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Icon metadata of a VM for the overview. An image icon is served from the provider's image
 * file, whether it is custom or built-in. Every other VM uses a generated fallback: a coloured
 * circle with initials derived from the display name. The fallback is the same size as a real
 * icon, so both variants lay out identically.
 *
 * @param image      true when the provider supplies an image file for this VM
 * @param initials   the initials of the fallback icon, empty for an image icon
 * @param colorIndex the fallback colour, between 0 and {@link #COLOR_COUNT} - 1
 */
public record VmIcon(boolean image, String initials, int colorIndex) {

    /** Number of fallback colours. The stylesheet defines one class per colour. */
    public static final int COLOR_COUNT = 6;

    private static final String UNKNOWN_INITIALS = "?";
    private static final int MAX_INITIALS = 2;

    public VmIcon {
        initials = initials == null ? "" : initials;
    }

    /** The icon of a VM whose provider supplies an image file. */
    public static VmIcon withImage() {
        return new VmIcon(true, "", 0);
    }

    /**
     * The generated fallback. Initials come from the display name and the colour from its
     * {@link String#hashCode()}, which is specified by Java and therefore stable across runs.
     */
    public static VmIcon fallback(String displayName) {
        String name = displayName == null ? "" : displayName.trim();

        return new VmIcon(
                false,
                initialsOf(name),
                Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), COLOR_COUNT)
        );
    }

    /**
     * Uses the first letter of the first two words, or the first two characters of a single
     * word. Other characters, such as separators, are ignored when splitting words.
     */
    static String initialsOf(String name) {
        List<String> words = Arrays.stream(name.split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isEmpty())
                .toList();

        if (words.isEmpty()) {
            return UNKNOWN_INITIALS;
        }

        if (words.size() == 1) {
            return leadingUpperCase(words.getFirst(), MAX_INITIALS);
        }

        return words.stream()
                .limit(MAX_INITIALS)
                .map(word -> leadingUpperCase(word, 1))
                .collect(Collectors.joining());
    }

    /** The first {@code count} characters of a word in upper case, respecting code points. */
    private static String leadingUpperCase(String word, int count) {
        int[] codePoints = word.codePoints()
                .limit(count)
                .map(Character::toUpperCase)
                .toArray();

        return new String(codePoints, 0, codePoints.length);
    }
}
