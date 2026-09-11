package com.bunshock.note_app_for_it_frontend.models.core;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {

    public AfFormat afFormat;
    public Map<String, List<String>> motivoOptions;
    public List<String> fallaOptions;
    public String failureTriggerMotivo = "Falla";
    // Names which motivoOptions.proveedor values expect the equipment to come back (e.g.
    // Garantía, Reparación) vs. permanent departures (e.g. Otro) — gates the Provider
    // return-tracking row in NoteDetailController, same "config-driven, not hardcoded" pattern
    // as failureTriggerMotivo above.
    public List<String> returnableMotivosProveedor = List.of();
    public SmtpConfig smtp;
    public int noteItemLimit;
    public DefaultSecrets defaults;
    public CatalogConfig catalog = new CatalogConfig();
    public UpdatesConfig updates;
    public MiddlewareConfig middleware;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AfFormat {
        public String prefix;
        public String separator;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SmtpConfig {
        public String host;
        public int port;
        public String senderAddress;
    }

    /**
     * Pre-encrypted (AppKeyEncryptionService) default values for a zero-touch first run —
     * generated via utils.AppKeyEncryptionGenerator, never plaintext. Copied into
     * APP_SETTINGS on first startup only if that key isn't already set; an admin's later
     * Settings edit always takes precedence.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DefaultSecrets {
        public String smtpPassword;
    }


    /**
     * catalog.genericLabel seeds the name of the single global "no specific brand/model"
     * catalog row the *first time* it's created — a one-shot default, not a live-synced
     * value (renaming it afterward is a normal admin catalog rename, same as any Brand/Model).
     * Safe to leave unset — falls back to "Genérico / Otro" if blank or missing.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CatalogConfig {
        public String genericLabel = "Genérico / Otro";
    }

    /**
     * Auto-update source — a UNC network-share folder, not an API (see CLAUDE.md's "Auto-update
     * system" section). manifestPath is not a secret, just an internal network path. Null/blank
     * means the feature is unconfigured — NetworkShareUpdateService.isConfigured() reports this
     * and every update check fails safe rather than erroring.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UpdatesConfig {
        public String manifestPath;
    }

    /**
     * The one backend the app talks to (Phase B — REST client). {@code baseUrl} is the middleware
     * root, e.g. {@code http://localhost:8080} or {@code https://nexus-it.example.org}. Not a
     * secret. Everything else the app used to configure locally (AD lookup, DB credentials)
     * lives server-side in the middleware now.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MiddlewareConfig {
        public String baseUrl;
    }
}
