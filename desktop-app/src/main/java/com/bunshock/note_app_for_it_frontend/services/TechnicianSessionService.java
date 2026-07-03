package com.bunshock.note_app_for_it_frontend.services;

import java.util.ArrayList;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ADUser;

import javafx.application.Platform;

/**
 * Holds the current technician's identity for this app session only — never persisted to
 * disk. Populated from the Windows session's UPN (email) resolved against AD; refreshed at
 * app startup and on demand (manual "Actualizar desde AD" button, or an admin-mode edit).
 */
public class TechnicianSessionService {

    public enum UpdateSource { AD, MANUAL }

    private static final TechnicianSessionService INSTANCE = new TechnicianSessionService();

    private volatile String name;
    private volatile String username;
    private volatile String email;
    private volatile String dni;
    private volatile String lastError;
    private volatile UpdateSource lastUpdateSource;

    private final List<Runnable> onChangeListeners = new ArrayList<>();

    private TechnicianSessionService() {}

    public static TechnicianSessionService getInstance() { return INSTANCE; }

    private static final String ADMIN_HINT =
        " Un administrador puede completar estos datos manualmente en modo administrador.";

    /** Resolves identity from the Windows session's UPN via AD. Safe to call from a background thread. */
    public synchronized void refreshFromWindowsSession() {
        String sessionEmail = WindowsIdentityService.getInstance().getSessionEmail();
        if (sessionEmail == null) {
            clear("No se pudo obtener el usuario de dominio de Windows." + ADMIN_HINT);
            return;
        }

        List<ADUser> results;
        try {
            results = ServiceLocator.getInstance().getAdService()
                .search(null, null, deriveUsernameFromEmail(sessionEmail));
        } catch (Exception adUnreachable) {
            clear("No se pudo conectar con Active Directory." + ADMIN_HINT);
            return;
        }

        if (results.isEmpty()) {
            clear("Usuario no encontrado en Active Directory." + ADMIN_HINT);
            return;
        }

        ADUser match = results.get(0);
        name = match.getFullName();
        username = match.getUsername();
        email = match.getEmail();
        dni = match.getDni();
        lastError = null;
        lastUpdateSource = UpdateSource.AD;
        notifyListeners();
    }

    /** Admin-mode manual override. Session-only — lost on next refresh/restart. */
    public synchronized void applyManualOverride(String name, String username, String email, String dni) {
        this.name = name;
        this.username = username;
        this.email = email;
        this.dni = dni;
        this.lastError = null;
        this.lastUpdateSource = UpdateSource.MANUAL;
        notifyListeners();
    }

    static String deriveUsernameFromEmail(String email) {
        int at = email.indexOf('@');
        return at >= 0 ? email.substring(0, at) : email;
    }

    private synchronized void clear(String error) {
        name = null;
        username = null;
        email = null;
        dni = null;
        lastError = error;
        notifyListeners();
    }

    private void notifyListeners() {
        List<Runnable> listeners = new ArrayList<>(onChangeListeners);
        Platform.runLater(() -> listeners.forEach(Runnable::run));
    }

    public void addOnChangeListener(Runnable listener) { onChangeListeners.add(listener); }
    public void removeOnChangeListener(Runnable listener) { onChangeListeners.remove(listener); }

    public String getName() { return name; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getDni() { return dni; }
    public String getLastError() { return lastError; }
    public UpdateSource getLastUpdateSource() { return lastUpdateSource; }
    public boolean isResolved() { return name != null; }
}
