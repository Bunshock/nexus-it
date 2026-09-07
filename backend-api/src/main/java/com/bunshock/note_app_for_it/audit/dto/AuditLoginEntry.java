package com.bunshock.note_app_for_it.audit.dto;

public record AuditLoginEntry(int id, String username, boolean success, String failureReason, String attemptedAt) {
}
