package com.bunshock.note_app_for_it_frontend.services;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.auth.ADUser;
import com.bunshock.note_app_for_it_frontend.models.auth.AdCredentialResult;

public interface IADService {
    List<ADUser> search(String dni, String name, String username);

    /**
     * Validates a username/password pair against AD (a bind-as-user check on the AD API's
     * side, not a lookup) and reports the caller's AD group memberships on success. Throws on
     * an unreachable/erroring AD API — callers distinguish "credentials rejected" (valid=false)
     * from "couldn't even ask" (exception) the same way search() already does for lookups.
     */
    AdCredentialResult validateCredentials(String username, String password);

    default boolean isConfigured() { return true; }
}
