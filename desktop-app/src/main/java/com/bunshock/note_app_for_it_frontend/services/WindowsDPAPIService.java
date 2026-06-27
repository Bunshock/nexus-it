package com.bunshock.note_app_for_it_frontend.services;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.sun.jna.platform.win32.Crypt32Util;

public class WindowsDPAPIService {

    private static final WindowsDPAPIService INSTANCE = new WindowsDPAPIService();

    private WindowsDPAPIService() {}

    public static WindowsDPAPIService getInstance() {
        return INSTANCE;
    }

    public String encrypt(String plaintext) {
        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = Crypt32Util.cryptProtectData(data);
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public String decrypt(String encryptedBase64) {
        byte[] encrypted = Base64.getDecoder().decode(encryptedBase64);
        byte[] decrypted = Crypt32Util.cryptUnprotectData(encrypted);
        return new String(decrypted, StandardCharsets.UTF_8);
    }
}
