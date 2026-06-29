package com.bunshock.note_app_for_it_frontend.utils;

import com.bunshock.note_app_for_it_frontend.services.AdminAuthService;

/**
 * Developer utility — run once to generate the admin password hash.
 *
 * Usage (from desktop-app/):
 *   mvn exec:java -Dexec.mainClass="com.bunshock.note_app_for_it_frontend.utils.AdminPasswordHashGenerator"
 *
 * Copy the printed hash into AdminAuthService.ADMIN_HASH and recompile.
 * Never commit the plaintext password anywhere.
 */
public class AdminPasswordHashGenerator {

    public static void main(String[] args) throws Exception {
        java.io.Console console = System.console();
        String password;
        if (console != null) {
            char[] chars = console.readPassword("Enter admin password: ");
            password = new String(chars);
            java.util.Arrays.fill(chars, '\0');
        } else {
            System.out.print("Enter admin password: ");
            password = new java.util.Scanner(System.in).nextLine();
        }

        if (password == null || password.isBlank()) {
            System.err.println("Password cannot be blank.");
            System.exit(1);
        }

        String hash = AdminAuthService.hash(password);
        System.out.println("\nPaste this into AdminAuthService.ADMIN_HASH:");
        System.out.println(hash);
    }
}
