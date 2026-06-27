package com.bunshock.note_app_for_it_frontend;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AfFormatterTest {

    private String formatAF(String digits, String prefix, String separator, int length, String filler) {
        String padded = filler.repeat(Math.max(0, length - digits.length())) + digits;
        if (padded.length() > length) padded = padded.substring(padded.length() - length);
        return prefix + separator + padded;
    }

    @Test
    void formatsStandardCase() {
        assertEquals("IT-00000512", formatAF("512", "IT", "-", 8, "0"));
    }

    @Test
    void truncatesWhenDigitsTooLong() {
        assertEquals("IT-12345678", formatAF("912345678", "IT", "-", 8, "0"));
    }

    @Test
    void padsFullLength() {
        assertEquals("IT-00000001", formatAF("1", "IT", "-", 8, "0"));
    }

    @Test
    void exactLength() {
        assertEquals("IT-12345678", formatAF("12345678", "IT", "-", 8, "0"));
    }

    @Test
    void differentSeparator() {
        assertEquals("AF/00000042", formatAF("42", "AF", "/", 8, "0"));
    }
}
