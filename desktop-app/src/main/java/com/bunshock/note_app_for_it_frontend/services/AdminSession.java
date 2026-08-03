package com.bunshock.note_app_for_it_frontend.services;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.bunshock.note_app_for_it_frontend.models.Permission;

import javafx.application.Platform;

public class AdminSession {

    private static final int TIMEOUT_MINUTES = 15;
    private static final AdminSession INSTANCE = new AdminSession();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "admin-session-monitor");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean active = false;
    private volatile boolean neverExpires = false;
    private volatile LocalDateTime lastActivity;
    // The role permissions are checked against while this session is active — set explicitly at
    // every activation, not read live from TechnicianSessionService's own role, because the
    // shared-password fallback (activate()) must always resolve to ADMIN-level permissions,
    // never SUPERADMIN, regardless of whoever is holding the password.
    private volatile String effectiveRole;
    private ScheduledFuture<?> timeoutCheck;

    private final List<Runnable> onActivateListeners   = new ArrayList<>();
    private final List<Runnable> onDeactivateListeners = new ArrayList<>();
    private final List<Runnable> onExpireListeners     = new ArrayList<>();

    private AdminSession() {}

    public static AdminSession getInstance() { return INSTANCE; }

    /** The shared-password fallback (requirePermission()'s on-the-spot prompt) — always
     * ADMIN-level, timed out after 15 minutes of inactivity, never SUPERADMIN regardless of the
     * technician's own actual role. */
    public synchronized void activate() {
        active = true;
        neverExpires = false;
        effectiveRole = IUserRoleService.ROLE_ADMIN;
        lastActivity = LocalDateTime.now();
        scheduleCheck();
        Platform.runLater(() -> new ArrayList<>(onActivateListeners).forEach(Runnable::run));
    }

    /**
     * Activates without ever expiring on inactivity — used for an ADMIN or SUPERADMIN-role
     * login, since the technician's own credentials already proved who they are, and the old
     * self-service "Activar modo administrador" password toggle (the only way anyone used to
     * get back in after the 15-minute timeout) no longer exists for anyone to click.
     */
    public synchronized void activatePermanently(String role) {
        active = true;
        neverExpires = true;
        effectiveRole = role;
        lastActivity = null;
        if (timeoutCheck != null) timeoutCheck.cancel(false);
        Platform.runLater(() -> new ArrayList<>(onActivateListeners).forEach(Runnable::run));
    }

    public synchronized void deactivate() {
        if (!active) return;
        active = false;
        neverExpires = false;
        effectiveRole = null;
        lastActivity = null;
        if (timeoutCheck != null) timeoutCheck.cancel(false);
        List<Runnable> listeners = new ArrayList<>(onDeactivateListeners);
        Platform.runLater(() -> listeners.forEach(Runnable::run));
    }

    /** True while this session is active and its effective role has been granted the given
     * permission (see ROLE_PERMISSION / IUserRoleService.getPermissionsForRole()). */
    public boolean hasPermission(Permission permission) {
        if (!isActive()) return false;
        String role = effectiveRole;
        if (role == null) return false;
        return ServiceLocator.getInstance().getUserRoleService().getPermissionsForRole(role).contains(permission);
    }

    /** Sede-scoped variant for APPROVE_NOTES/SYNC_GLPI/VALIDATE_RETURNS: a SUPERADMIN session
     * bypasses the Sede check entirely; an ADMIN session must have the technician's own
     * superadmin-assigned Sede match the note's Sede exactly — a null on either side (unassigned
     * technician Sede, or a note with no Sede) fails safe (denied), never falls back to "allow". */
    public boolean hasPermission(Permission permission, Integer noteSedeId) {
        if (!hasPermission(permission)) return false;
        if (IUserRoleService.ROLE_SUPERADMIN.equals(effectiveRole)) return true;
        if (noteSedeId == null) return false;
        Integer mySedeId = TechnicianSessionService.getInstance().getSedeId();
        return mySedeId != null && mySedeId.equals(noteSedeId);
    }

    /** The role backing the current activation ("ADMIN"/"SUPERADMIN"), or null if not active —
     * used only for UI decisions (e.g. the superadmin badge color), never as a substitute for
     * hasPermission() itself. */
    public String getEffectiveRole() {
        return isActive() ? effectiveRole : null;
    }

    public synchronized boolean isActive() {
        if (!active) return false;
        if (neverExpires) return true;
        if (lastActivity == null ||
            LocalDateTime.now().isAfter(lastActivity.plusMinutes(TIMEOUT_MINUTES))) {
            expire();
            return false;
        }
        return true;
    }

    public synchronized void refreshActivity() {
        if (active && !neverExpires) lastActivity = LocalDateTime.now();
    }

    public long getRemainingSeconds() {
        if (!active || neverExpires || lastActivity == null) return 0;
        long elapsed = java.time.temporal.ChronoUnit.SECONDS.between(lastActivity, LocalDateTime.now());
        return Math.max(0, TIMEOUT_MINUTES * 60L - elapsed);
    }

    public void addOnActivateListener(Runnable listener)     { onActivateListeners.add(listener); }
    public void removeOnActivateListener(Runnable listener)  { onActivateListeners.remove(listener); }

    public void addOnDeactivateListener(Runnable listener)   { onDeactivateListeners.add(listener); }
    public void removeOnDeactivateListener(Runnable listener){ onDeactivateListeners.remove(listener); }

    public void addOnExpireListener(Runnable listener)       { onExpireListeners.add(listener); }
    public void removeOnExpireListener(Runnable listener)    { onExpireListeners.remove(listener); }

    /** Clears every registered listener — called once during logout (MainController's "Cerrar
     * sesión" action), alongside deactivate(). Safe only because logout discards the entire
     * MainView/ViewFactory tree these listeners belong to; see
     * TechnicianSessionService.clearSessionForLogout()'s doc for the full reasoning. */
    public void clearListenersForLogout() {
        onActivateListeners.clear();
        onDeactivateListeners.clear();
        onExpireListeners.clear();
    }

    private void scheduleCheck() {
        if (timeoutCheck != null && !timeoutCheck.isDone()) timeoutCheck.cancel(false);
        timeoutCheck = scheduler.scheduleAtFixedRate(() -> {
            if (active && lastActivity != null &&
                LocalDateTime.now().isAfter(lastActivity.plusMinutes(TIMEOUT_MINUTES))) {
                Platform.runLater(this::expire);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private synchronized void expire() {
        if (!active) return;
        active = false;
        effectiveRole = null;
        lastActivity = null;
        List<Runnable> deact = new ArrayList<>(onDeactivateListeners);
        List<Runnable> exp   = new ArrayList<>(onExpireListeners);
        Platform.runLater(() -> {
            deact.forEach(Runnable::run);
            exp.forEach(Runnable::run);
        });
    }
}
