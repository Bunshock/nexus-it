package com.bunshock.note_app_for_it.adapters.glpi;

import com.bunshock.note_app_for_it.port.dto.Availability;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Plain unit test for the state-bucket / sync-target resolution logic (no Spring, no yaml bind). */
class GlpiAdapterPropertiesTest {

    private static GlpiAdapterProperties withDefaults() {
        GlpiAdapterProperties p = new GlpiAdapterProperties();
        Map<Integer, Availability> buckets = new LinkedHashMap<>();
        buckets.put(33, Availability.AVAILABLE);
        buckets.put(31, Availability.IN_USE);
        buckets.put(34, Availability.IN_USE);
        buckets.put(35, Availability.UNAVAILABLE);
        buckets.put(45, Availability.UNAVAILABLE);
        p.setStateBuckets(buckets);
        Map<String, Integer> targets = new LinkedHashMap<>();
        targets.put("ENTREGA", 31);
        targets.put("PRESTAMO", 34);
        targets.put("DEVOLUCION", 33);
        targets.put("LOST", 45);
        p.setSyncTargets(targets);
        return p;
    }

    @Test
    void bucketForMapsKnownStatesAndFallsBackForUnknownOrNull() {
        GlpiAdapterProperties p = withDefaults();
        assertEquals(Availability.AVAILABLE, p.bucketFor(33));
        assertEquals(Availability.IN_USE, p.bucketFor(34));
        assertEquals(Availability.UNAVAILABLE, p.bucketFor(45));
        assertEquals(Availability.UNAVAILABLE, p.bucketFor(999), "unmapped state → default bucket");
        assertEquals(Availability.UNAVAILABLE, p.bucketFor(null), "states_id 0/null → default bucket");
    }

    @Test
    void bucketDefaultIsOverridable() {
        GlpiAdapterProperties p = withDefaults();
        p.setDefaultBucket(Availability.AVAILABLE);
        assertEquals(Availability.AVAILABLE, p.bucketFor(999));
    }

    @Test
    void syncTargetForReturnsTheMappedState() {
        GlpiAdapterProperties p = withDefaults();
        assertEquals(31, p.syncTargetFor("ENTREGA"));
        assertEquals(34, p.syncTargetFor("PRESTAMO"), "Préstamo writes 34 for assets and countables alike");
        assertEquals(45, p.syncTargetFor("LOST"), "lost writes 45 En baja, not 48 Scrap");
        assertNull(p.syncTargetFor("RELOCATE"), "RELOCATE keeps the state; no sync target");
    }

    @Test
    void isConfiguredNeedsBothBaseUrlAndAppToken() {
        GlpiAdapterProperties p = new GlpiAdapterProperties();
        assertFalse(p.isConfigured());
        p.setBaseUrl("https://glpi.example/api.php/v1");
        assertFalse(p.isConfigured());
        p.setAppToken("tok");
        assertTrue(p.isConfigured());
    }
}
