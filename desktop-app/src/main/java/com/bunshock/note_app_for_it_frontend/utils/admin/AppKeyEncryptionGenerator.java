package com.bunshock.note_app_for_it_frontend.utils.admin;

import com.bunshock.note_app_for_it_frontend.services.core.AppKeyEncryptionService;
/**
 * Developer/admin utility — encrypts a plaintext secret with the app's current embedded
 * AppKeyEncryptionService key, for pasting into app-config.json's "defaults" section so a
 * fresh install ships pre-configured (see README.md's "Pre-configuring default secrets").
 *
 * Usage (from desktop-app/):
 *   mvn exec:java -Dexec.mainClass="com.bunshock.note_app_for_it_frontend.utils.admin.AppKeyEncryptionGenerator"
 *
 * Never commit the plaintext secret anywhere — only the printed ciphertext.
 */
public class AppKeyEncryptionGenerator {

    public static void main(String[] args) throws Exception {
        java.io.Console console = System.console();
        String plaintext;
        if (console != null) {
            char[] chars = console.readPassword("Enter the secret to encrypt: ");
            plaintext = new String(chars);
            java.util.Arrays.fill(chars, '\0');
        } else {
            System.out.print("Enter the secret to encrypt: ");
            plaintext = new java.util.Scanner(System.in).nextLine();
        }

        if (plaintext == null || plaintext.isBlank()) {
            System.err.println("Secret cannot be blank.");
            System.exit(1);
        }

        String encrypted = AppKeyEncryptionService.getInstance().encrypt(plaintext);
        System.out.println("\nPaste this into app-config.json's matching \"defaults\" field:");
        System.out.println(encrypted);
    }
}
