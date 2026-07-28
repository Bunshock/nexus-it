package com.bunshock.note_app_for_it_frontend.models;

public class ActionAuditEntry {
    private final String username;
    private final String eventType;
    private final String occurredAt;
    private final Integer noteItemId;
    private final String details;

    public ActionAuditEntry(String username, String eventType, String occurredAt, Integer noteItemId, String details) {
        this.username = username;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.noteItemId = noteItemId;
        this.details = details;
    }

    public String getUsername() { return username; }
    public String getEventType() { return eventType; }
    public String getOccurredAt() { return occurredAt; }
    public Integer getNoteItemId() { return noteItemId; }
    public String getDetails() { return details; }
}
