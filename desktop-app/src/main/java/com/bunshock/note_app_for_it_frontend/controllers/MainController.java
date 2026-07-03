package com.bunshock.note_app_for_it_frontend.controllers;

import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.RemoteDatabaseService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;
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
        TechnicianSessionService.getInstance().addOnChangeListener(this::updateWelcomeLabels);
        updateWelcomeLabels();
        refreshTechnicianSessionAsync();

        startStatusMonitor();
        showSection(viewFactory.getGeneratorView());

        navigationGroup.selectedToggleProperty().addListener((obs, old, newVal) -> {
            if (newVal == null) old.setSelected(true);
        });

        AdminSession.getInstance().addOnActivateListener(this::updateAdminIndicator);
        AdminSession.getInstance().addOnDeactivateListener(this::updateAdminIndicator);
        AdminSession.getInstance().addOnExpireListener(this::handleAdminExpiry);
    }

    private void updateWelcomeLabels() {
        TechnicianSessionService session = TechnicianSessionService.getInstance();
        String name = session.getName();
        String username = session.getUsername();
        lblWelcome.setText(name != null ? "Hola, " + extractFirstName(name) + "!" : "Hola!");
        lblUsername.setText(username != null ? "Usuario: " + username : "Perfil no configurado");
    }

    private void refreshTechnicianSessionAsync() {
        Thread t = new Thread(() -> {
            TechnicianSessionService session = TechnicianSessionService.getInstance();
            session.refreshFromWindowsSession();
            if (!session.isResolved()) {
                Platform.runLater(() -> showWarningNotice("Perfil de técnico no disponible",
                    session.getLastError() + " Puede reintentar desde Mi Perfil con \"Actualizar Perfil desde AD\"."));
            }
        }, "technician-session-startup");
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
        showNotice("Sesión finalizada",
            "El modo administrador expiró por inactividad (15 minutos).");
    }

    private void showNotice(String title, String message) {
        showDialogNotice(title, message, "#1a1a1a", null);
    }

    private void showWarningNotice(String title, String message) {
        showDialogNotice(title, message, "#f59e0b", "⚠");
    }

    private void showDialogNotice(String title, String message, String accentColor, String icon) {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (icon != null) {
            Label lblIcon = new Label(icon);
            lblIcon.setStyle("-fx-font-size: 16px; -fx-text-fill: " + accentColor + ";");
            titleRow.getChildren().add(lblIcon);
        }
        Label lblT = new Label(title);
        lblT.getStyleClass().add("section-label");
        titleRow.getChildren().add(lblT);

        Label lblMsg = new Label(message);
        lblMsg.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");
        lblMsg.setWrapText(true);

        Button btnOk = new Button("Aceptar");
        btnOk.getStyleClass().add("button-primary");
        btnOk.setOnAction(e -> stage.close());

        HBox buttons = new HBox(btnOk);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(360, accentColor);
        root.getChildren().addAll(titleRow, lblMsg, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();
    }

    private Stage buildDialogStage() {
        Stage s = new Stage();
        s.initStyle(StageStyle.TRANSPARENT);
        s.initModality(Modality.APPLICATION_MODAL);
        return s;
    }

    private void centerOnContent(Stage stage) {
        stage.setOpacity(0);
        stage.setOnShown(e -> {
            javafx.geometry.Bounds b = contentArea.localToScreen(contentArea.getBoundsInLocal());
            if (b != null) {
                stage.setX(b.getMinX() + (b.getWidth()  - stage.getWidth())  / 2);
                stage.setY(b.getMinY() + (b.getHeight() - stage.getHeight()) / 2);
            }
            stage.setOpacity(1);
        });
    }

    private VBox buildDialogRoot(double prefWidth, String accentColor) {
        VBox root = new VBox(14);
        root.setPrefWidth(prefWidth);
        root.setStyle("""
            -fx-background-color: %s, white;
            -fx-background-radius: 12, 10;
            -fx-background-insets: 0, 2;
            -fx-padding: 24;
            -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 20, 0, 0, 5);
            """.formatted(accentColor));
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
