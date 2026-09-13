package com.shizuku.translate.dto;

/**
 * A target language the service can translate into, as offered to clients.
 *
 * @param code  BCP-47 short tag sent back as {@code targetLanguage} on translate requests
 * @param label human-readable name, also the name used inside the system prompt
 */
public record LanguageOption(String code, String label) {
}
