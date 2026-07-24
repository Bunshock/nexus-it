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
    public SmtpConfig smtp;
    public ApiEndpoint adApi;
    public ApiEndpoint glpiApi;
    public RemoteDatabaseConfig remoteDatabase;
    public int noteItemLimit;
    public DefaultSecrets defaults;
    public RemitoConfig remito;
    public CatalogConfig catalog = new CatalogConfig();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AfFormat {
        public String prefix;
        public String separator;
        public int length;
        public String filler;
        public String inputPattern = "\\d";
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
     * Fixed "Remitente" (sender) details printed on every Remito de Envío note — this IT
     * department's own área/sede, rarely changed, edited by hand in this file (same
     * editability model as motivoOptions/fallaOptions) rather than through a Settings UI field.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RemitoConfig {
        public String remitenteArea;
        public String remitenteSede;
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
}
