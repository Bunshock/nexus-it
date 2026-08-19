package com.bunshock.note_app_for_it_frontend.services.auth;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.auth.ADUser;
import com.bunshock.note_app_for_it_frontend.models.auth.AdCredentialResult;

public class MockADService implements IADService {

    // Test/local-dev only — stands in until the real AD API's validate-credentials endpoint
    // exists (see IADService.validateCredentials()). Any of the three mock users below,
    // paired with this fixed password, is treated as valid and a member of the mock "allowed"
    // group so login-gated flows can be exercised end to end without a real AD API.
    private static final String MOCK_PASSWORD = "password123";
    private static final String MOCK_ALLOWED_GROUP = "AllowedAppUsers";

    private final List<ADUser> users = List.of(
        new ADUser("35123456", "Leandro Mantovani", "lmantovani",
            "lmantovani@ues21.edu.ar", "OU=Cordoba,OU=IT,DC=ues21"),
        new ADUser("38987654", "Leandro Garcia", "lgarcia",
            "lgarcia@ues21.edu.ar", "OU=BuenosAires,OU=Docentes,DC=ues21"),
        new ADUser("40111222", "Juan Perez", "jperez",
            "jperez@ues21.edu.ar", "OU=Cordoba,OU=Alumnos,DC=ues21")
    );

    @Override
    public List<ADUser> search(String dni, String name, String username) {
        String cleanDni = dni != null ? dni.replace(".", "").trim() : "";

        return users.stream()
            .filter(u -> {
                String dbDni = u.getDni().replace(".", "");
                boolean matchesDni = !cleanDni.isEmpty() && dbDni.contains(cleanDni);
                boolean matchesName = name != null && !name.isBlank()
                    && u.getFullName().toLowerCase().contains(name.toLowerCase());
                boolean matchesUsername = username != null && !username.isBlank()
                    && (u.getUsername().toLowerCase().contains(username.toLowerCase())
                        || u.getUsername().replace(".", "-").equalsIgnoreCase(username.replace(".", "-")));
                return matchesDni || matchesName || matchesUsername;
            })
            .toList();
    }

    @Override
    public AdCredentialResult validateCredentials(String username, String password) {
        boolean knownUser = users.stream().anyMatch(u -> u.getUsername().equalsIgnoreCase(username));
        boolean valid = knownUser && MOCK_PASSWORD.equals(password);
        return new AdCredentialResult(valid, valid ? List.of(MOCK_ALLOWED_GROUP) : List.of());
    }
}
