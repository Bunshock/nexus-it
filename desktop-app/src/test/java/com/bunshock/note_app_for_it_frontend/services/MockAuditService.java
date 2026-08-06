package com.bunshock.note_app_for_it_frontend.services;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory IAuditService test double — real filtering logic (time window, success flag), not
 * just recorded calls, so LoginController's rate-limiter can be exercised realistically without
 * a real SQLite database. Mirrors MockADService/MockUserRoleService's precedent of being a
 * genuinely functional stand-in rather than an empty stub.
 */
public class MockAuditService implements IAuditService {

    private record LoginAttempt(String username, boolean success, LocalDateTime at) {}

    private final List<LoginAttempt> loginAttempts = new ArrayList<>();
    public final List<Object[]> stockChanges = new ArrayList<>();
    public final List<Object[]> itemStatusChanges = new ArrayList<>();
    public final List<Object[]> adminActions = new ArrayList<>();

    @Override
    public void recordLoginAttempt(String username, boolean success, String failureReason) {
        loginAttempts.add(new LoginAttempt(username, success, LocalDateTime.now()));
    }

    @Override
    public int countRecentFailedLoginAttempts(String username, int windowMinutes) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(windowMinutes);
        return (int) loginAttempts.stream()
            .filter(a -> a.username().equals(username) && !a.success() && a.at().isAfter(since))
            .count();
    }

    @Override
    public void recordStockChange(int brandId, int typeId, int modelId, int sedeId, String username,
            int oldStock, int newStock, String reason) {
        stockChanges.add(new Object[]{brandId, typeId, modelId, sedeId, username, oldStock, newStock, reason});
    }

    @Override
    public void recordItemStatusChange(int itemId, String statusKind, String oldStatus, String newStatus,
            String reason, int quantity, String username) {
        itemStatusChanges.add(new Object[]{itemId, statusKind, oldStatus, newStatus, reason, quantity, username});
    }

    @Override
    public void recordAdminAction(String username, String action, String targetType, String targetId,
            String oldValue, String newValue, String reason) {
        adminActions.add(new Object[]{username, action, targetType, targetId, oldValue, newValue, reason});
    }

    /** Test-only convenience: total attempts recorded so far, regardless of username/outcome. */
    public int loginAttemptCount() { return loginAttempts.size(); }
}
