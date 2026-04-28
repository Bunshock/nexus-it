package com.bunshock.note_app_for_it_frontend.models;

import java.util.List;

public class ADUser {
    private String dni;
    private String fullName;
    private String username;
    private String email;
    private String distinguishedName; // e.g., CN=...,OU=Users,DC=ues21...
    private List<String> groups;

    public ADUser(String dni, String fullName, String username, String email, String distinguishedName, List<String> groups) {
        this.dni = dni;
        this.fullName = fullName;
        this.username = username;
        this.email = email;
        this.distinguishedName = distinguishedName;
        this.groups = groups;
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

    public List<String> getGroups() {
        return groups;
    }

    public String getMemberOfSummary() {
        return String.join(", ", groups);
    }
}