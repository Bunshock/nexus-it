package com.bunshock.note_app_for_it.audit.dto;

/** {@code statusKind} is "GLPI" or "RETURN" — see NOTE_ITEM_STATUS_TRACKING's own tracking_type. */
public record AuditItemStatusEntry(
        int id, int itemId, String statusKind, String oldStatus, String newStatus,
        String reason, int quantity, String username, String changedAt) {
}
