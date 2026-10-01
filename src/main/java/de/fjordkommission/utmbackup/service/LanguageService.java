package de.fjordkommission.utmbackup.service;

import de.fjordkommission.utmbackup.model.AvailableLanguage;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the languages provided by the application's message bundles.
 *
 * The default messages.properties bundle represents English. Additional
 * languages are detected from files named messages_<language>.properties.
 */
@Service
public class LanguageService {

    private static final Pattern LANGUAGE_FILE =
            Pattern.compile("messages_([a-zA-Z]{2,8})\\.properties");

    public List<AvailableLanguage> findAvailableLanguages() {
        List<AvailableLanguage> languages = new ArrayList<>();
        languages.add(new AvailableLanguage("en", "EN"));

        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver();

        try {
            Resource[] resources =
                    resolver.getResources("classpath*:messages_*.properties");

            for (Resource resource : resources) {
                String filename = resource.getFilename();

                if (filename == null) {
                    continue;
                }

                Matcher matcher = LANGUAGE_FILE.matcher(filename);

                if (matcher.matches()) {
                    String code = matcher.group(1).toLowerCase();

                    languages.add(
                            new AvailableLanguage(code, code.toUpperCase())
                    );
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Available language bundles could not be detected.",
                    e
            );
        }

        return languages.stream()
                .distinct()
                .sorted(Comparator.comparing(AvailableLanguage::code))
                .toList();
    }
}