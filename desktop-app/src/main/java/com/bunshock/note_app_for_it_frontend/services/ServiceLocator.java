package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.bunshock.note_app_for_it_frontend.models.AppConfig;

public class ServiceLocator {

    private static ServiceLocator instance;

    private IEquipmentService equipmentService;
    private IADService adService;
    private IGLPIService glpiService;
    private IEmailService emailService;
    private IHistoryService historyService;

    private boolean remoteConnected = false;

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

        equipmentService = localEquipment;
        historyService   = localHistory;

        String host = loadSetting("db_host");
        if (host != null && !host.isBlank()) {
            try {
                String portStr  = loadSetting("db_port");
                int    port     = (portStr != null && !portStr.isBlank()) ? Integer.parseInt(portStr) : 5432;
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

                equipmentService   = new CachingEquipmentService(remoteEquipment, localEquipment);
                historyService     = new CachingHistoryService(remoteHistory, localHistory);
                remoteConnected    = true;
            } catch (Exception e) {
                remoteConnected = false;
            }
        }

        AdApiService realAd = AdApiService.getInstance();
        realAd.configure(config.adApi != null ? config.adApi.baseUrl : null, decryptSetting("ad_api_token"));
        adService = realAd;

        glpiService  = new GLPIServiceStub();

        String encryptedPassword = loadSmtpPassword();
        emailService = new GmailEmailService(config.smtp, encryptedPassword);
    }

    /**
     * Copies pre-encrypted default secrets (config.defaults) into APP_SETTINGS on first
     * run only — never overwrites a value an admin already configured via Settings.
     */
    private void provisionDefaultSecrets(AppConfig config) {
        if (config.defaults == null) return;
        provisionIfMissing("smtp_password", config.defaults.smtpPassword);
        provisionIfMissing("glpi_api_key", config.defaults.glpiApiKey);
        provisionIfMissing("db_password", config.defaults.dbPassword);
        provisionIfMissing("ad_api_token", config.defaults.adApiToken);
    }

    private void provisionIfMissing(String key, String preEncryptedDefault) {
        if (preEncryptedDefault == null || preEncryptedDefault.isBlank()) return;
        String existing = loadSetting(key);
        if (existing != null && !existing.isBlank()) return;
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO APP_SETTINGS (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, preEncryptedDefault);
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
    public boolean           isRemoteConnected()    { return remoteConnected; }

    public void setEquipmentService(IEquipmentService s) { equipmentService = s; }
    public void setAdService(IADService s)               { adService = s; }
}
