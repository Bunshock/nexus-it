package com.bunshock.note_app_for_it_frontend.controllers;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class AboutController {

    @FXML private Label lblVersion;
    @FXML private VBox panelAbout;
    @FXML private VBox panelManual;

    public void initialize() {
        lblVersion.setText("Versión 1.0.0");
    }

    @FXML
    private void onOpenManual() {
        panelAbout.setVisible(false);
        panelAbout.setManaged(false);
        panelManual.setVisible(true);
        panelManual.setManaged(true);
    }

    @FXML
    private void onBack() {
        panelManual.setVisible(false);
        panelManual.setManaged(false);
        panelAbout.setVisible(true);
        panelAbout.setManaged(true);
    }
}
