package com.bunshock.note_app_for_it_frontend;

import com.bunshock.note_app_for_it_frontend.models.history.GlpiStatus;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlpiStatusTest {

    @Test
    void fromStringMapsKnownValues() {
        assertEquals(GlpiStatus.PENDING, GlpiStatus.fromString("PENDING"));
        assertEquals(GlpiStatus.SYNCED, GlpiStatus.fromString("SYNCED"));
        assertEquals(GlpiStatus.REJECTED, GlpiStatus.fromString("REJECTED"));
        assertEquals(GlpiStatus.N_A, GlpiStatus.fromString("N_A"));
    }

    @Test
    void fromStringNullFallsBackToNA() {
        assertEquals(GlpiStatus.N_A, GlpiStatus.fromString(null));
    }

    @Test
    void fromStringUnknownValueFallsBackToNA() {
        assertEquals(GlpiStatus.N_A, GlpiStatus.fromString("GARBAGE"));
    }

    @Test
    void toDbStringMatchesEnumName() {
        assertEquals("PENDING", GlpiStatus.PENDING.toDbString());
        assertEquals("SYNCED", GlpiStatus.SYNCED.toDbString());
        assertEquals("REJECTED", GlpiStatus.REJECTED.toDbString());
        assertEquals("N_A", GlpiStatus.N_A.toDbString());
    }
}
