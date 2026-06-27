package com.bunshock.note_app_for_it_frontend.controllers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.bunshock.note_app_for_it_frontend.services.DatabaseService;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

public class ProfileController {

    @FXML private TextField txtProfileName;
    @FXML private TextField txtProfileDni;
    @FXML private TextField txtProfileEmail;
    @FXML private Label lblProfileStatus;

    private String windowsUsername;

    public void initialize() {
        windowsUsername = System.getProperty("user.name");
        loadProfile();
    }

    private void loadProfile() {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT name, dni, email FROM TECHNICIAN_PROFILE WHERE windows_username = ?")) {
            ps.setString(1, windowsUsername);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                txtProfileName.setText(rs.getString("name"));
                txtProfileDni.setText(rs.getString("dni"));
                txtProfileEmail.setText(rs.getString("email"));
            }
        } catch (Exception e) {
            lblProfileStatus.setText("Error al cargar el perfil");
        }
    }

    @FXML
    private void handleSave() {
        String name = txtProfileName.getText().trim();
        String dni = txtProfileDni.getText().trim();
        String email = txtProfileEmail.getText().trim();

        if (name.isEmpty()) {
            lblProfileStatus.setStyle("-fx-text-fill: #ef4444;");
            lblProfileStatus.setText("El nombre es obligatorio");
            return;
        }

        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement("""
                INSERT INTO TECHNICIAN_PROFILE (windows_username, name, dni, email)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(windows_username) DO UPDATE SET name=excluded.name, dni=excluded.dni, email=excluded.email
                """)) {
            ps.setString(1, windowsUsername);
            ps.setString(2, name);
            ps.setString(3, dni);
            ps.setString(4, email);
            ps.executeUpdate();
            lblProfileStatus.setStyle("-fx-text-fill: #0c8570;");
            lblProfileStatus.setText("Perfil guardado correctamente");
        } catch (Exception e) {
            lblProfileStatus.setStyle("-fx-text-fill: #ef4444;");
            lblProfileStatus.setText("Error al guardar el perfil");
        }
    }
}
