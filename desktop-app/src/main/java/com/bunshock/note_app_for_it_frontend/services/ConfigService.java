package com.bunshock.note_app_for_it_frontend.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.bunshock.note_app_for_it_frontend.models.AppConfig;
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

    public void save() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(CONFIG_FILE), config);
    }
}
