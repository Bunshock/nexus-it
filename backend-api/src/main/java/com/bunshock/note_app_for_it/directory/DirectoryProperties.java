package com.bunshock.note_app_for_it.directory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code middleware.directory.*} — application.yml. Coordinates for the org's directory (Active
 * Directory) REST API, which the middleware proxies for {@code GET /api/v1/directory/users}
 * (contract §7.6 / D8). Blank until a real deployment sets them (via
 * {@code MIDDLEWARE_DIRECTORY_BASEURL} / {@code MIDDLEWARE_DIRECTORY_TOKEN}) — same "genuinely
 * open, degrade to 503 rather than crash startup" convention as {@code middleware.idp.issuer-uri}
 * and {@code middleware.security.encryption-key}.
 */
@ConfigurationProperties(prefix = "middleware.directory")
public class DirectoryProperties {

    private String baseUrl = "";
    private String token = "";

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && token != null && !token.isBlank();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
