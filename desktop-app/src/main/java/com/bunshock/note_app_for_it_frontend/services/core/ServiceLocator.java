package com.bunshock.note_app_for_it_frontend.services.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.bunshock.note_app_for_it_frontend.models.core.AppConfig;

import com.bunshock.note_app_for_it_frontend.services.admin.CachingUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.admin.IUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.admin.SqliteUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.audit.IAuditService;
import com.bunshock.note_app_for_it_frontend.services.audit.SqliteAuditService;
import com.bunshock.note_app_for_it_frontend.services.auth.AdApiService;
import com.bunshock.note_app_for_it_frontend.services.auth.IADService;
import com.bunshock.note_app_for_it_frontend.services.catalog.CachingEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.catalog.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.catalog.SqliteEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.history.CachingHistoryService;
import com.bunshock.note_app_for_it_frontend.services.history.GLPIServiceStub;
import com.bunshock.note_app_for_it_frontend.services.history.IGLPIService;
import com.bunshock.note_app_for_it_frontend.services.history.IHistoryService;
import com.bunshock.note_app_for_it_frontend.services.history.SqliteHistoryService;
import com.bunshock.note_app_for_it_frontend.services.note.GmailEmailService;
import com.bunshock.note_app_for_it_frontend.services.note.IEmailService;
import com.bunshock.note_app_for_it_frontend.services.update.IUpdateService;
import com.bunshock.note_app_for_it_frontend.services.update.NetworkShareUpdateService;
public class ServiceLocator {

    private static ServiceLocator instance;

    // volatile: retryRemoteConnectionIfDown() can reassign these from the status-monitor's
    // background thread while the FX thread reads them.
    private volatile IEquipmentService equipmentService;
    private IADService adService;
    private IGLPIService glpiService;
    private IEmailService emailService;
    private volatile IHistoryService historyService;
    private volatile IUserRoleService userRoleService;
    private IAuditService auditService;
    private IUpdateService updateService;

    private volatile boolean remoteConnected = false;

    private ServiceLocator() {}

    public static ServiceLocator getInstance() {
        if (instance == null) {
            instance = new ServiceLocator();
        }
        return instance;
    }

    public void initialize(AppConfig config) {
        provisionDefaultSecrets(config);

        SqliteEquipmentService localEquipment = new SqliteEquipmentService();
        SqliteHistoryService localHistory     = new SqliteHistoryService();
        SqliteUserRoleService localUserRole   = new SqliteUserRoleService();

        equipmentService = localEquipment;
        historyService   = localHistory;
        userRoleService  = localUserRole;
        // Local-only, deliberately — no remote-first CachingAuditService wrapper yet, same
        // reasoning as APP_SETTINGS staying local-only regardless of remote config. See
        // IAuditService's Javadoc.
        auditService     = new SqliteAuditService();

        connectRemote();

        AdApiService realAd = AdApiService.getInstance();
        realAd.configure(config.adApi != null ? config.adApi.baseUrl : null, decryptSetting("ad_api_token"));
        adService = realAd;

        glpiService  = new GLPIServiceStub();

        String encryptedPassword = loadSmtpPassword();
        emailService = new GmailEmailService(config.smtp, encryptedPassword);

        NetworkShareUpdateService realUpdate = NetworkShareUpdateService.getInstance();
        realUpdate.configure(config.updates != null ? config.updates.manifestPath : null);
        updateService = realUpdate;
    }

    /**
     * Called periodically by MainController's status monitor (not just at startup) — if remote
     * was unreachable when initialize() ran, this is the only thing that can later promote
     * equipmentService/historyService/userRoleService from local-only to the Caching wrapper
     * once remote actually comes back. No-op if already connected or not configured at all.
     */
    public void retryRemoteConnectionIfDown() {
        if (!remoteConnected) connectRemote();
    }

    private void connectRemote() {
        String host = loadSetting("db_host");
        if (host == null || host.isBlank()) return;
        try {
            String portStr  = loadSetting("db_port");
            int    port     = (portStr != null && !portStr.isBlank()) ? Integer.parseInt(portStr) : 1433;
            String dbName   = loadSetting("db_name");
            String username = decryptSetting("db_username");
            String password = decryptSetting("db_password");

            RemoteDatabaseService remote = RemoteDatabaseService.getInstance();
            remote.configure(host, port, dbName, username, password);
            remote.ensureSchema();

            IEquipmentService remoteEquipment = new SqliteEquipmentService(() -> {
                try { return remote.getConnection(); } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            });
            IHistoryService remoteHistory = new SqliteHistoryService(() -> {
                try { return remote.getConnection(); } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            });
            IUserRoleService remoteUserRole = new SqliteUserRoleService(() -> {
                try { return remote.getConnection(); } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            });

            // Safe only because remoteConnected is still false here — these fields are guaranteed
            // to still be the plain local instances, never an already-wrapped Caching*Service.
            equipmentService = new CachingEquipmentService(remoteEquipment, equipmentService);
            historyService   = new CachingHistoryService(remoteHistory, historyService);
            userRoleService  = new CachingUserRoleService(remoteUserRole, userRoleService);
            remoteConnected  = true;
        } catch (Exception e) {
            remoteConnected = false;
        }
    }

    /**
     * Copies non-secret remote DB fields (config.remoteDatabase) and pre-encrypted default
     * secrets (config.defaults) into APP_SETTINGS on first run only — never overwrites a
     * value an admin already configured via Settings / Base de Datos.
     */
    void provisionDefaultSecrets(AppConfig config) {
        if (config.remoteDatabase != null
                && config.remoteDatabase.host != null
                && !config.remoteDatabase.host.isBlank()) {
            provisionIfMissing("db_host", config.remoteDatabase.host);
            provisionIfMissing("db_port", String.valueOf(config.remoteDatabase.port));
            provisionIfMissing("db_name", config.remoteDatabase.dbName);
        }

        if (config.defaults == null) return;
        provisionIfMissing("smtp_password", config.defaults.smtpPassword);
        provisionIfMissing("glpi_api_key", config.defaults.glpiApiKey);
        provisionIfMissing("db_username", config.defaults.dbUsername);
        provisionIfMissing("db_password", config.defaults.dbPassword);
        provisionIfMissing("ad_api_token", config.defaults.adApiToken);
    }

    void provisionIfMissing(String key, String value) {
        if (value == null || value.isBlank()) return;
        String existing = loadSetting(key);
        if (existing != null && !existing.isBlank()) return;
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO APP_SETTINGS (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (Exception e) {
            // Best-effort — a failed default provisioning just leaves that setting
            // unconfigured, same as a fresh install with no defaults shipped at all.
        }
    }

    private String loadSetting(String key) {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT value FROM APP_SETTINGS WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String decryptSetting(String key) {
        String enc = loadSetting(key);
        if (enc == null || enc.isBlank()) return null;
        try { return AppKeyEncryptionService.getInstance().decrypt(enc); }
        catch (Exception e) { return null; }
    }

    private String loadSmtpPassword() {
        return loadSetting("smtp_password");
    }

    public IEquipmentService getEquipmentService() { return equipmentService; }
    public IADService        getAdService()         { return adService; }
    public IGLPIService      getGlpiService()       { return glpiService; }
    public IEmailService     getEmailService()      { return emailService; }
    public IHistoryService   getHistoryService()    { return historyService; }
    public IUserRoleService  getUserRoleService()   { return userRoleService; }
    public IAuditService     getAuditService()      { return auditService; }
    public IUpdateService    getUpdateService()     { return updateService; }
    public boolean           isRemoteConnected()    { return remoteConnected; }

    public void setEquipmentService(IEquipmentService s) { equipmentService = s; }
    public void setAdService(IADService s)               { adService = s; }
    public void setUserRoleService(IUserRoleService s)   { userRoleService = s; }
    public void setHistoryService(IHistoryService s)     { historyService = s; }
    public void setAuditService(IAuditService s)         { auditService = s; }
    public void setUpdateService(IUpdateService s)       { updateService = s; }
}
