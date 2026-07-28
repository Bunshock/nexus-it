package com.bunshock.note_app_for_it_frontend.models;

public class LoginAuditEntry {
    private final String username;
    private final String attemptedAt;
    private final boolean success;
    private final String failureReason;

    public LoginAuditEntry(String username, String attemptedAt, boolean success, String failureReason) {
        this.username = username;
        this.attemptedAt = attemptedAt;
        this.success = success;
        this.failureReason = failureReason;
    }

    public String getUsername() { return username; }
    public String getAttemptedAt() { return attemptedAt; }
    public boolean isSuccess() { return success; }
    public String getFailureReason() { return failureReason; }
}
