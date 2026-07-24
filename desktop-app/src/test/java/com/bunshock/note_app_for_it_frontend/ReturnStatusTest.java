package com.bunshock.note_app_for_it_frontend;

import com.bunshock.note_app_for_it_frontend.models.ReturnStatus;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReturnStatusTest {

    @Test
    void fromStringMapsKnownValues() {
        assertEquals(ReturnStatus.PENDING, ReturnStatus.fromString("PENDING"));
        assertEquals(ReturnStatus.RETURNED, ReturnStatus.fromString("RETURNED"));
        assertEquals(ReturnStatus.LOST, ReturnStatus.fromString("LOST"));
        assertEquals(ReturnStatus.N_A, ReturnStatus.fromString("N_A"));
    }

    @Test
    void fromStringNullFallsBackToNA() {
        assertEquals(ReturnStatus.N_A, ReturnStatus.fromString(null));
    }

    @Test
    void fromStringUnknownValueFallsBackToNA() {
        assertEquals(ReturnStatus.N_A, ReturnStatus.fromString("GARBAGE"));
    }

    @Test
    void toDbStringMatchesEnumName() {
        assertEquals("PENDING", ReturnStatus.PENDING.toDbString());
        assertEquals("RETURNED", ReturnStatus.RETURNED.toDbString());
        assertEquals("LOST", ReturnStatus.LOST.toDbString());
        assertEquals("N_A", ReturnStatus.N_A.toDbString());
    }
}
