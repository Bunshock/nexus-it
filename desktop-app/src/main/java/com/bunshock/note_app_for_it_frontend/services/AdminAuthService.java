package com.bunshock.note_app_for_it_frontend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class AdminAuthService {

    // Run utils/AdminPasswordHashGenerator to produce this value, then replace it here.
    // Never store the plaintext password anywhere — only this hash.
    private static final String ADMIN_HASH = "db851b910fc8e016e316b92f4c74ccd43d90fba20d15e4cb44ac92967904e6fe";

    private AdminAuthService() {}

    public static boolean verify(String entered) {
        if (ADMIN_HASH == null || ADMIN_HASH.isEmpty()) return false;
        return hash(entered).equals(ADMIN_HASH);
    }

    public static boolean isConfigured() {
        return ADMIN_HASH != null && !ADMIN_HASH.isEmpty();
    }

    public static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Salt ties the hash to this application, preventing cross-app rainbow table reuse.
            md.update("noteapp-it-admin-2026".getBytes(StandardCharsets.UTF_8));
            md.update(input.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
