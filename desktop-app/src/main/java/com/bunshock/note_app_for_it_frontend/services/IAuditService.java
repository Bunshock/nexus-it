package com.bunshock.note_app_for_it_frontend.services;

/**
 * Write-only append-only audit trail — AUDIT_LOGIN/AUDIT_STOCK/AUDIT_ITEM_STATUS/
 * AUDIT_ADMIN_ACTION. Local-only for now: writes go straight to the local SQLite database, same
 * as APP_SETTINGS — no remote-first/CachingXxxService wrapper yet. Only recordLoginAttempt()/
 * countRecentFailedLoginAttempts() are wired to a real call site (LoginController) today;
 * recordStockChange()/recordItemStatusChange()/recordAdminAction() exist for the schema to be
 * ready, but nothing calls them yet — see CLAUDE.md's audit-trail section for the full rationale
 * and the list of real call sites still to be wired (stock dialogs, GLPI/return status handlers,
 * catalog CRUD, config editors, note approval/rejection).
 */
public interface IAuditService {

    /** One row per real login attempt. Never called for an attempt blocked by the rate limiter
     * itself (see LoginController) — that's what keeps this table's growth bounded. */
    void recordLoginAttempt(String username, boolean success, String failureReason);

    /** Count of failed attempts for this username within the last windowMinutes — the
     * rate-limiter's own query, kept here so LoginController never touches SQL directly. */
    int countRecentFailedLoginAttempts(String username, int windowMinutes);

    /** brandId/typeId (not brandTypeId directly) since that's what every real caller already has
     * on hand — the BRAND_TYPE_LINK row is resolved internally, same convenience
     * IEquipmentService.setModelStock() itself already offers its own callers. */
    void recordStockChange(int brandId, int typeId, int modelId, int sedeId, String username,
            int oldStock, int newStock, String reason);

    /** statusKind is "GLPI" or "RETURN". quantity is how many units this row concerns — 1 for a
     * whole-item transition (a single serialized asset, or a GLPI sync, is always exactly one
     * unit), or the actual allocated quantity for a partial countable batch — never a sentinel or
     * NULL, so a plain SUM(quantity) works uniformly across every row in the table. reason is
     * NULL whenever no admin-typed justification applies (a plain sync, a normal return) — only
     * populated for actions that actually require one (GLPI reject, marking something lost/not
     * received). */
    void recordItemStatusChange(int itemId, String statusKind, String oldStatus, String newStatus,
            String reason, int quantity, String username);

    /** Generic admin-action log. oldValue/newValue must NEVER hold an actual secret value (SMTP
     * password, GLPI API key, AD token, DB credentials) — only that a change happened. Not yet
     * wired to any real call site. */
    void recordAdminAction(String username, String action, String targetType, String targetId,
            String oldValue, String newValue, String reason);
}
