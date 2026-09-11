package com.bunshock.note_app_for_it_frontend.services.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.bunshock.note_app_for_it_frontend.models.core.AppConfig;

import com.bunshock.note_app_for_it_frontend.services.audit.IAuditService;
import com.bunshock.note_app_for_it_frontend.services.audit.SqliteAuditService;
import com.bunshock.note_app_for_it_frontend.services.auth.IADService;
import com.bunshock.note_app_for_it_frontend.services.auth.RestDirectoryService;
import com.bunshock.note_app_for_it_frontend.services.catalog.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.catalog.RestEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.history.GLPIServiceStub;
import com.bunshock.note_app_for_it_frontend.services.history.IGLPIService;
import com.bunshock.note_app_for_it_frontend.services.history.IHistoryService;
import com.bunshock.note_app_for_it_frontend.services.history.RestHistoryService;
import com.bunshock.note_app_for_it_frontend.services.note.GmailEmailService;
import com.bunshock.note_app_for_it_frontend.services.note.IEmailService;
import com.bunshock.note_app_for_it_frontend.services.update.IUpdateService;
import com.bunshock.note_app_for_it_frontend.services.update.NetworkShareUpdateService;
public class ServiceLocator {

    private static ServiceLocator instance;

    private volatile IEquipmentService equipmentService;
    private IADService adService;
    private IGLPIService glpiService;
    private IEmailService emailService;
    private volatile IHistoryService historyService;
    private IAuditService auditService;
    private IUpdateService updateService;

    private ServiceLocator() {}

    public static ServiceLocator getInstance() {
        if (instance == null) {
            instance = new ServiceLocator();
        }
        return instance;
    }

    public void initialize(AppConfig config) {
        provisionDefaultSecrets(config);

        MiddlewareClient.getInstance().configure(config.middleware != null ? config.middleware.baseUrl : null);

        equipmentService = new RestEquipmentService(MiddlewareClient.getInstance());
        historyService   = new RestHistoryService(MiddlewareClient.getInstance());
        // Local-only, deliberately — no remote-first CachingAuditService wrapper yet, same
        // reasoning as APP_SETTINGS staying local-only regardless of remote config. See
        // IAuditService's Javadoc.
        auditService     = new SqliteAuditService();

        // Phase B: directory search (recipient lookup) goes through the middleware now — the
        // middleware owns its own directory config server-side, nothing left to configure here.
        adService = new RestDirectoryService(MiddlewareClient.getInstance());

        glpiService  = new GLPIServiceStub();

        String encryptedPassword = loadSmtpPassword();
        emailService = new GmailEmailService(config.smtp, encryptedPassword);

        NetworkShareUpdateService realUpdate = NetworkShareUpdateService.getInstance();
        realUpdate.configure(config.updates != null ? config.updates.manifestPath : null);
        updateService = realUpdate;
    }

    /**
     * Copies pre-encrypted default secrets (config.defaults) into APP_SETTINGS on first run only —
     * never overwrites a value an admin already configured via Settings. The remote-DB fields
     * (config.remoteDatabase, defaults.dbUsername/dbPassword) were retired along with
     * RemoteDatabaseService — the middleware is the one remote connection now, configured via
     * config.middleware.baseUrl (a plain, non-secret URL, not something provisioned here).
     */
    void provisionDefaultSecrets(AppConfig config) {
        if (config.defaults == null) return;
        provisionIfMissing("smtp_password", config.defaults.smtpPassword);
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

    private String loadSmtpPassword() {
        return loadSetting("smtp_password");
    }

    public IEquipmentService getEquipmentService() { return equipmentService; }
    public IADService        getAdService()         { return adService; }
    public IGLPIService      getGlpiService()       { return glpiService; }
    public IEmailService     getEmailService()      { return emailService; }
    public IHistoryService   getHistoryService()    { return historyService; }
    public IAuditService     getAuditService()      { return auditService; }
    public IUpdateService    getUpdateService()     { return updateService; }

    public void setEquipmentService(IEquipmentService s) { equipmentService = s; }
    public void setAdService(IADService s)               { adService = s; }
    public void setHistoryService(IHistoryService s)     { historyService = s; }
    public void setAuditService(IAuditService s)         { auditService = s; }
    public void setUpdateService(IUpdateService s)       { updateService = s; }
}
