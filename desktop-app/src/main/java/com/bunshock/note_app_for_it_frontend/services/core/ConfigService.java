package com.bunshock.note_app_for_it_frontend.services.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.bunshock.note_app_for_it_frontend.models.core.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ConfigService {

    private static ConfigService instance;
    private AppConfig config;

    private static final String CONFIG_FILE = "config/app-config.json";

    private ConfigService() {}

    public static ConfigService getInstance() {
        if (instance == null) {
            instance = new ConfigService();
        }
        return instance;
    }

    public void load() throws IOException {
        Path path = Paths.get(CONFIG_FILE);
        File file = path.toFile();
        if (!file.exists()) {
            throw new IOException("Config file not found: " + path.toAbsolutePath());
        }
        ObjectMapper mapper = new ObjectMapper();
        config = mapper.readValue(file, AppConfig.class);
    }

    public AppConfig getConfig() {
        if (config == null) {
            throw new IllegalStateException("ConfigService not loaded — call load() first");
        }
        return config;
    }

    private static final String DEFAULT_GENERIC_LABEL = "Genérico / Otro";

    // Falls back to the default when unconfigured, blank, or ConfigService isn't loaded yet
    // (e.g. some test setups) — never throws.
    public String getGenericLabel() {
        try {
            AppConfig.CatalogConfig catalog = getConfig().catalog;
            if (catalog != null && catalog.genericLabel != null && !catalog.genericLabel.isBlank()) {
                return catalog.genericLabel.trim();
            }
        } catch (IllegalStateException notLoaded) {
            // fall through to default
        }
        return DEFAULT_GENERIC_LABEL;
    }

    public void save() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(CONFIG_FILE), config);
    }
}
