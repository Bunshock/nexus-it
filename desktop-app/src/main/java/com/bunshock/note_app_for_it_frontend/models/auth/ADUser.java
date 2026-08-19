package com.bunshock.note_app_for_it_frontend.models.auth;

public class ADUser {
    private String dni;
    private String fullName;
    private String username;
    private String email;
    private String distinguishedName; // e.g., OU=2025,OU=Bajas,OU=Cau2018,OU=SEDES CAU

    public ADUser(String dni, String fullName, String username, String email, String distinguishedName) {
        this.dni = dni;
        this.fullName = fullName;
        this.username = username;
        this.email = email;
        this.distinguishedName = distinguishedName;
    }

    public String getDni() {
        return dni;
    }

    public String getFullName() {
        return fullName;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getDistinguishedName() {
        return distinguishedName;
    }
}
