package com.bunshock.note_app_for_it_frontend.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.bunshock.note_app_for_it_frontend.models.ADUser;

public class MockADService implements IADService {

    private final List<ADUser> mockDatabase;

    public MockADService() {
        mockDatabase = new ArrayList<>();
        
        // Let's create some dummy data
        mockDatabase.add(new ADUser("35123456", "Leandro Mantovani", "lmantovani", 
            "lmantovani@ues21.edu.ar", "OU=Cordoba, OU=IT, DC=ues21", 
            Arrays.asList("Domain Users", "IT_Admins", "VPN_Users")));

        mockDatabase.add(new ADUser("38987654", "Leandro Garcia", "lgarcia", 
            "lgarcia@ues21.edu.ar", "OU=BuenosAires, OU=Docentes, DC=ues21", 
            Arrays.asList("Domain Users", "Faculty_Group")));

        mockDatabase.add(new ADUser("40111222", "Juan Perez", "jperez", 
            "jperez@ues21.edu.ar", "OU=Cordoba, OU=Alumnos, DC=ues21", 
            Arrays.asList("Domain Users", "Student_Group")));
    }

    @Override
    public List<ADUser> search(String dni, String name, String username) {
        // Clean the input (remove dots)
        String cleanInputDni = (dni != null) ? dni.replace(".", "").trim() : "";
        
        return mockDatabase.stream()
            .filter(user -> {
                // Clean the database DNI for comparison
                String dbDniClean = user.getDni().replace(".", "");

                // Check if input DNI is a partial match of the cleaned DB DNI
                boolean matchesDni = !cleanInputDni.isEmpty() && dbDniClean.contains(cleanInputDni);
                
                boolean matchesName = name != null && !name.isEmpty() && 
                                    user.getFullName().toLowerCase().contains(name.toLowerCase());
                                    
                boolean matchesUser = username != null && !username.isEmpty() && 
                                    user.getUsername().toLowerCase().contains(username.toLowerCase());
                
                return matchesDni || matchesName || matchesUser;
            })
            .collect(Collectors.toList());
    }
    
}
