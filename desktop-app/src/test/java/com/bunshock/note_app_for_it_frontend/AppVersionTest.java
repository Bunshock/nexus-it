package com.bunshock.note_app_for_it_frontend;

import com.bunshock.note_app_for_it_frontend.utils.update.AppVersion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppVersionTest {

    @Test
    void getCurrentVersionReturnsARealResolvedValueNotTheUnfilteredPlaceholder() {
        // mvn test always runs after process-resources, so version.properties should already be
        // Maven-filtered by the time this runs — this guards against the resource silently not
        // being filtered (e.g. a pom.xml <resources> regression), not against a missing file
        // (that path is covered by the fallback itself, not asserted here since it depends on
        // build state this test doesn't control).
        String version = AppVersion.getCurrentVersion();
        assertNotNull(version);
        assertFalse(version.isBlank());
        assertFalse(version.startsWith("${"), "version.properties was not filtered: " + version);
    }

    @Test
    void getCurrentVersionIsStableAcrossCalls() {
        assertEquals(AppVersion.getCurrentVersion(), AppVersion.getCurrentVersion());
    }
}
