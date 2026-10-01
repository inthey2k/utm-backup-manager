package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.model.AvailableLanguage;
import de.fjordkommission.utmbackup.service.LanguageService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * Provides common model attributes used by all web views.
 */
@ControllerAdvice
public class WebControllerAdvice {

    private final LanguageService languageService;

    public WebControllerAdvice(LanguageService languageService) {
        this.languageService = languageService;
    }

    @ModelAttribute("availableLanguages")
    public List<AvailableLanguage> availableLanguages() {
        return languageService.findAvailableLanguages();
    }
}