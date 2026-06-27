package com.bunshock.note_app_for_it_frontend.controllers;

import java.sql.Connection;
import java.sql.PreparedStatement;

import com.bunshock.note_app_for_it_frontend.models.AppConfig;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.DatabaseService;
import com.bunshock.note_app_for_it_frontend.services.WindowsDPAPIService;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class SettingsController {

    @FXML private TextField txtAfPrefix;
    @FXML private TextField txtAfSeparator;
    @FXML private TextField txtAfLength;
    @FXML private TextField txtAfFiller;
    @FXML private Label lblAfPreview;

    @FXML private TextField txtSmtpSender;
    @FXML private PasswordField pfSmtpPassword;
    @FXML private Label lblSmtpStatus;

    @FXML private TextField txtGlpiUrl;

    public void initialize() {
        AppConfig config = ConfigService.getInstance().getConfig();

        txtAfPrefix.setText(config.afFormat.prefix);
        txtAfSeparator.setText(config.afFormat.separator);
        txtAfLength.setText(String.valueOf(config.afFormat.length));
        txtAfFiller.setText(config.afFormat.filler);
        txtSmtpSender.setText(config.smtp.senderAddress);
        txtGlpiUrl.setText(config.glpiApi.baseUrl);

        txtAfPrefix.textProperty().addListener((o, a, b) -> updateAfPreview());
        txtAfSeparator.textProperty().addListener((o, a, b) -> updateAfPreview());
        txtAfLength.textProperty().addListener((o, a, b) -> updateAfPreview());
        txtAfFiller.textProperty().addListener((o, a, b) -> updateAfPreview());
        updateAfPreview();
    }

    private void updateAfPreview() {
        try {
            String prefix = txtAfPrefix.getText();
            String sep = txtAfSeparator.getText();
            int length = Integer.parseInt(txtAfLength.getText().trim());
            String filler = txtAfFiller.getText().isEmpty() ? "0" : txtAfFiller.getText().substring(0, 1);
            String example = filler.repeat(Math.max(0, length - 3)) + "512";
            lblAfPreview.setText("Vista previa: " + prefix + sep + example.substring(Math.max(0, example.length() - length)));
        } catch (NumberFormatException e) {
            lblAfPreview.setText("Vista previa: —");
        }
    }

    @FXML
    private void handleSave() {
        AppConfig config = ConfigService.getInstance().getConfig();

        config.afFormat.prefix = txtAfPrefix.getText().trim();
        config.afFormat.separator = txtAfSeparator.getText();
        config.afFormat.filler = txtAfFiller.getText().isEmpty() ? "0" : txtAfFiller.getText().substring(0, 1);
        config.smtp.senderAddress = txtSmtpSender.getText().trim();
        config.glpiApi.baseUrl = txtGlpiUrl.getText().trim();

        try {
            config.afFormat.length = Integer.parseInt(txtAfLength.getText().trim());
        } catch (NumberFormatException e) {
            lblSmtpStatus.setStyle("-fx-text-fill: #ef4444;");
            lblSmtpStatus.setText("La longitud de A/F debe ser un número");
            return;
        }

        String password = pfSmtpPassword.getText();
        if (!password.isBlank()) {
            saveSmtpPassword(password);
        }

        try {
            ConfigService.getInstance().save();
            lblSmtpStatus.setStyle("-fx-text-fill: #0c8570;");
            lblSmtpStatus.setText("Configuración guardada");
        } catch (Exception e) {
            lblSmtpStatus.setStyle("-fx-text-fill: #ef4444;");
            lblSmtpStatus.setText("Error al guardar la configuración");
        }
    }

    private void saveSmtpPassword(String plainPassword) {
        String encrypted = WindowsDPAPIService.getInstance().encrypt(plainPassword);
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO APP_SETTINGS (key, value) VALUES ('smtp_password', ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, encrypted);
            ps.executeUpdate();
            pfSmtpPassword.clear();
        } catch (Exception e) {
            lblSmtpStatus.setStyle("-fx-text-fill: #ef4444;");
            lblSmtpStatus.setText("Error al guardar la contraseña");
        }
    }
}
