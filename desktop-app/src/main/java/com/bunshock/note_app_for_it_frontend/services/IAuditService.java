package com.bunshock.note_app_for_it_frontend.services;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ActionAuditEntry;
import com.bunshock.note_app_for_it_frontend.models.LoginAuditEntry;

/**
 * Append-only audit trail — LOGIN_AUDIT (every login attempt) and ACTION_AUDIT (GLPI sync/
 * reject, Préstamo return/lost, DB connection changes). No update/delete methods exist on
 * purpose: nothing in the app can ever alter or remove a row once written. If cleanup is ever
 * needed, that's a manual SQL job against the database, same precedent as USER_ROLE.
 *
 * Write methods are deliberately best-effort/fail-open — see CachingAuditService's own doc —
 * a logging failure must never block the real action being audited.
 */
public interface IAuditService {

    void logLogin(String username, boolean success, String failureReason);

    void logAction(String username, String eventType, Integer noteItemId, String details);

    /** Newest first. */
    List<LoginAuditEntry> getAllLogins();

    /** Newest first. */
    List<ActionAuditEntry> getAllActions();
}
