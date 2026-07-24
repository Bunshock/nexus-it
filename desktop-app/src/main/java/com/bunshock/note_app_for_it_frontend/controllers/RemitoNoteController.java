package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.regex.Pattern;

import com.bunshock.note_app_for_it_frontend.services.ConfigService;

import javafx.animation.FadeTransition;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.util.Duration;

public class RemitoNoteController {

    private static final Pattern NAME_PATTERN =
        Pattern.compile("^\\p{L}+( \\p{L}+)*$");

    private static final int AREA_MAX_LENGTH = 100;
    private static final int SEDE_MAX_LENGTH = 200;

    // Matches every other note form's fade timing so validation errors behave identically
    // across Usuario/Proveedor/Remito.
    private static final Duration ERROR_HOLD = Duration.millis(2000);
    private static final Duration ERROR_FADE = Duration.millis(650);

    @FXML private TextField txtDestinatarioName;
    @FXML private TextField txtDestinatarioArea;
    @FXML private TextField txtDestinatarioSede;
    @FXML private Label lblDestinatarioStatus;

    // Remitente is the IT Support coordinator signing off the shipment — not necessarily the
    // technician generating the note (that identity is still captured separately, on
    // NOTE_REPORT.technician_name/technician_dni, for traceability, just never printed on this
    // template). Nombre is deliberately manual with no default — a different coordinator may
    // send any given shipment. Área/Sede are editable too, but pre-filled from app-config.json's
    // remito.remitenteArea/remitenteSede as a starting point, since those rarely change.
    @FXML private TextField txtRemitenteName;
    @FXML private TextField txtRemitenteArea;
    @FXML private TextField txtRemitenteSede;
    @FXML private Label lblRemitenteStatus;

    public void initialize() {
        txtDestinatarioArea.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= AREA_MAX_LENGTH ? change : null));
        txtDestinatarioSede.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= SEDE_MAX_LENGTH ? change : null));
        txtRemitenteArea.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= AREA_MAX_LENGTH ? change : null));
        txtRemitenteSede.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= SEDE_MAX_LENGTH ? change : null));

        applyRemitenteDefaults();
    }

    /**
     * Seeds Remitente Área/Sede with the app-config.json defaults — called once on
     * initialize() and again from clearAllFields()'s "Limpiar" action, which restores the
     * defaults rather than blanking them (unlike Destinatario/Remitente Nombre, which have
     * no default to restore).
     */
    private void applyRemitenteDefaults() {
        var remitoConfig = ConfigService.getInstance().getConfig().remito;
        txtRemitenteArea.setText(remitoConfig != null ? orEmpty(remitoConfig.remitenteArea) : "");
        txtRemitenteSede.setText(remitoConfig != null ? orEmpty(remitoConfig.remitenteSede) : "");
    }

    public boolean validateAndShowErrors() {
        String destName = txtDestinatarioName.getText().trim();
        String destArea = txtDestinatarioArea.getText().trim();
        String destSede = txtDestinatarioSede.getText().trim();

        if (destName.isEmpty()) {
            showDestinatarioError("Debe ingresar el nombre del destinatario");
            return false;
        }
        if (!NAME_PATTERN.matcher(destName).matches()) {
            showDestinatarioError("El nombre solo puede contener letras y espacios simples");
            return false;
        }
        if (destArea.isEmpty()) {
            showDestinatarioError("Debe ingresar el área del destinatario");
            return false;
        }
        if (destSede.isEmpty()) {
            showDestinatarioError("Debe ingresar la sede del destinatario");
            return false;
        }
        lblDestinatarioStatus.setText("");

        String remName = txtRemitenteName.getText().trim();
        String remArea = txtRemitenteArea.getText().trim();
        String remSede = txtRemitenteSede.getText().trim();

        if (remName.isEmpty()) {
            showRemitenteError("Debe ingresar el nombre del remitente");
            return false;
        }
        if (!NAME_PATTERN.matcher(remName).matches()) {
            showRemitenteError("El nombre solo puede contener letras y espacios simples");
            return false;
        }
        if (remArea.isEmpty()) {
            showRemitenteError("Debe ingresar el área del remitente");
            return false;
        }
        if (remSede.isEmpty()) {
            showRemitenteError("Debe ingresar la sede del remitente");
            return false;
        }
        lblRemitenteStatus.setText("");
        return true;
    }

    private void showDestinatarioError(String message) {
        fadeOutError(lblDestinatarioStatus, message);
    }

    private void showRemitenteError(String message) {
        fadeOutError(lblRemitenteStatus, message);
    }

    private void fadeOutError(Label label, String message) {
        label.setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
        label.setText(message);
        label.setOpacity(1.0);
        FadeTransition fade = new FadeTransition(ERROR_FADE, label);
        fade.setDelay(ERROR_HOLD);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            label.setText("");
            label.setStyle("");
            label.setOpacity(1.0);
        });
        fade.play();
    }

    public String getDestinatarioName() { return txtDestinatarioName.getText().trim(); }
    public String getDestinatarioArea() { return txtDestinatarioArea.getText().trim(); }
    public String getDestinatarioSede() { return txtDestinatarioSede.getText().trim(); }
    public String getRemitenteName() { return txtRemitenteName.getText().trim(); }
    public String getRemitenteArea() { return txtRemitenteArea.getText().trim(); }
    public String getRemitenteSede() { return txtRemitenteSede.getText().trim(); }

    public void clearAllFields() {
        txtDestinatarioName.clear();
        txtDestinatarioArea.clear();
        txtDestinatarioSede.clear();
        lblDestinatarioStatus.setText("");

        txtRemitenteName.clear();
        applyRemitenteDefaults();
        lblRemitenteStatus.setText("");
    }

    private String orEmpty(String s) { return s != null ? s : ""; }
}
