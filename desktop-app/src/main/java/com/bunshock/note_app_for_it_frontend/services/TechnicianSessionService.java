package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ADUser;

import javafx.application.Platform;

/**
 * Holds the current technician's identity for this app session only — never persisted to
 * disk. Populated from the Windows session's UPN (email) resolved against AD; refreshed at
 * app startup and on demand (manual "Actualizar desde AD" button, or an admin-mode edit).
 * The one exception is displayNamePreference (see getDisplayName()/setDisplayNamePreference()):
 * a personal greeting-name preference, independent of AD identity, persisted locally in
 * APP_SETTINGS keyed by username so it survives restarts and AD refreshes.
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
    private volatile String displayNamePreference;
    private volatile Integer sedeIdPreference;
    private volatile String sedeName;

    private final List<Runnable> onChangeListeners = new ArrayList<>();
    private final List<Runnable> onDisplayNameChangeListeners = new ArrayList<>();
    private final List<Runnable> onSedeChangeListeners = new ArrayList<>();

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
        loadDisplayNamePreference();
        loadSedePreference();
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
        loadDisplayNamePreference();
        loadSedePreference();
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
        displayNamePreference = null;
        sedeIdPreference = null;
        sedeName = null;
        lastError = error;
        notifyListeners();
    }

    /**
     * The sidebar greeting name: the technician's own explicit preference if they've set one
     * (persisted locally, keyed by username — see setDisplayNamePreference()), otherwise a
     * suggested default derived from the AD full name's last word. AD's name order is kept as
     * "Apellido Nombre" (see AdApiService.normalizeName()), so the last word is the given name.
     */
    public String getDisplayName() {
        // Snapshot the volatile field into a local once — reading it twice (a null/blank check,
        // then using it again) is a check-then-act race: another thread's clear()/
        // applyManualOverride(null,...) can null it out in between, turning the second read into
        // an NPE. A local read is a single, consistent snapshot no other thread can invalidate.
        String preference = displayNamePreference;
        if (preference != null && !preference.isBlank()) return preference;
        return defaultDisplayName();
    }

    private String defaultDisplayName() {
        String currentName = name;
        if (currentName == null || currentName.isBlank()) return null;
        String[] words = currentName.trim().split("\\s+");
        String last = words[words.length - 1];
        return last.isEmpty() ? currentName : Character.toUpperCase(last.charAt(0)) + last.substring(1).toLowerCase();
    }

    /**
     * Sets the technician's explicit greeting-name preference — independent of AD identity data,
     * so it survives AD refreshes untouched. A blank value clears the preference back to
     * defaultDisplayName(). No-ops if the username isn't resolved yet (nothing to key it by).
     */
    public synchronized void setDisplayNamePreference(String value) {
        if (username == null) return;
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            deleteSetting(displayNamePreferenceKey(username));
            displayNamePreference = null;
        } else {
            saveSetting(displayNamePreferenceKey(username), trimmed);
            displayNamePreference = trimmed;
        }
        // Its own listener list, separate from onChangeListeners — this preference is
        // independent of AD identity (see class doc), so notifying it must not re-trigger
        // ProfileController's identity-refresh confirmation message (lblProfileStatus), which
        // used to fire on every display-name save/reset since both routed through the same
        // notifyListeners()/populateFieldsFromSession() callback.
        notifyDisplayNameListeners();
    }

    private void loadDisplayNamePreference() {
        displayNamePreference = username != null ? loadSetting(displayNamePreferenceKey(username)) : null;
    }

    private static String displayNamePreferenceKey(String username) {
        return "display_name_pref:" + username;
    }

    /** The technician's configured Sede (site) display name, or null if not set yet. Resolved
     * from the stored sede_id at load time — see loadSedePreference(). */
    public String getSede() { return sedeName; }

    /** The technician's configured Sede id, or null if not set yet — this is what gets
     * persisted onto NOTE_REPORT.sede_id at note-generation time. */
    public Integer getSedeId() { return sedeIdPreference; }

    /**
     * Sets the technician's Sede preference from a catalog selection (the Settings ComboBox<Sede>
     * item), persisting only the id in APP_SETTINGS keyed by username — same key/mechanism the
     * old free-text version used (sede_pref:<username>), just storing an id instead of a name now
     * (added 2026-07-24, when Sede became catalog-backed). Its own listener list keeps a Sede
     * save from re-triggering the AD-identity or display-name status messages, same reasoning as
     * the existing listener split between those two.
     */
    public synchronized void setSedePreference(Integer sedeId, String sedeName) {
        if (username == null) return;
        if (sedeId == null) {
            deleteSetting(sedePreferenceKey(username));
            this.sedeIdPreference = null;
            this.sedeName = null;
        } else {
            saveSetting(sedePreferenceKey(username), String.valueOf(sedeId));
            this.sedeIdPreference = sedeId;
            this.sedeName = sedeName;
        }
        notifySedeListeners();
    }

    private void loadSedePreference() {
        String stored = username != null ? loadSetting(sedePreferenceKey(username)) : null;
        if (stored == null) {
            sedeIdPreference = null;
            sedeName = null;
            return;
        }
        try {
            sedeIdPreference = Integer.parseInt(stored);
        } catch (NumberFormatException notAnId) {
            // Pre-2026-07-24 installs stored the Sede as free text, not a catalog id — treat as
            // unset rather than crash; the technician re-picks it once via the new combobox.
            sedeIdPreference = null;
            sedeName = null;
            return;
        }
        sedeName = resolveSedeName(sedeIdPreference);
    }

    // Resolves regardless of deprecated status — a technician's own saved preference should
    // still display *something* even if an admin renamed/deprecated that Sede since, rather
    // than silently going blank (which would also block note generation, since Sede is
    // mandatory). If the row was genuinely deleted this returns null, same as never having set one.
    private String resolveSedeName(int sedeId) {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT name FROM SEDE WHERE id = ?")) {
            ps.setInt(1, sedeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("name") : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private static String sedePreferenceKey(String username) {
        return "sede_pref:" + username;
    }

    private String loadSetting(String key) {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM APP_SETTINGS WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private void saveSetting(String key, String value) {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO APP_SETTINGS (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            // best-effort persistence — the in-memory value above still updates, so this
            // session stays correct even if the write fails
        }
    }

    private void deleteSetting(String key) {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM APP_SETTINGS WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            // best-effort — see saveSetting
        }
    }

    private void notifyListeners() {
        List<Runnable> listeners = new ArrayList<>(onChangeListeners);
        Platform.runLater(() -> listeners.forEach(Runnable::run));
    }

    private void notifyDisplayNameListeners() {
        List<Runnable> listeners = new ArrayList<>(onDisplayNameChangeListeners);
        Platform.runLater(() -> listeners.forEach(Runnable::run));
    }

    private void notifySedeListeners() {
        List<Runnable> listeners = new ArrayList<>(onSedeChangeListeners);
        Platform.runLater(() -> listeners.forEach(Runnable::run));
    }

    public void addOnChangeListener(Runnable listener) { onChangeListeners.add(listener); }
    public void removeOnChangeListener(Runnable listener) { onChangeListeners.remove(listener); }

    public void addOnDisplayNameChangeListener(Runnable listener) { onDisplayNameChangeListeners.add(listener); }
    public void removeOnDisplayNameChangeListener(Runnable listener) { onDisplayNameChangeListeners.remove(listener); }

    public void addOnSedeChangeListener(Runnable listener) { onSedeChangeListeners.add(listener); }
    public void removeOnSedeChangeListener(Runnable listener) { onSedeChangeListeners.remove(listener); }

    public String getName() { return name; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getDni() { return dni; }
    public String getLastError() { return lastError; }
    public UpdateSource getLastUpdateSource() { return lastUpdateSource; }
    public boolean isResolved() { return name != null; }
}
