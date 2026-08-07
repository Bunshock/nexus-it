package com.bunshock.note_app_for_it_frontend.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads the real build version from version.properties (Maven-filtered from pom.xml's
 * project.version at build time — see pom.xml's <resources> block), replacing the old hardcoded
 * "Versión 1.0.0" string that used to live directly in AboutController. Unlike
 * Package.getImplementationVersion() (only populated from a real packaged jar's manifest), this
 * resolves correctly under `mvn javafx:run` too, since Maven resource filtering runs before
 * compile regardless of how the app is launched — important right now since packaging (jpackage)
 * doesn't exist in this project yet.
 */
public final class AppVersion {

    private static final String RESOURCE_PATH = "/version.properties";
    private static final String FALLBACK_VERSION = "0.0.0-dev";

    private static String cachedVersion;

    private AppVersion() {}

    public static synchronized String getCurrentVersion() {
        if (cachedVersion != null) return cachedVersion;
        cachedVersion = readVersion();
        return cachedVersion;
    }

    private static String readVersion() {
        try (InputStream in = AppVersion.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) return FALLBACK_VERSION;
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty("app.version");
            if (version == null || version.isBlank() || version.startsWith("${")) {
                // "${project.version}" literal means the resource wasn't actually filtered
                // (e.g. resources copied some other way) — treat the same as missing.
                return FALLBACK_VERSION;
            }
            return version.trim();
        } catch (IOException e) {
            return FALLBACK_VERSION;
        }
    }
}
