package de.fjordkommission.utmbackup.service;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Formats byte counts as gigabytes for the web interface. The unit is decimal (10^9 bytes),
 * matching the browser code. A value has at most one decimal place, and a whole number has
 * no ".0", for example {@code 117 GB}, {@code 1.2 GB} or {@code 0.4 GB}. The decimal separator
 * follows the request locale.
 *
 * <p>Thymeleaf calls this bean as {@code @byteSizeFormatter}.
 */
@Component("byteSizeFormatter")
public class ByteSizeFormatter {

    private static final double BYTES_PER_GIGABYTE = 1_000_000_000d;

    /** Returns the value with its unit, for example {@code 48 GB}. */
    public String gigabytes(long bytes) {
        return number(bytes) + " GB";
    }

    /** Returns the value without a unit, for example {@code 48} or {@code 1,2} in German. */
    public String number(long bytes) {
        return numberFormat(LocaleContextHolder.getLocale()).format(bytes / BYTES_PER_GIGABYTE);
    }

    /** Package-visible so that tests can choose the locale. */
    static NumberFormat numberFormat(Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(1);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    }
}
