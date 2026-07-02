package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DatabaseService {

    private static DatabaseService instance;
    private static final String DB_URL = "jdbc:sqlite:data/noteapp.db";

    private DatabaseService() {}

    public static DatabaseService getInstance() {
        if (instance == null) {
            instance = new DatabaseService();
        }
        return instance;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    public void initialize() throws SQLException {
        new java.io.File("data").mkdirs();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("PRAGMA foreign_keys = ON");
            createEquipmentTables(stmt);
            createHistoryTables(stmt);
            createTechnicianTable(stmt);
            createSettingsTable(stmt);
            insertDefaultData(stmt);
            seedEquipmentData(conn);
        }
    }

    private void createEquipmentTables(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS TYPE (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                is_asset INTEGER NOT NULL DEFAULT 1
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS BRAND (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS BRAND_TYPE_LINK (
                id       INTEGER PRIMARY KEY AUTOINCREMENT,
                type_id  INTEGER NOT NULL REFERENCES TYPE(id),
                brand_id INTEGER NOT NULL REFERENCES BRAND(id),
                UNIQUE(type_id, brand_id)
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS MODEL (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                name          TEXT NOT NULL
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS SN_VALIDATION (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                regex_pattern TEXT,
                description   TEXT,
                is_active     INTEGER NOT NULL DEFAULT 1
            )""");
    }

    private void createHistoryTables(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_REPORT (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at   TEXT NOT NULL,
                profile_type TEXT NOT NULL,
                glpi_synced  INTEGER NOT NULL DEFAULT 0,
                technician_id INTEGER REFERENCES TECHNICIAN_PROFILE(id)
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ENTREGA_DEVOLUCION (
                note_report_id INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                user_name      TEXT,
                user_dni       TEXT,
                user_email     TEXT,
                motivo         TEXT
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_PROVEEDOR (
                note_report_id  INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                provider_name   TEXT,
                cuit            TEXT,
                motivo          TEXT
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ITEM (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                note_id       INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
                type_name     TEXT NOT NULL,
                brand_name    TEXT,
                model_name    TEXT,
                serial_number TEXT,
                a_f           TEXT,
                quantity      INTEGER NOT NULL DEFAULT 1,
                observations  TEXT
            )""");
    }

    private void createTechnicianTable(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS TECHNICIAN_PROFILE (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                windows_username TEXT NOT NULL UNIQUE,
                name             TEXT,
                dni              TEXT,
                email            TEXT
            )""");
    }

    private void createSettingsTable(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS APP_SETTINGS (
                key   TEXT PRIMARY KEY,
                value TEXT
            )""");
    }

    private void insertDefaultData(Statement stmt) throws SQLException {
        stmt.executeUpdate("INSERT OR IGNORE INTO BRAND (name) VALUES ('Generic')");
    }

    private void seedEquipmentData(Connection conn) throws SQLException {
        try (ResultSet check = conn.createStatement()
                .executeQuery("SELECT COUNT(*) FROM TYPE")) {
            if (check.next() && check.getInt(1) > 0) return;
        }

        // {typeName, isAsset(1/0), brandName, modelName or null}
        // null modelName = no specific model; "Otro / Genérico" is added to every link at the end
        String[][] rows = {
            {"ADAPTADOR HUB",          "0", "Genérico",          "USB C"},
            {"ADAPTADOR WIFI",         "0", "TP-LINK",           "AX1800"},
            {"ALL-IN-ONE",             "1", "DELL",              "OPTIPLEX 9030"},
            {"ARO DE LUZ",             "0", "DX260",             "DX260"},
            {"BARRA DE SONIDO",        "1", "LENOVO",            "THINKSMART BAR (L10TSS2M)"},
            {"BRAZO ROBOT",            "1", "ARDUINO",           "SPLR002"},
            {"CABLE HDMI",             "0", "Genérico",          "1.5 MTS"},
            {"CABLE HDMI",             "0", "Genérico",          "15 MTS"},
            {"CABLE POWER CPU",        "0", "Genérico",          null},
            {"CABLE DE RED",           "0", "Genérico",          null},
            {"CAMARA",                 "1", "LOGITECH",          "C270"},
            {"CAMARA",                 "1", "POLYCOM",           "POLY P009"},
            {"CARGADOR NOTEBOOK",      "0", "LENOVO",            "65W"},
            {"CARGADOR NOTEBOOK",      "0", "LENOVO",            "USB C"},
            {"CELULAR",                "1", "MOTOROLA",          "E4 PLUS"},
            {"CELULAR",                "1", "MOTOROLA",          "E6 PLAY"},
            {"CELULAR",                "1", "MOTOROLA",          "G6 PLUS"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A03"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A04"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A06"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A11"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A13"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A14"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A20s"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A24"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A31"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A52s 5G"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A53 5G"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A54"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY A54 5G"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY M12"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY S21"},
            {"CELULAR",                "1", "SAMSUNG",           "GALAXY S25 FE"},
            {"FINDER",                 "1", "ARDUINO",           "OPTA 8A.04"},
            {"FINDER",                 "1", "ARDUINO",           "OPTA EXT A0602"},
            {"GABINETE",               "0", "ASUS",              "C/FUENTE"},
            {"HEADSET",                "0", "GENIUS",            "HS-230U"},
            {"HEADSET",                "0", "LOGITECH",          "H390"},
            {"HEADSET",                "0", "LOGITECH",          "M170"},
            {"HEADSET",                "0", "TRUST",             "AYDA"},
            {"HEADSET",                "0", "TRUST",             "AYDA (JACK 3.5)"},
            {"HEADSET",                "0", "TRUST",             "CARUS GXT493"},
            {"KIT FUNDA Y TEMPLADO",   "0", "Genérico",          null},
            {"LINEA CORPORATIVA",      "0", "PERSONAL",          null},
            {"MICROFONO",              "0", "K9",                null},
            {"MICROFONO CORBATERO",    "0", "LAMBO TECH",        "K8"},
            {"MINI PC",                "1", "ACER",              "VN90G SERIES"},
            {"MINI PC",                "1", "LENOVO",            "NEO 50Q GEN 4"},
            {"MINI PC",                "1", "LENOVO",            "NEO 55Q GEN 6"},
            {"MODEM",                  "1", "HUAWEI",            "B310s"},
            {"MONITOR",                "1", "ACER",              "V227Q"},
            {"MONITOR",                "1", "BANGHO",            "EM2131W"},
            {"MONITOR",                "1", "BANGHO",            "LED 22\""},
            {"MONITOR",                "1", "LG",                "23EA53V"},
            {"MONITOR",                "1", "NETSYS",            "L24W9-K.AR"},
            {"MONITOR",                "1", "SAMSUNG",           "22\""},
            {"MONITOR",                "1", "SAMSUNG",           "E19"},
            {"MONITOR",                "1", "SAMSUNG",           "ESSENTIAL MONITOR"},
            {"MONITOR",                "1", "SAMSUNG",           "F220T350FHL"},
            {"MONITOR",                "1", "SAMSUNG",           "LS22D300FYCZB"},
            {"MONITOR",                "1", "SAMSUNG",           "LS22F350FHLXZB"},
            {"MONITOR",                "1", "SAMSUNG",           "S19B150N"},
            {"MONITOR",                "1", "SAMSUNG",           "S22F350FH"},
            {"MONITOR",                "1", "SAMSUNG",           "S22F350FHL (22\")"},
            {"MOUSE",                  "0", "ACER",              "MOANUOA"},
            {"MOUSE",                  "0", "DELL",              null},
            {"MOUSE",                  "0", "GENIUS",            "DX-120"},
            {"MOUSE",                  "0", "GENIUS",            "DX120"},
            {"MOUSE",                  "0", "GENIUS",            "NX-7000"},
            {"MOUSE",                  "0", "GENIUS",            "NX-7000SE"},
            {"MOUSE",                  "0", "GENIUS",            "NX-7123"},
            {"MOUSE",                  "0", "LENOVO",            "AB1AS3Z"},
            {"MOUSE",                  "0", "LENOVO",            "EMS-537A"},
            {"MOUSE",                  "0", "LOGITECH",          "M170"},
            {"NOTEBOOK",               "1", "ASUS",              "AORUS 15P RX5L"},
            {"NOTEBOOK",               "1", "LENOVO",            "14s-IML"},
            {"NOTEBOOK",               "1", "LENOVO",            "14s-IWL"},
            {"NOTEBOOK",               "1", "LENOVO",            "B50-80"},
            {"NOTEBOOK",               "1", "LENOVO",            "E14 GEN 2"},
            {"NOTEBOOK",               "1", "LENOVO",            "E14 GEN 3"},
            {"NOTEBOOK",               "1", "LENOVO",            "E14 GEN 4"},
            {"NOTEBOOK",               "1", "LENOVO",            "E14 GEN 5"},
            {"NOTEBOOK",               "1", "LENOVO",            "E14 GEN 6"},
            {"NOTEBOOK",               "1", "LENOVO",            "E14 GEN 7"},
            {"NOTEBOOK",               "1", "LENOVO",            "E16 GEN 1"},
            {"NOTEBOOK",               "1", "LENOVO",            "E16 GEN 2"},
            {"NOTEBOOK",               "1", "LENOVO",            "L14 GEN 1"},
            {"NOTEBOOK",               "1", "LENOVO",            "L15 GEN 1"},
            {"NOTEBOOK",               "1", "LENOVO",            "L15 GEN 4"},
            {"NOTEBOOK",               "1", "LENOVO",            "LEGION 5 15ACH6H"},
            {"NOTEBOOK",               "1", "LENOVO",            "LEGION 5 16IRX9"},
            {"NOTEBOOK",               "1", "LENOVO",            "LEGION PRO 5 16ADR10"},
            {"NOTEBOOK",               "1", "LENOVO",            "LEGION SLIM 5 16ARP9"},
            {"NOTEBOOK",               "1", "LENOVO",            "LOQ 15IAX9E"},
            {"NOTEBOOK",               "1", "LENOVO",            "THINKBOOK 16 G6 ABP"},
            {"NOTEBOOK",               "1", "LENOVO",            "THINKBOOK 16 G7 ARP"},
            {"NOTEBOOK",               "1", "LENOVO",            "V15 G4 IRU"},
            {"NOTEBOOK",               "1", "LENOVO",            "V310-15ISK"},
            {"NOTEBOOK",               "1", "LENOVO",            "V330-14IKB"},
            {"NOTEBOOK",               "1", "LENOVO",            "V330-15IKB"},
            {"NOTEBOOK",               "1", "LENOVO",            "X1 CARBON"},
            {"NOTEBOOK",               "1", "LENOVO",            "X1 YOGA"},
            {"NOTEBOOK",               "1", "MSI",               "KATANA"},
            {"PALO DE SELFIE",         "0", "H1S",               "3556"},
            {"PANTALLA INTERACTIVA",   "1", "LEGAMASTER",        "ETX-6500 (65\")"},
            {"PANTALLA INTERACTIVA",   "1", "LEGAMASTER",        "ETX-6510UHD"},
            {"PC",                     "1", "APPLE",             "iMAC (EMC: 3442)"},
            {"PC",                     "1", "APPLE",             "iMac (EMC: 2833)"},
            {"PC",                     "1", "APPLE",             "iMac (EMC: 3069)"},
            {"PC",                     "1", "APPLE",             "iMac (EMC: 3195)"},
            {"PC",                     "1", "DELL",              "OPTIPLEX 3050"},
            {"PC",                     "1", "INTEL",             "NUC 7i5BNH"},
            {"PC",                     "1", "INTEL",             "NUC i5"},
            {"PC",                     "1", "INTEL",             "NUC7i5BNH"},
            {"PC",                     "1", "INTEL",             "NUC7i5BNHL"},
            {"PENDRIVE",               "0", "SANDISK",           "32 GB"},
            {"PROYECTOR",              "1", "EPSON",             "H854A"},
            {"RAM",                    "0", "ADATA",             "16GB DDR5 5600MHZ"},
            {"RAM",                    "0", "ADATA",             "DDR4"},
            {"RAM",                    "0", "CRUCIAL",           "DDR4"},
            {"RECEPTOR WIFI",          "0", "TP-LINK",           "AX1800"},
            {"ROBOT KIT",              "1", "ARDUINO",           "ARVIK"},
            {"SOPORTE TV",             "0", "Genérico",          null},
            {"SSD",                    "0", "Genérico",          "240GB"},
            {"SSD",                    "0", "GIGABYTE",          "NVMe M2 256GB"},
            {"TABLET",                 "1", "LENOVO",            "TAB M8"},
            {"TABLET",                 "1", "REDMI",             "REDMI PAD PRO"},
            {"TABLET",                 "1", "XIAOMI",            "REDMI PAD PRO"},
            {"TARJETA DE MEMORIA",     "0", "SANDISK",           "EXTREME PRO 128GB RIA"},
            {"TECLADO",                "0", "ACER",              "PR1101V"},
            {"TONER",                  "0", "BROTHER",           "3479N"},
            {"TONER",                  "0", "KATUN PERFORMANCE", "TN-221K (NEGRO)"},
            {"TONER",                  "0", "KATUN PERFORMANCE", "TN-221Y (AMARILLO)"},
            {"TONER",                  "0", "RICOH SAVIN LANIER","IM C300 (NEGRO)"},
            {"TV",                     "1", "MOTOROLA",          "ANDROID TV (43\")"},
            {"TV",                     "1", "MOTOROLA",          "MT5000"},
            {"TV",                     "1", "SAMSUNG",           "43\""},
            {"UPS",                    "1", "TRV",               "NEO 850"},
            {"VR",                     "1", "META",              "QUEST 3"},
            {"WEBCAM",                 "0", "VIDLOK",            "W77"},
        };

        Map<String, Integer> typeCache  = new HashMap<>();
        Map<String, Integer> brandCache = new HashMap<>();
        Map<String, Integer> linkCache  = new HashMap<>();
        Set<String>          modelSet   = new HashSet<>();

        try (PreparedStatement psIT = conn.prepareStatement(
                 "INSERT OR IGNORE INTO TYPE (name, is_asset) VALUES (?, ?)");
             PreparedStatement psGT = conn.prepareStatement(
                 "SELECT id FROM TYPE WHERE name = ?");
             PreparedStatement psIB = conn.prepareStatement(
                 "INSERT OR IGNORE INTO BRAND (name) VALUES (?)");
             PreparedStatement psGB = conn.prepareStatement(
                 "SELECT id FROM BRAND WHERE name = ?");
             PreparedStatement psIL = conn.prepareStatement(
                 "INSERT OR IGNORE INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (?, ?)");
             PreparedStatement psGL = conn.prepareStatement(
                 "SELECT id FROM BRAND_TYPE_LINK WHERE type_id = ? AND brand_id = ?");
             PreparedStatement psIM = conn.prepareStatement(
                 "INSERT INTO MODEL (brand_type_id, name) VALUES (?, ?)")) {

            for (String[] r : rows) {
                String typeName  = r[0];
                int    isAsset   = "1".equals(r[1]) ? 1 : 0;
                String brandName = r[2];
                String modelName = r[3];

                if (!typeCache.containsKey(typeName)) {
                    psIT.setString(1, typeName); psIT.setInt(2, isAsset);
                    psIT.executeUpdate();
                    psGT.setString(1, typeName);
                    try (ResultSet rs = psGT.executeQuery()) {
                        typeCache.put(typeName, rs.getInt(1));
                    }
                }
                int tid = typeCache.get(typeName);

                if (!brandCache.containsKey(brandName)) {
                    psIB.setString(1, brandName);
                    psIB.executeUpdate();
                    psGB.setString(1, brandName);
                    try (ResultSet rs = psGB.executeQuery()) {
                        brandCache.put(brandName, rs.getInt(1));
                    }
                }
                int bid = brandCache.get(brandName);

                String lk = tid + ":" + bid;
                if (!linkCache.containsKey(lk)) {
                    psIL.setInt(1, tid); psIL.setInt(2, bid);
                    psIL.executeUpdate();
                    psGL.setInt(1, tid); psGL.setInt(2, bid);
                    try (ResultSet rs = psGL.executeQuery()) {
                        linkCache.put(lk, rs.getInt(1));
                    }
                }
                int lid = linkCache.get(lk);

                if (modelName != null) {
                    String mk = lid + "|" + modelName;
                    if (!modelSet.contains(mk)) {
                        psIM.setInt(1, lid); psIM.setString(2, modelName);
                        psIM.executeUpdate();
                        modelSet.add(mk);
                    }
                }
            }
        }

        conn.createStatement().executeUpdate("""
            INSERT INTO MODEL (brand_type_id, name)
            SELECT btl.id, 'Otro / Genérico'
            FROM BRAND_TYPE_LINK btl
            WHERE NOT EXISTS (
                SELECT 1 FROM MODEL m
                WHERE m.brand_type_id = btl.id AND m.name = 'Otro / Genérico'
            )
            """);
    }
}
