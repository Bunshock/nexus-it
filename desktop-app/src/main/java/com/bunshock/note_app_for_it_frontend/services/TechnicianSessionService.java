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
 * disk. Populated once at login (LoginController.loginResolved(), after AD credentials, group
 * membership, and USER_ROLE have all already been checked), and refreshable on demand
 * ("Actualizar Perfil desde AD" in Mi Perfil, or an admin-mode manual edit) — but never
 * re-authenticated mid-session; a refresh failure leaves the existing session intact rather
 * than forcing a fresh login. The one exception is displayNamePreference (see
 * getDisplayName()/setDisplayNamePreference()): a personal greeting-name preference,
 * independent of AD identity, persisted locally in APP_SETTINGS keyed by username so it
 * survives restarts and AD refreshes.
 */
public class TechnicianSessionService {

    public enum UpdateSource { AD, MANUAL }

    private static final TechnicianSessionService INSTANCE = new TechnicianSessionService();

    private volatile String name;
    private volatile String username;
    private volatile String email;
    private volatile String dni;
    private volatile String role;
    private volatile String lastError;
    private volatile UpdateSource lastUpdateSource;
    private volatile String displayNamePreference;
    private volatile Integer sedeId;
    private volatile String sedeName;
    private volatile Boolean autoClearFormAfterGeneration;

    private final List<Runnable> onChangeListeners = new ArrayList<>();
    private final List<Runnable> onDisplayNameChangeListeners = new ArrayList<>();

    private TechnicianSessionService() {}

    public static TechnicianSessionService getInstance() { return INSTANCE; }

    /**
     * Populates the session from an already-authenticated login (LoginController): the AD
     * credential check and group-membership gate already ran, and {@code user} is the result
     * of the existing {@code search(null, null, username)} profile lookup — this method does
     * no AD I/O of its own. {@code role} is the login-time APP_USER lookup
     * ("USER"/"ADMIN"/"SUPERADMIN"). Sede is resolved here too — it's a superadmin-assigned
     * attribute (APP_USER.sede_id), not a self-service preference, so it can only ever change
     * between logins, never mid-session.
     */
    public synchronized void loginResolved(ADUser user, String role) {
        name = user.getFullName();
        username = user.getUsername();
        email = user.getEmail();
        dni = user.getDni();
        this.role = role;
        lastError = null;
        lastUpdateSource = UpdateSource.AD;
        loadDisplayNamePreference();
        loadAssignedSede();
        loadAutoClearFormPreference();
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
        loadAssignedSede();
        loadAutoClearFormPreference();
        notifyListeners();
    }

    /** Used by LoginController to pre-fill the username field from the Windows session's UPN —
     * a convenience only, never trusted for authentication itself. */
    public static String deriveUsernameFromEmail(String email) {
        int at = email.indexOf('@');
        return at >= 0 ? email.substring(0, at) : email;
    }

    /**
     * Updates name/email/dni from a fresh AD lookup (ProfileController's "Actualizar Perfil
     * desde AD" button) without touching username/role — this is a post-login profile refresh,
     * not a re-authentication, so it deliberately does NOT wipe the existing session the way
     * the old Windows-session resolution failure path used to.
     */
    public synchronized void refreshProfileFromAd(ADUser user) {
        name = user.getFullName();
        email = user.getEmail();
        dni = user.getDni();
        lastError = null;
        lastUpdateSource = UpdateSource.AD;
        notifyListeners();
    }

    /** Reports a profile-refresh failure without discarding the already-resolved session —
     * an AD hiccup while refreshing shouldn't force a technician to log in again. */
    public synchronized void reportProfileRefreshError(String message) {
        lastError = message;
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

    /**
     * Whether Generar Nota's form should clear itself automatically right after a note is
     * generated — a per-technician preference, any role can change it, defaulting to {@code true}
     * ("to prevent mistakes" — reusing stale form data for a new note) when nothing has been
     * saved yet. Same persisted-preference shape as the display name above.
     */
    public boolean isAutoClearFormAfterGeneration() {
        Boolean pref = autoClearFormAfterGeneration;
        return pref == null || pref;
    }

    public synchronized void setAutoClearFormAfterGeneration(boolean value) {
        if (username == null) return;
        saveSetting(autoClearFormPreferenceKey(username), Boolean.toString(value));
        autoClearFormAfterGeneration = value;
    }

    private void loadAutoClearFormPreference() {
        if (username == null) { autoClearFormAfterGeneration = null; return; }
        String stored = loadSetting(autoClearFormPreferenceKey(username));
        autoClearFormAfterGeneration = stored != null ? Boolean.parseBoolean(stored) : null;
    }

    private static String autoClearFormPreferenceKey(String username) {
        return "auto_clear_form_pref:" + username;
    }

    /** The technician's superadmin-assigned Sede (site) display name, or null if not assigned
     * yet. Resolved from APP_USER.sede_id at login time — see loadAssignedSede(). */
    public String getSede() { return sedeName; }

    /** The technician's superadmin-assigned Sede id, or null if not assigned yet — this is what
     * gets persisted onto NOTE_REPORT.sede_id at note-generation time, and what an ADMIN-role
     * session's Sede-scoped permission checks are compared against (see AdminSession). */
    public Integer getSedeId() { return sedeId; }

    // Sede is no longer a self-service preference — it's assigned by a superadmin directly via
    // SQL against APP_USER.sede_id, read here at login (or manual-override) time, same as role
    // itself. Resolves regardless of deprecated status — a technician's assigned Sede should
    // still display *something* even if an admin renamed/deprecated that Sede since, rather than
    // silently going blank (which would also block note generation, since Sede is mandatory). If
    // the row was genuinely deleted, or no Sede has been assigned at all, this leaves both fields
    // null, same as never having one.
    private void loadAssignedSede() {
        if (username == null) {
            sedeId = null;
            sedeName = null;
            return;
        }
        Integer assigned;
        try {
            assigned = ServiceLocator.getInstance().getUserRoleService().getSedeId(username);
        } catch (Exception e) {
            assigned = null;
        }
        sedeId = assigned;
        sedeName = assigned != null ? resolveSedeName(assigned) : null;
    }

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

    /**
     * Full reset for logout (MainController's "Cerrar sesión" action) — clears both the session
     * state and every registered listener. Clearing the listener lists too is only safe because
     * logout tears down the entire MainView/ViewFactory tree along with it: every listener still
     * registered at this point (MainController's own, plus ProfileController's/SettingsController's
     * — none of which ever unregister themselves, since before logout existed a MainController was
     * never rebuilt mid-process) belongs to a controller instance about to be discarded, and a
     * fresh MainController/ViewFactory registers its own listeners again right after the next
     * login succeeds.
     */
    public synchronized void clearSessionForLogout() {
        name = null;
        username = null;
        email = null;
        dni = null;
        role = null;
        lastError = null;
        lastUpdateSource = null;
        displayNamePreference = null;
        sedeId = null;
        sedeName = null;
        onChangeListeners.clear();
        onDisplayNameChangeListeners.clear();
    }

    public void addOnChangeListener(Runnable listener) { onChangeListeners.add(listener); }
    public void removeOnChangeListener(Runnable listener) { onChangeListeners.remove(listener); }

    public void addOnDisplayNameChangeListener(Runnable listener) { onDisplayNameChangeListeners.add(listener); }
    public void removeOnDisplayNameChangeListener(Runnable listener) { onDisplayNameChangeListeners.remove(listener); }

    public String getName() { return name; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getDni() { return dni; }
    public String getRole() { return role; }
    public String getLastError() { return lastError; }
    public UpdateSource getLastUpdateSource() { return lastUpdateSource; }
    public boolean isResolved() { return name != null; }
}
