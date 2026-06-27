package com.bunshock.note_app_for_it_frontend.services;

import java.util.Arrays;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ADUser;

public class MockADService implements IADService {

    private final List<ADUser> users = List.of(
        new ADUser("35123456", "Leandro Mantovani", "lmantovani",
            "lmantovani@ues21.edu.ar", "OU=Cordoba,OU=IT,DC=ues21",
            Arrays.asList("Domain Users", "IT_Admins", "VPN_Users")),
        new ADUser("38987654", "Leandro Garcia", "lgarcia",
            "lgarcia@ues21.edu.ar", "OU=BuenosAires,OU=Docentes,DC=ues21",
            Arrays.asList("Domain Users", "Faculty_Group")),
        new ADUser("40111222", "Juan Perez", "jperez",
            "jperez@ues21.edu.ar", "OU=Cordoba,OU=Alumnos,DC=ues21",
            Arrays.asList("Domain Users", "Student_Group"))
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
}
