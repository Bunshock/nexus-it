package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.bunshock.note_app_for_it_frontend.models.update.ChangelogEntry;
import com.bunshock.note_app_for_it_frontend.models.update.UpdateInfo;
import com.bunshock.note_app_for_it_frontend.services.update.IUpdateService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.utils.update.AppVersion;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class AboutController {

    @FXML private Label lblVersion;
    @FXML private Label lblManualVersion;
    @FXML private VBox panelAbout;
    @FXML private VBox panelManual;

    @FXML private Button btnCheckUpdates;
    @FXML private Button btnChangelog;

    public void initialize() {
        lblVersion.setText("Versión " + AppVersion.getCurrentVersion());
        // The manual's own portada used to hardcode a version string independent of lblVersion
        // above — silently drifted out of sync with the real build version. Both must read from
        // the same source now so they can't diverge again.
        lblManualVersion.setText("Versión:            " + AppVersion.getCurrentVersion());
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

    // ── Auto-update ──────────────────────────────────────────────────
    // Every check result (found/not found/unconfigured) surfaces in a popup dialog rather than
    // inline in the card — a prior inline-label/button version made the card's height shift
    // between states (and left a permanent dead-space gap while idle, to always reserve room for
    // it); a dialog can never affect this panel's layout at all, by construction. See CLAUDE.md's
    // "Auto-update system" section.

    @FXML
    private void handleCheckUpdates() {
        IUpdateService updateService = ServiceLocator.getInstance().getUpdateService();
        if (updateService == null || !updateService.isConfigured()) {
            showInfoDialog("Actualizaciones", "Las actualizaciones automáticas no están configuradas.");
            return;
        }

        btnCheckUpdates.setDisable(true);
        String originalText = btnCheckUpdates.getText();
        btnCheckUpdates.setText("Buscando...");

        String currentVersion = AppVersion.getCurrentVersion();
        Thread t = new Thread(() -> {
            Optional<UpdateInfo> result = updateService.checkForUpdate(currentVersion);
            Platform.runLater(() -> {
                btnCheckUpdates.setDisable(false);
                btnCheckUpdates.setText(originalText);
                if (result.isPresent()) {
                    showUpdateFoundDialog(result.get());
                } else {
                    showInfoDialog("Actualizaciones", "Ya tenés la última versión.");
                }
            });
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void handleShowChangelog() {
        IUpdateService updateService = ServiceLocator.getInstance().getUpdateService();
        if (updateService == null || !updateService.isConfigured()) {
            showInfoDialog("Actualizaciones", "Las actualizaciones automáticas no están configuradas.");
            return;
        }

        btnChangelog.setDisable(true);
        String originalText = btnChangelog.getText();
        btnChangelog.setText("Cargando...");

        Thread t = new Thread(() -> {
            List<ChangelogEntry> entries = updateService.getChangelog();
            Platform.runLater(() -> {
                btnChangelog.setDisable(false);
                btnChangelog.setText(originalText);
                showChangelogDialog(entries);
            });
        }, "changelog-load");
        t.setDaemon(true);
        t.start();
    }

    /** Combines the "found a newer version" notice and the "confirm before closing" step into
     * one dialog — download/install progress and any error are shown inline in this same dialog
     * (it stays open throughout), rather than closing immediately and reporting elsewhere. */
    private void showUpdateFoundDialog(UpdateInfo info) {
        IUpdateService updateService = ServiceLocator.getInstance().getUpdateService();
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Actualización disponible");
        lblTitle.getStyleClass().add("section-label");

        StringBuilder msg = new StringBuilder("Hay una nueva versión disponible (")
            .append(info.getVersion()).append(").");
        if (info.getNotes() != null && !info.getNotes().isBlank()) {
            msg.append("\n\n").append(info.getNotes());
        }
        msg.append("\n\nLa aplicación se va a cerrar para instalar la actualización — guardá "
            + "cualquier nota en curso antes de continuar.");
        Label lblMsg = new Label(msg.toString());
        lblMsg.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");
        lblMsg.setWrapText(true);

        Label lblProgress = new Label("");
        lblProgress.setWrapText(true);
        lblProgress.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnConfirm = new Button("Actualizar ahora");
        btnConfirm.setStyle("-fx-background-color: #0c8570; -fx-text-fill: white; "
            + "-fx-background-radius: 6; -fx-font-weight: bold; -fx-cursor: hand;");
        btnConfirm.setOnAction(e -> {
            btnCancel.setDisable(true);
            btnConfirm.setDisable(true);
            lblProgress.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");
            lblProgress.setText("Descargando actualización...");
            // The Stage only auto-sizes to its content once, at the initial showAndWait() —
            // lblProgress's text is set after that, from a background thread, so without this
            // the window doesn't grow and a longer message (especially the error case below)
            // renders past the window's edge instead of wrapping into view.
            stage.sizeToScene();

            Thread t = new Thread(() -> {
                try {
                    updateService.downloadAndInstall(info);
                    // Success: the relaunch helper is already running as its own detached
                    // process — this app must close now so the installer can overwrite its
                    // files (Windows won't allow that while this process still has them open).
                    Platform.runLater(Platform::exit);
                } catch (IOException ex) {
                    // Closes this dialog and opens a brand-new one for the error, rather than
                    // growing this one in place — a fresh dialog sizes itself to its own content
                    // from the start, so there's no resize/sizeToScene() involved at all here,
                    // regardless of how long the error message turns out to be. The second
                    // Platform.runLater is required, not redundant: stage.close() on a
                    // showAndWait()-blocked Stage only *requests* its nested event loop to exit —
                    // that exit is processed after the current event finishes, so opening another
                    // showAndWait() dialog in the very same callback raced with that pending exit
                    // and silently never showed. Deferring to a fresh runLater lets the close
                    // fully unwind first.
                    Platform.runLater(() -> {
                        stage.close();
                        Platform.runLater(() -> showInfoDialog("No se pudo actualizar",
                            "No se pudo instalar la actualización: " + ex.getMessage()));
                    });
                }
            }, "update-install");
            t.setDaemon(true);
            t.start();
        });

        HBox buttons = new HBox(8, btnCancel, btnConfirm);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380);
        root.getChildren().addAll(lblTitle, lblMsg, lblProgress, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();
    }

    /** Plain single-message, single-button notice — "not configured" / "already up to date". */
    private void showInfoDialog(String title, String message) {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label(title);
        lblTitle.getStyleClass().add("section-label");

        Label lblMsg = new Label(message);
        lblMsg.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");
        lblMsg.setWrapText(true);

        Button btnClose = new Button("Cerrar");
        btnClose.getStyleClass().add("button-secondary");
        btnClose.setOnAction(e -> stage.close());
        HBox buttons = new HBox(btnClose);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(340);
        root.getChildren().addAll(lblTitle, lblMsg, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();
    }

    private void showChangelogDialog(List<ChangelogEntry> entries) {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Historial de cambios");
        lblTitle.getStyleClass().add("section-label");

        VBox entriesBox = new VBox(10);
        if (entries.isEmpty()) {
            Label lblEmpty = new Label("No hay historial de cambios disponible.");
            lblEmpty.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12px; -fx-font-style: italic;");
            entriesBox.getChildren().add(lblEmpty);
        } else {
            for (ChangelogEntry entry : entries) {
                VBox entryBox = new VBox(2);
                Label lblEntryHeader = new Label(
                    entry.getVersion() + (entry.getDate() != null && !entry.getDate().isBlank()
                        ? "  ·  " + entry.getDate() : ""));
                lblEntryHeader.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #0c8570;");
                Label lblEntryNotes = new Label(entry.getNotes() != null ? entry.getNotes() : "");
                lblEntryNotes.setWrapText(true);
                lblEntryNotes.setStyle("-fx-font-size: 12px; -fx-text-fill: #475569;");
                entryBox.getChildren().addAll(lblEntryHeader, lblEntryNotes);
                entriesBox.getChildren().add(entryBox);
                entriesBox.getChildren().add(new Separator());
            }
        }

        ScrollPane scroll = new ScrollPane(entriesBox);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(320);
        scroll.setStyle("-fx-background: white; -fx-background-color: white;");

        Button btnClose = new Button("Cerrar");
        btnClose.getStyleClass().add("button-secondary");
        btnClose.setOnAction(e -> stage.close());
        HBox buttons = new HBox(btnClose);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(440);
        root.getChildren().addAll(lblTitle, scroll, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();
    }

    // ── Dialog helpers ───────────────────────────────────────────────
    // Duplicated per this codebase's no-shared-abstraction convention — same four methods as
    // SettingsController/MainController/DatabaseSectionController's own copies.

    private void centerOnContent(Stage stage) {
        stage.setOpacity(0);
        stage.setOnShown(e -> {
            javafx.geometry.Bounds b = panelAbout.localToScreen(panelAbout.getBoundsInLocal());
            if (b != null) {
                stage.setX(b.getMinX() + (b.getWidth()  - stage.getWidth())  / 2);
                stage.setY(b.getMinY() + (b.getHeight() - stage.getHeight()) / 2);
            }
            stage.setOpacity(1);
        });
    }

    private Stage buildDialogStage() {
        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.initModality(Modality.APPLICATION_MODAL);
        return stage;
    }

    private VBox buildDialogRoot(double prefWidth) {
        VBox root = new VBox(14);
        root.setPrefWidth(prefWidth);
        root.setStyle("""
            -fx-background-color: #1a1a1a, white;
            -fx-background-radius: 12, 10;
            -fx-background-insets: 0, 2;
            -fx-padding: 24;
            -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 20, 0, 0, 5);
            """);
        return root;
    }

    private Scene buildDialogScene(VBox content) {
        javafx.scene.layout.StackPane wrapper = new javafx.scene.layout.StackPane(content);
        wrapper.setStyle("-fx-background-color: transparent; -fx-padding: 20;");
        Scene scene = new Scene(wrapper);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource(
            "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());
        return scene;
    }
}
