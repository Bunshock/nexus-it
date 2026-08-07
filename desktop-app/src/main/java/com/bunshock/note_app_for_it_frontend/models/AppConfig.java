package com.bunshock.note_app_for_it_frontend.models;

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
    public ApiEndpoint adApi;
    public ApiEndpoint glpiApi;
    public RemoteDatabaseConfig remoteDatabase;
    public int noteItemLimit;
    public DefaultSecrets defaults;
    public CatalogConfig catalog = new CatalogConfig();
    public AdAccessConfig adAccess = new AdAccessConfig();
    public UpdatesConfig updates;

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiEndpoint {
        public String baseUrl;
    }

    /**
     * Gates app access at login on AD group membership, not just a valid password — see
     * LoginController. Blank allowedGroupName means the check is skipped entirely (not yet
     * configured), same degrade-gracefully convention as GLPI/AD/SMTP being unconfigured
     * elsewhere in this app. TODO: real group name still pending investigation on the org's
     * AD side — leave blank until confirmed, do not guess a value here.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdAccessConfig {
        public String allowedGroupName;

        /**
         * TEMPORARY bypass — the real AD API can't be extended with a
         * validate-credentials endpoint yet. When true, AdApiService.validateCredentials()
         * skips the (nonexistent) HTTP call entirely and instead does a real search() lookup
         * to confirm the typed username exists in AD, accepting ANY password for it. Remove
         * this flag (and AdApiService's mock branch) once the real endpoint exists — do not
         * leave it around as a permanent "skip login" switch.
         */
        public boolean mockCredentialValidation = false;
    }

    /**
     * Non-secret remote SQL Server connection fields (host/port/dbName), for pre-configuring
     * a shared database before first startup. Username/password are secrets and go through
     * DefaultSecrets instead — see ServiceLocator.provisionDefaultSecrets().
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RemoteDatabaseConfig {
        public String host;
        public int port = 1433;
        public String dbName;
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
        public String glpiApiKey;
        public String dbUsername;
        public String dbPassword;
        public String adApiToken;
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
}
