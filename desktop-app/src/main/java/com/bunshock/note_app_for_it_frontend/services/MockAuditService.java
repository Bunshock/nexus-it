package com.bunshock.note_app_for_it_frontend.services;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ActionAuditEntry;
import com.bunshock.note_app_for_it_frontend.models.LoginAuditEntry;

/** In-memory, test-only. */
public class MockAuditService implements IAuditService {

    private final List<LoginAuditEntry> logins = new ArrayList<>();
    private final List<ActionAuditEntry> actions = new ArrayList<>();

    @Override
    public void logLogin(String username, boolean success, String failureReason) {
        logins.add(0, new LoginAuditEntry(username, LocalDateTime.now().toString(), success, failureReason));
    }

    @Override
    public void logAction(String username, String eventType, Integer noteItemId, String details) {
        actions.add(0, new ActionAuditEntry(username, eventType, LocalDateTime.now().toString(), noteItemId, details));
    }

    @Override
    public List<LoginAuditEntry> getAllLogins() {
        return Collections.unmodifiableList(logins);
    }

    @Override
    public List<ActionAuditEntry> getAllActions() {
        return Collections.unmodifiableList(actions);
    }
}
