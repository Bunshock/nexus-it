package com.bunshock.note_app_for_it_frontend.services;

import com.bunshock.note_app_for_it_frontend.models.AppConfig;

public class ServiceLocator {

    private static ServiceLocator instance;

    private IEquipmentService equipmentService;
    private IADService adService;
    private IGLPIService glpiService;
    private IEmailService emailService;
    private IHistoryService historyService;

    private ServiceLocator() {}

    public static ServiceLocator getInstance() {
        if (instance == null) {
            instance = new ServiceLocator();
        }
        return instance;
    }

    public void initialize(AppConfig config) {
        equipmentService = new MockEquipmentService();
        adService = new MockADService();
        glpiService = new GLPIServiceStub();
        historyService = new SqliteHistoryService();

        String encryptedPassword = loadSmtpPassword();
        emailService = new GmailEmailService(config.smtp, encryptedPassword);
    }

    private String loadSmtpPassword() {
        try (java.sql.Connection c = DatabaseService.getInstance().getConnection();
             java.sql.ResultSet rs = c.createStatement().executeQuery(
                 "SELECT value FROM APP_SETTINGS WHERE key = 'smtp_password'")) {
            if (rs.next()) return rs.getString("value");
        } catch (Exception e) {
            // No password configured yet — email will report as not configured
        }
        return null;
    }

    public IEquipmentService getEquipmentService() { return equipmentService; }
    public IADService getAdService() { return adService; }
    public IGLPIService getGlpiService() { return glpiService; }
    public IEmailService getEmailService() { return emailService; }
    public IHistoryService getHistoryService() { return historyService; }

    public void setEquipmentService(IEquipmentService s) { equipmentService = s; }
    public void setAdService(IADService s) { adService = s; }
}
