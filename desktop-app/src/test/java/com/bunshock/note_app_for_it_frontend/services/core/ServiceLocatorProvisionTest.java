package com.bunshock.note_app_for_it_frontend.services.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import com.bunshock.note_app_for_it_frontend.models.core.AppConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// provisionDefaultSecrets()/provisionIfMissing() write directly into the real local
// APP_SETTINGS table (DatabaseService.getInstance(), "data/noteapp.db" — the same file the
// running app uses on this machine), using fixed, real production key names. There's no way
// to parametrize them per test the way TechnicianSessionServiceTest does with its per-username
// display-name-preference key, so every test here backs up whatever value already exists for
// the keys it touches and restores it in @AfterEach — a run against a developer's real,
// already-configured installation must never lose real settings.
//
// Phase B: the remote-DB fields (db_host/port/name/username/password), ad_api_token, and
// glpi_api_key were all retired along with RemoteDatabaseService/AdApiService/the GLPI Settings
// card — the middleware is the one remote connection now, configured via config.middleware.baseUrl
// (not a secret, not provisioned here). smtp_password is the only secret provisionDefaultSecrets()
// still copies from config.defaults.
class ServiceLocatorProvisionTest {

    private static final String KEY = "smtp_password";

    private final ServiceLocator locator = ServiceLocator.getInstance();
    private final Map<String, String> backup = new HashMap<>();

    @BeforeEach
    void backupSettings() throws Exception {
        backup.put(KEY, readSetting(KEY));
    }

    @AfterEach
    void restoreSettings() throws Exception {
        String original = backup.get(KEY);
        if (original == null) {
            deleteSetting(KEY);
        } else {
            writeSetting(KEY, original);
        }
    }

    @Test
    void provisionsSmtpPasswordFromDefaultsWhenMissing() throws Exception {
        deleteSetting(KEY);

        AppConfig config = new AppConfig();
        config.defaults = new AppConfig.DefaultSecrets();
        config.defaults.smtpPassword = "encrypted-smtp-ciphertext";

        locator.provisionDefaultSecrets(config);

        assertEquals("encrypted-smtp-ciphertext", readSetting(KEY));
    }

    @Test
    void doesNotOverwriteExistingSmtpPassword() throws Exception {
        writeSetting(KEY, "already-set-ciphertext");

        AppConfig config = new AppConfig();
        config.defaults = new AppConfig.DefaultSecrets();
        config.defaults.smtpPassword = "should-not-be-used-ciphertext";

        locator.provisionDefaultSecrets(config);

        assertEquals("already-set-ciphertext", readSetting(KEY));
    }

    @Test
    void provisionIfMissingIgnoresBlankOrNullValue() throws Exception {
        deleteSetting(KEY);

        locator.provisionIfMissing(KEY, "");
        assertNull(readSetting(KEY));

        locator.provisionIfMissing(KEY, null);
        assertNull(readSetting(KEY));
    }

    @Test
    void provisionDefaultSecretsIsANoOpWhenDefaultsIsNull() throws Exception {
        deleteSetting(KEY);

        AppConfig config = new AppConfig();
        config.defaults = null;

        locator.provisionDefaultSecrets(config);

        assertNull(readSetting(KEY));
    }

    private String readSetting(String key) throws Exception {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM APP_SETTINGS WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        }
    }

    private void writeSetting(String key, String value) throws Exception {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO APP_SETTINGS (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private void deleteSetting(String key) throws Exception {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM APP_SETTINGS WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }
}
