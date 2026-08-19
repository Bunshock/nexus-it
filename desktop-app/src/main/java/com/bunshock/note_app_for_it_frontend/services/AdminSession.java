package com.bunshock.note_app_for_it_frontend.services;

import java.util.ArrayList;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.admin.Permission;

import javafx.application.Platform;

public class AdminSession {

    private static final AdminSession INSTANCE = new AdminSession();

    private volatile boolean active = false;
    // The role permissions are checked against while this session is active — set explicitly at
    // every activation, not read live from TechnicianSessionService's own role, so it stays a
    // fixed snapshot of whatever activatePermanently(role) was actually called with.
    private volatile String effectiveRole;

    private final List<Runnable> onActivateListeners   = new ArrayList<>();
    private final List<Runnable> onDeactivateListeners = new ArrayList<>();

    private AdminSession() {}

    public static AdminSession getInstance() { return INSTANCE; }

    /**
     * The only way to activate this session — used for an ADMIN or SUPERADMIN-role login, since
     * the technician's own credentials already proved who they are. Never expires on inactivity
     * (there used to be a separate, timed "shared admin password" activation mode with a
     * 15-minute inactivity expiry; it was removed entirely — see CLAUDE.md — since it was a
     * client-side-only bypass that added no real security beyond what real per-account logins
     * already provide, and its own fallback code path never actually called it in the first
     * place, making it dead weight even before removal).
     */
    public synchronized void activatePermanently(String role) {
        active = true;
        effectiveRole = role;
        Platform.runLater(() -> new ArrayList<>(onActivateListeners).forEach(Runnable::run));
    }

    public synchronized void deactivate() {
        if (!active) return;
        active = false;
        effectiveRole = null;
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
        return active;
    }

    // No-op — sessions no longer expire on inactivity (see activatePermanently()'s doc). Kept as
    // a harmless, documented stub rather than touching every one of its many call sites across
    // NoteDetailController/PrestamoDetailController/SettingsController/DatabaseSectionController,
    // each of which calls this right before running an admin action as a "still here" heartbeat —
    // a convention worth keeping in place even though it currently does nothing, in case a future
    // feature reintroduces timed sessions.
    public void refreshActivity() {}

    public void addOnActivateListener(Runnable listener)     { onActivateListeners.add(listener); }
    public void removeOnActivateListener(Runnable listener)  { onActivateListeners.remove(listener); }

    public void addOnDeactivateListener(Runnable listener)   { onDeactivateListeners.add(listener); }
    public void removeOnDeactivateListener(Runnable listener){ onDeactivateListeners.remove(listener); }

    /** Clears every registered listener — called once during logout (MainController's "Cerrar
     * sesión" action), alongside deactivate(). Safe only because logout discards the entire
     * MainView/ViewFactory tree these listeners belong to; see
     * TechnicianSessionService.clearSessionForLogout()'s doc for the full reasoning. */
    public void clearListenersForLogout() {
        onActivateListeners.clear();
        onDeactivateListeners.clear();
    }
}
