package com.bunshock.note_app_for_it_frontend;

import com.bunshock.note_app_for_it_frontend.services.core.AppKeyEncryptionService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppKeyEncryptionServiceTest {

    private final AppKeyEncryptionService service = AppKeyEncryptionService.getInstance();

    @Test
    void decryptsWhatWasEncrypted() {
        String plaintext = "s3cr3t-t0k3n";
        String encrypted = service.encrypt(plaintext);
        assertEquals(plaintext, service.decrypt(encrypted));
    }

    @Test
    void encryptedValueIsNotThePlaintext() {
        String plaintext = "s3cr3t-t0k3n";
        assertNotEquals(plaintext, service.encrypt(plaintext));
    }

    @Test
    void encryptingTheSamePlaintextTwiceYieldsDifferentCiphertext() {
        String plaintext = "s3cr3t-t0k3n";
        assertNotEquals(service.encrypt(plaintext), service.encrypt(plaintext));
    }

    @Test
    void decryptThrowsOnGarbageInput() {
        assertThrows(RuntimeException.class, () -> service.decrypt("not-valid-base64-ciphertext!!"));
    }

    @Test
    void handlesUnicodeAndSpecialCharacters() {
        String plaintext = "Joaquín Rodríguez — token/con símbolos & más";
        assertEquals(plaintext, service.decrypt(service.encrypt(plaintext)));
    }
}
