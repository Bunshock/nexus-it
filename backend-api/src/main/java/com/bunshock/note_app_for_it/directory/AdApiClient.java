package com.bunshock.note_app_for_it.directory;

import java.util.List;

/**
 * One raw round-trip to the directory API's user-search endpoint. {@link DirectoryService} sits
 * on top of this and does the query-shaping / merge / re-verification the raw API needs (it
 * doesn't reliably AND its params and does loose substring matching). Extracted as an interface
 * so {@code DirectoryService} is testable without real HTTP — same precedent as
 * {@code SqliteEquipmentService} taking a connection supplier.
 */
public interface AdApiClient {

    /**
     * {@code GET {baseUrl}/api/v1/ad/users?dni=&name=&username=}. Any argument may be null (that
     * param is simply omitted). Multi-valued directory attributes are already flattened to their
     * first value by the implementation. Throws {@code ApiException(502 DIRECTORY_UNAVAILABLE)}
     * on any transport failure or non-200.
     */
    List<AdApiUser> fetch(String dni, String name, String username);

    /** A directory row with every field flattened to a plain string (or null if absent). */
    record AdApiUser(String samAccountName, String displayName, String dni, String mail, String ou) {
    }
}
