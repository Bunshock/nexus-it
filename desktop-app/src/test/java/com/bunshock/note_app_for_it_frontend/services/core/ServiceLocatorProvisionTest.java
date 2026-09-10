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
import com.bunshock.note_app_for_it_frontend.services.catalog.SqliteEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.history.SqliteHistoryService;
import static org.junit.jupiter.api.Assertions.*;

// provisionDefaultSecrets()/provisionIfMissing() write directly into the real local
// APP_SETTINGS table (DatabaseService.getInstance(), "data/noteapp.db" — the same file the
// running app uses on this machine), using fixed, real production key names. There's no way
// to parametrize them per test the way TechnicianSessionServiceTest does with its per-username
// display-name-preference key, so every test here backs up whatever value already exists for
// the keys it touches and restores it in @AfterEach — a run against a developer's real,
// already-configured installation must never lose real settings.
class ServiceLocatorProvisionTest {

    private static final String[] KEYS = {
        "db_host", "db_port", "db_name", "db_username", "db_password",
        "smtp_password", "glpi_api_key", "ad_api_token"
    };

    private final ServiceLocator locator = ServiceLocator.getInstance();
    private final Map<String, String> backup = new HashMap<>();

    @BeforeEach
    void backupSettings() throws Exception {
        backup.clear();
        for (String key : KEYS) {
            backup.put(key, readSetting(key));
        }
    }

    @AfterEach
    void restoreSettings() throws Exception {
        for (String key : KEYS) {
            String original = backup.get(key);
            if (original == null) {
                deleteSetting(key);
            } else {
                writeSetting(key, original);
            }
        }
    }

    @Test
    void provisionsRemoteDatabaseHostPortAndNameWhenMissing() throws Exception {
        deleteSetting("db_host");
        deleteSetting("db_port");
        deleteSetting("db_name");

        AppConfig config = new AppConfig();
        config.remoteDatabase = new AppConfig.RemoteDatabaseConfig();
        config.remoteDatabase.host = "db.example.org";
        config.remoteDatabase.port = 5433;
        config.remoteDatabase.dbName = "notas_it";

        locator.provisionDefaultSecrets(config);

        assertEquals("db.example.org", readSetting("db_host"));
        assertEquals("5433", readSetting("db_port"));
        assertEquals("notas_it", readSetting("db_name"));
    }

    @Test
    void doesNotOverwriteExistingRemoteDatabaseHost() throws Exception {
        writeSetting("db_host", "already-configured.example.org");

        AppConfig config = new AppConfig();
        config.remoteDatabase = new AppConfig.RemoteDatabaseConfig();
        config.remoteDatabase.host = "should-not-be-used.example.org";
        config.remoteDatabase.port = 1433;
        config.remoteDatabase.dbName = "ignored";

        locator.provisionDefaultSecrets(config);

        assertEquals("already-configured.example.org", readSetting("db_host"));
    }

    @Test
    void skipsRemoteDatabaseProvisioningWhenHostBlank() throws Exception {
        deleteSetting("db_host");
        deleteSetting("db_port");
        deleteSetting("db_name");

        AppConfig config = new AppConfig();
        config.remoteDatabase = new AppConfig.RemoteDatabaseConfig();
        config.remoteDatabase.host = "";
        config.remoteDatabase.port = 1433;
        config.remoteDatabase.dbName = "notas_it";

        locator.provisionDefaultSecrets(config);

        assertNull(readSetting("db_host"));
        assertNull(readSetting("db_port"));
        assertNull(readSetting("db_name"));
    }

    @Test
    void provisionsDbUsernameFromDefaultsWhenMissing() throws Exception {
        deleteSetting("db_username");

        AppConfig config = new AppConfig();
        config.defaults = new AppConfig.DefaultSecrets();
        config.defaults.dbUsername = "encrypted-username-ciphertext";

        locator.provisionDefaultSecrets(config);

        assertEquals("encrypted-username-ciphertext", readSetting("db_username"));
    }

    @Test
    void doesNotOverwriteExistingDbUsername() throws Exception {
        writeSetting("db_username", "already-set-ciphertext");

        AppConfig config = new AppConfig();
        config.defaults = new AppConfig.DefaultSecrets();
        config.defaults.dbUsername = "should-not-be-used-ciphertext";

        locator.provisionDefaultSecrets(config);

        assertEquals("already-set-ciphertext", readSetting("db_username"));
    }

    // Same "unreachable host only" scope as RemoteDatabaseServiceTest — no live remote instance
    // exists in this project's test infrastructure to exercise the actual promotion-to-Caching
    // path. This just proves the retry doesn't throw or wrongly flip remoteConnected on failure.
    @Test
    void retryRemoteConnectionIfDownStaysDisconnectedAgainstAnUnreachableHost() throws Exception {
        writeSetting("db_host", "unreachable-test-host.invalid");
        writeSetting("db_port", "1433");
        writeSetting("db_name", "doesnotexist");
        locator.setEquipmentService(new SqliteEquipmentService());
        locator.setHistoryService(new SqliteHistoryService());

        assertFalse(locator.isRemoteConnected());
        locator.retryRemoteConnectionIfDown();
        assertFalse(locator.isRemoteConnected());
    }

    @Test
    void provisionIfMissingIgnoresBlankOrNullValue() throws Exception {
        deleteSetting("db_name");

        locator.provisionIfMissing("db_name", "");
        assertNull(readSetting("db_name"));

        locator.provisionIfMissing("db_name", null);
        assertNull(readSetting("db_name"));
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
