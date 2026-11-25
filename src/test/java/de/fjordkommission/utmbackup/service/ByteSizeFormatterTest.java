package de.fjordkommission.utmbackup.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ByteSizeFormatterTest {

    private final ByteSizeFormatter formatter = new ByteSizeFormatter();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void wholeGigabytesHaveNoDecimalPlace() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        assertEquals("117 GB", formatter.gigabytes(117_000_000_000L));
        assertEquals("48 GB", formatter.gigabytes(48_000_000_000L));
        assertEquals("0 GB", formatter.gigabytes(0L));
    }

    @Test
    void fractionsKeepAtMostOneDecimalPlace() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        assertEquals("1.2 GB", formatter.gigabytes(1_200_000_000L));
        assertEquals("0.4 GB", formatter.gigabytes(400_000_000L));
        assertEquals("1.2 GB", formatter.gigabytes(1_249_999_999L));
    }

    @Test
    void roundsHalfUpLikeTheBrowserFormatting() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        // The browser rounds halves away from zero, so 1.25 GB becomes 1.3 GB on both sides.
        assertEquals("1.3 GB", formatter.gigabytes(1_250_000_000L));
    }

    @Test
    void decimalSeparatorFollowsTheRequestLocale() {
        LocaleContextHolder.setLocale(Locale.GERMAN);

        assertEquals("1,2 GB", formatter.gigabytes(1_200_000_000L));
        assertEquals("117 GB", formatter.gigabytes(117_000_000_000L));
    }

    @Test
    void numberHasNoUnit() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        assertEquals("48", formatter.number(48_000_000_000L));
        assertEquals("0.4", formatter.number(400_000_000L));
    }
}
