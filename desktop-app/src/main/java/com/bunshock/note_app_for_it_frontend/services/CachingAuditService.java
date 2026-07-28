package com.bunshock.note_app_for_it_frontend.services;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ActionAuditEntry;
import com.bunshock.note_app_for_it_frontend.models.LoginAuditEntry;

/**
 * Writes deliberately deviate from every other CachingXxxService's "primary first, fail loudly"
 * convention: logLogin/logAction try primary then local, swallowing every exception from both —
 * an audit-write failure must never surface to (or block) the caller performing the real action.
 * Reads use the normal remote-first/local-fallback pattern.
 */
public class CachingAuditService implements IAuditService {

    private final IAuditService primary;
    private final IAuditService local;

    public CachingAuditService(IAuditService primary, IAuditService local) {
        this.primary = primary;
        this.local = local;
    }

    @Override
    public void logLogin(String username, boolean success, String failureReason) {
        try { primary.logLogin(username, success, failureReason); } catch (Exception ignored) { }
        try { local.logLogin(username, success, failureReason); } catch (Exception ignored) { }
    }

    @Override
    public void logAction(String username, String eventType, Integer noteItemId, String details) {
        try { primary.logAction(username, eventType, noteItemId, details); } catch (Exception ignored) { }
        try { local.logAction(username, eventType, noteItemId, details); } catch (Exception ignored) { }
    }

    @Override
    public List<LoginAuditEntry> getAllLogins() {
        try { return primary.getAllLogins(); } catch (Exception e) { return local.getAllLogins(); }
    }

    @Override
    public List<ActionAuditEntry> getAllActions() {
        try { return primary.getAllActions(); } catch (Exception e) { return local.getAllActions(); }
    }
}
