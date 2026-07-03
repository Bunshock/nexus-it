package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ADUser;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.RemoteDatabaseService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.utils.ViewFactory;

import javafx.application.Platform;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class MainController {

    @FXML private Label lblWelcome;
    @FXML private Label lblUsername;
    @FXML private Label lblAdminIndicator;

    @FXML private Circle circleAD;
    @FXML private Circle circleGLPI;
    @FXML private Circle circleDB;
    @FXML private Tooltip tooltipAD;
    @FXML private Tooltip tooltipGLPI;
    @FXML private Tooltip tooltipDB;

    @FXML private StackPane contentArea;
    @FXML private javafx.scene.control.ToggleGroup navigationGroup;

    private final ViewFactory viewFactory = new ViewFactory();

    public void initialize() {
        String windowsUser = System.getProperty("user.name");
        lblUsername.setText("Usuario: " + windowsUser);
        lblWelcome.setText("Hola, " + extractFirstName(windowsUser) + "!");

        lookupCurrentUserAsync(windowsUser);
        startStatusMonitor();
        showSection(viewFactory.getGeneratorView());

        navigationGroup.selectedToggleProperty().addListener((obs, old, newVal) -> {
            if (newVal == null) old.setSelected(true);
        });

        AdminSession.getInstance().addOnActivateListener(this::updateAdminIndicator);
        AdminSession.getInstance().addOnDeactivateListener(this::updateAdminIndicator);
        AdminSession.getInstance().addOnExpireListener(this::handleAdminExpiry);
    }

    private void lookupCurrentUserAsync(String windowsUser) {
        Thread t = new Thread(() -> {
            try {
                List<ADUser> results = ServiceLocator.getInstance().getAdService()
                    .search(null, null, windowsUser);
                if (!results.isEmpty()) {
                    String firstName = extractFirstName(results.get(0).getFullName());
                    Platform.runLater(() -> lblWelcome.setText("Hola, " + firstName + "!"));
                }
            } catch (Exception ignored) {}
        }, "ad-startup-lookup");
        t.setDaemon(true);
        t.start();
    }

    private void startStatusMonitor() {
        ScheduledService<boolean[]> service = new ScheduledService<>() {
            @Override
            protected Task<boolean[]> createTask() {
                return new Task<>() {
                    @Override
                    protected boolean[] call() {
                        boolean adUp   = checkAdReachable();
                        boolean glpiUp = ServiceLocator.getInstance().getGlpiService().isReachable();
                        boolean dbUp   = RemoteDatabaseService.getInstance().testConnection();
                        return new boolean[]{adUp, glpiUp, dbUp};
                    }
                };
            }
        };
        service.setPeriod(Duration.seconds(60));
        service.setOnSucceeded(e -> {
            boolean[] r = service.getValue();
            updateADStatus(r[0]);
            updateGLPIStatus(r[1]);
            updateDBStatus(r[2]);
        });
        service.start();

        Thread initial = new Thread(() -> {
            boolean adUp   = checkAdReachable();
            boolean glpiUp = ServiceLocator.getInstance().getGlpiService().isReachable();
            boolean dbUp   = RemoteDatabaseService.getInstance().testConnection();
            Platform.runLater(() -> {
                updateADStatus(adUp);
                updateGLPIStatus(glpiUp);
                updateDBStatus(dbUp);
            });
        }, "status-initial-check");
        initial.setDaemon(true);
        initial.start();
    }

    private boolean checkAdReachable() {
        try {
            ServiceLocator.getInstance().getAdService().search(null, "ping", null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateADStatus(boolean online) {
        circleAD.setFill(online ? Color.web("#22c55e") : Color.web("#ef4444"));
        tooltipAD.setText("Active Directory: " + (online ? "En línea" : "Desconectado"));
    }

    private void updateGLPIStatus(boolean online) {
        circleGLPI.setFill(online ? Color.web("#22c55e") : Color.web("#ef4444"));
        tooltipGLPI.setText("GLPI API: " + (online ? "En línea" : "Desconectado"));
    }

    private void updateDBStatus(boolean online) {
        if (!RemoteDatabaseService.getInstance().isConfigured()) {
            circleDB.setFill(Color.web("#94a3b8"));
            tooltipDB.setText("Base de datos remota: No configurada");
        } else {
            circleDB.setFill(online ? Color.web("#22c55e") : Color.web("#ef4444"));
            tooltipDB.setText("Base de datos remota: " + (online ? "En línea" : "Desconectada"));
        }
    }

    private void updateAdminIndicator() {
        lblAdminIndicator.setVisible(AdminSession.getInstance().isActive());
    }

    private void handleAdminExpiry() {
        showAdminNotice("Sesión finalizada",
            "El modo administrador expiró por inactividad (15 minutos).");
    }

    private void showAdminNotice(String title, String message) {
        Stage stage = buildDialogStage();

        Label lblT = new Label(title);
        lblT.getStyleClass().add("section-label");

        Label lblMsg = new Label(message);
        lblMsg.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");
        lblMsg.setWrapText(true);

        Button btnOk = new Button("Aceptar");
        btnOk.getStyleClass().add("button-primary");
        btnOk.setOnAction(e -> stage.close());

        HBox buttons = new HBox(btnOk);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(360);
        root.getChildren().addAll(lblT, lblMsg, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();
    }

    private Stage buildDialogStage() {
        Stage s = new Stage();
        s.initStyle(StageStyle.TRANSPARENT);
        s.initModality(Modality.APPLICATION_MODAL);
        s.setOpacity(0);
        s.setOnShown(e -> { s.centerOnScreen(); s.setOpacity(1); });
        return s;
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
        StackPane wrapper = new StackPane(content);
        wrapper.setStyle("-fx-background-color: transparent; -fx-padding: 20;");
        Scene scene = new Scene(wrapper);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource(
            "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());
        return scene;
    }

    private void showSection(Parent view) {
        contentArea.getChildren().setAll(view);
    }

    private String extractFirstName(String input) {
        if (input == null || input.isBlank()) return input;
        String first = input.trim().split("[._ ]+")[0];
        return first.isEmpty() ? input : Character.toUpperCase(first.charAt(0)) + first.substring(1).toLowerCase();
    }

    @FXML private void handleShowGenerator() { showSection(viewFactory.getGeneratorView()); }
    @FXML private void handleShowHistory()   { showSection(viewFactory.getHistoryView()); }
    @FXML private void handleShowDatabase()  { showSection(viewFactory.getDatabaseView()); }
    @FXML private void handleShowSettings()  { showSection(viewFactory.getSettingsView()); }
    @FXML private void handleShowAbout()     { showSection(viewFactory.getAboutView()); }
    @FXML private void handleShowProfile()   { showSection(viewFactory.getProfileView()); }
}
