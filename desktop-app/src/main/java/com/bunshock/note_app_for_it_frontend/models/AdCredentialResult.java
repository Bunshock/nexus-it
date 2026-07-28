package com.bunshock.note_app_for_it_frontend.models;

import java.util.List;

/** Result of an AD credential check — see IADService.validateCredentials(). Groups is only
 * meaningful when valid is true; empty (never null) when credentials were rejected, so no
 * group-membership information leaks about an account that failed authentication. */
public class AdCredentialResult {
    private final boolean valid;
    private final List<String> groups;

    public AdCredentialResult(boolean valid, List<String> groups) {
        this.valid = valid;
        this.groups = groups;
    }

    public boolean isValid() { return valid; }
    public List<String> getGroups() { return groups; }
}
