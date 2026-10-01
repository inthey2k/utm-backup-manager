package de.fjordkommission.utmbackup.model;

/**
 * Describes a language for which a message bundle is available.
 */
public record AvailableLanguage(
        String code,
        String label
) {
}