package com.bunshock.note_app_for_it.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code middleware.idp.*} — application.yml. Backs GET /auth/config (§2.0). */
@ConfigurationProperties(prefix = "middleware.idp")
public class IdpProperties {

    private String issuerUri = "";
    private String clientId = "";
    private String allowedGroupName = "";
    private String usernameClaim = "preferred_username";
    private String groupsClaim = "groups";

    public boolean isConfigured() {
        return issuerUri != null && !issuerUri.isBlank();
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getAllowedGroupName() {
        return allowedGroupName;
    }

    public void setAllowedGroupName(String allowedGroupName) {
        this.allowedGroupName = allowedGroupName;
    }

    public String getUsernameClaim() {
        return usernameClaim;
    }

    public void setUsernameClaim(String usernameClaim) {
        this.usernameClaim = usernameClaim;
    }

    public String getGroupsClaim() {
        return groupsClaim;
    }

    public void setGroupsClaim(String groupsClaim) {
        this.groupsClaim = groupsClaim;
    }
}
