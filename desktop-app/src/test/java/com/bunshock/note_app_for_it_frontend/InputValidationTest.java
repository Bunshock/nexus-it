package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

import com.bunshock.note_app_for_it_frontend.controllers.core.ProfileController;
import com.bunshock.note_app_for_it_frontend.controllers.note.ProviderNoteController;
import com.bunshock.note_app_for_it_frontend.controllers.note.UserNoteController;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputValidationTest {

    private Pattern patternField(Class<?> controllerClass, String fieldName) throws Exception {
        Field f = controllerClass.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (Pattern) f.get(null);
    }

    private void assertNamePatternBehavior(Pattern p) {
        assertTrue(p.matcher("Juan Perez").matches());
        assertTrue(p.matcher("María José Ñañez").matches());
        assertTrue(p.matcher("Ana").matches());
        assertFalse(p.matcher("Juan  Perez").matches());
        assertFalse(p.matcher(" Juan Perez").matches());
        assertFalse(p.matcher("Juan Perez ").matches());
        assertFalse(p.matcher("Juan123").matches());
        assertFalse(p.matcher("Juan-Perez").matches());
        assertFalse(p.matcher("").matches());
    }

    private void assertDniPatternBehavior(Pattern p) {
        assertTrue(p.matcher("30111222").matches());
        assertTrue(p.matcher("3011122").matches());
        assertFalse(p.matcher("301112").matches());
        assertFalse(p.matcher("301112223").matches());
        assertFalse(p.matcher("30.111.222").matches());
        assertFalse(p.matcher("3011122a").matches());
        assertFalse(p.matcher("").matches());
    }

    @Test
    void userNoteControllerNamePattern() throws Exception {
        assertNamePatternBehavior(patternField(UserNoteController.class, "NAME_PATTERN"));
    }

    @Test
    void userNoteControllerDniPattern() throws Exception {
        assertDniPatternBehavior(patternField(UserNoteController.class, "DNI_PATTERN"));
    }

    @Test
    void profileControllerNamePattern() throws Exception {
        assertNamePatternBehavior(patternField(ProfileController.class, "NAME_PATTERN"));
    }

    @Test
    void providerNoteControllerNamePattern() throws Exception {
        assertNamePatternBehavior(patternField(ProviderNoteController.class, "NAME_PATTERN"));
    }

    @Test
    void providerNoteControllerDniPattern() throws Exception {
        assertDniPatternBehavior(patternField(ProviderNoteController.class, "DNI_PATTERN"));
    }
}
