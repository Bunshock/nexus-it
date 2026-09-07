package com.bunshock.note_app_for_it.audit.dto;

public record AuditAdminActionEntry(
        int id, String username, String action, String targetType, String targetId,
        String oldValue, String newValue, String reason, String performedAt) {
}
