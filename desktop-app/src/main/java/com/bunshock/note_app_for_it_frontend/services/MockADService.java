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
        // Filter using the provided criteria. If a criteria is empty, it won't be used for filtering.
        return mockDatabase.stream()
            .filter(user -> 
                (dni != null && !dni.isEmpty() && user.getDni().contains(dni)) ||
                (name != null && !name.isEmpty() && user.getFullName().toLowerCase().contains(name.toLowerCase())) ||
                (username != null && !username.isEmpty() && user.getUsername().toLowerCase().contains(username.toLowerCase()))
            )
            .collect(Collectors.toList());
    }
    
}
