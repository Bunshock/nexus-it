package com.bunshock.note_app_for_it_frontend.models;

public class TechnicianProfile {
    private String name;
    private String dni;
    private String email;
    private String windowsUsername;

    public TechnicianProfile(String name, String dni, String email, String windowsUsername) {
        this.name = name;
        this.dni = dni;
        this.email = email;
        this.windowsUsername = windowsUsername;
    }

    public String getName() { return name; }
    public String getDni() { return dni; }
    public String getEmail() { return email; }
    public String getWindowsUsername() { return windowsUsername; }

    public void setName(String name) { this.name = name; }
    public void setDni(String dni) { this.dni = dni; }
    public void setEmail(String email) { this.email = email; }
}
