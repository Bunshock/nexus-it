package com.bunshock.note_app_for_it_frontend.controllers;

import java.util.concurrent.atomic.AtomicInteger;

import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.RemoteDatabaseService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;
import com.bunshock.note_app_for_it_frontend.utils.ViewFactory;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class MainController {

    // 3 is a conventional retry count for a startup connectivity check — enough to ride out
    // a transient blip without turning a genuine outage into a long wait (worst case per
    // service: 3 attempts + 2 gaps of sleepBetweenAttempts()).
    private static final int MAX_CONNECTION_ATTEMPTS = 3;

    @FXML private BorderPane rootPane;

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

        showSection(viewFactory.getGeneratorView());
        // Deferred: initialize() runs during FXMLLoader.load(), before App.start() calls
        // stage.show() — centerOnContent()'s localToScreen() needs the window already
        // shown to position the overlay correctly, so this must wait one pulse.
        Platform.runLater(this::runStartupChecks);

        navigationGroup.selectedToggleProperty().addListener((obs, old, newVal) -> {
            if (newVal == null) old.setSelected(true);
        });

        AdminSession.getInstance().addOnActivateListener(this::updateAdminIndicator);
        AdminSession.getInstance().addOnDeactivateListener(this::updateAdminIndicator);
        AdminSession.getInstance().addOnExpireListener(this::handleAdminExpiry);
    }

    private void updateWelcomeLabels() {
        TechnicianSessionService session = TechnicianSessionService.getInstance();
        String displayName = session.getDisplayName();
        String username = session.getUsername();
        lblWelcome.setText(displayName != null ? "Hola " + displayName + "!" : "Hola!");
        lblUsername.setText(username != null ? "Usuario: " + username : "Perfil no configurado");
    }

    /**
     * Runs the AD profile lookup, DB connection test, and GLPI reachability check in
     * parallel behind a blurred, non-dismissable loading overlay, so the technician sees
     * one unified startup sequence instead of the AD lookup silently taking a while in the
     * background. Feeds the same sidebar dots the periodic monitor updates later. Only
     * after the overlay closes (plus a short pause so the results are actually readable) is
     * the existing "AD lookup failed" warning shown, if the AD check failed.
     */
    private void runStartupChecks() {
        GaussianBlur blur = new GaussianBlur(20);
        rootPane.setEffect(blur);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(40, 40);
        spinner.setStyle("-fx-progress-color: #0c8570;");

        Label lblTitle = new Label("Verificando conexiones...");
        lblTitle.getStyleClass().add("section-label");

        StartupRow rowAD   = buildPendingRow("Active Directory");
        StartupRow rowDB   = buildPendingRow("Base de Datos");
        StartupRow rowGLPI = buildPendingRow("GLPI");

        VBox statusRows = new VBox(8, rowAD.container, rowDB.container, rowGLPI.container);
        statusRows.setStyle("-fx-padding: 10 0 0 0;");

        VBox root = buildDialogRoot(320, "#1a1a1a");
        root.setAlignment(Pos.CENTER);
        root.getChildren().addAll(spinner, lblTitle, statusRows);

        Stage loadingStage = buildDialogStage();
        centerOnContent(loadingStage);
        loadingStage.setScene(buildDialogScene(root));
        loadingStage.show();

        AtomicInteger remaining = new AtomicInteger(3);
        Runnable onCheckDone = () -> {
            if (remaining.decrementAndGet() == 0) {
                PauseTransition pause = new PauseTransition(Duration.seconds(3));
                pause.setOnFinished(e -> fadeOutStartupOverlay(loadingStage, root, blur));
                pause.play();
            }
        };

        // Staggered starts (1s, 2s, 3s from when the overlay appears) so the pending/loading
        // state of each row is visible for a moment, and so the three don't all flash their
        // spinners at once — each still runs independently and reports its own result
        // whenever it finishes, regardless of the others.
        delayThenRun(1, () -> startAdCheck(rowAD, onCheckDone));
        delayThenRun(2, () -> startDbCheck(rowDB, onCheckDone));
        delayThenRun(3, () -> startGlpiCheck(rowGLPI, onCheckDone));

        startStatusMonitor();
    }

    private void delayThenRun(int seconds, Runnable action) {
        PauseTransition delay = new PauseTransition(Duration.seconds(seconds));
        delay.setOnFinished(e -> action.run());
        delay.play();
    }

    /** Retries the technician-profile AD lookup up to MAX_CONNECTION_ATTEMPTS times before giving up. */
    private void startAdCheck(StartupRow row, Runnable onCheckDone) {
        Thread t = new Thread(() -> {
            boolean resolved = false;
            for (int attempt = 1; attempt <= MAX_CONNECTION_ATTEMPTS && !resolved; attempt++) {
                TechnicianSessionService.getInstance().refreshFromWindowsSession();
                resolved = TechnicianSessionService.getInstance().isResolved();
                if (!resolved && attempt < MAX_CONNECTION_ATTEMPTS) sleepBetweenAttempts();
            }
            boolean finalResolved = resolved;
            Platform.runLater(() -> {
                updateADStatus(finalResolved);
                resolveRow(row, "Active Directory",
                    ServiceLocator.getInstance().getAdService().isConfigured(), finalResolved);
                onCheckDone.run();
            });
        }, "startup-ad-check");
        t.setDaemon(true);
        t.start();
    }

    /** Retries the DB connection test up to MAX_CONNECTION_ATTEMPTS times before giving up. */
    private void startDbCheck(StartupRow row, Runnable onCheckDone) {
        Thread t = new Thread(() -> {
            boolean dbUp = false;
            for (int attempt = 1; attempt <= MAX_CONNECTION_ATTEMPTS && !dbUp; attempt++) {
                dbUp = RemoteDatabaseService.getInstance().testConnection();
                if (!dbUp && attempt < MAX_CONNECTION_ATTEMPTS) sleepBetweenAttempts();
            }
            boolean finalDbUp = dbUp;
            Platform.runLater(() -> {
                updateDBStatus(finalDbUp);
                resolveRow(row, "Base de Datos", RemoteDatabaseService.getInstance().isConfigured(), finalDbUp);
                onCheckDone.run();
            });
        }, "startup-db-check");
        t.setDaemon(true);
        t.start();
    }

    /** Retries the GLPI reachability check up to MAX_CONNECTION_ATTEMPTS times before giving up. */
    private void startGlpiCheck(StartupRow row, Runnable onCheckDone) {
        Thread t = new Thread(() -> {
            boolean glpiUp = false;
            for (int attempt = 1; attempt <= MAX_CONNECTION_ATTEMPTS && !glpiUp; attempt++) {
                glpiUp = ServiceLocator.getInstance().getGlpiService().isReachable();
                if (!glpiUp && attempt < MAX_CONNECTION_ATTEMPTS) sleepBetweenAttempts();
            }
            boolean finalGlpiUp = glpiUp;
            Platform.runLater(() -> {
                updateGLPIStatus(finalGlpiUp);
                resolveRow(row, "GLPI", true, finalGlpiUp);
                onCheckDone.run();
            });
        }, "startup-glpi-check");
        t.setDaemon(true);
        t.start();
    }

    private void sleepBetweenAttempts() {
        try { Thread.sleep(500); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /** Holds the mutable pieces of one startup-overlay row so it can transition from pending to resolved. */
    private static class StartupRow {
        final HBox container;
        final StackPane indicatorSlot;
        final Label lblService;
        final Label lblStatus;
        final Timeline ellipsis;

        StartupRow(HBox container, StackPane indicatorSlot, Label lblService, Label lblStatus, Timeline ellipsis) {
            this.container = container;
            this.indicatorSlot = indicatorSlot;
            this.lblService = lblService;
            this.lblStatus = lblStatus;
            this.ellipsis = ellipsis;
        }
    }

    /** Builds one row in its pending state: a mini spinner, a greyed-out label with a cycling "..." animation. */
    private StartupRow buildPendingRow(String serviceName) {
        ProgressIndicator miniSpinner = new ProgressIndicator();
        miniSpinner.setMinSize(16, 16);
        miniSpinner.setMaxSize(16, 16);
        miniSpinner.setStyle("-fx-progress-color: #0c8570;");

        StackPane indicatorSlot = new StackPane(miniSpinner);
        indicatorSlot.setMinWidth(16);

        Label lblService = new Label(serviceName + ".");
        lblService.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12px;");

        Label lblStatus = new Label("");
        lblStatus.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(8, indicatorSlot, lblService, spacer, lblStatus);
        row.setAlignment(Pos.CENTER_LEFT);

        int[] dotCount = {1};
        Timeline ellipsis = new Timeline(new KeyFrame(Duration.millis(450), e -> {
            dotCount[0] = dotCount[0] % 3 + 1;
            lblService.setText(serviceName + ".".repeat(dotCount[0]));
        }));
        ellipsis.setCycleCount(Timeline.INDEFINITE);
        ellipsis.play();

        return new StartupRow(row, indicatorSlot, lblService, lblStatus, ellipsis);
    }

    /** Transitions a row from pending to resolved: real status dot, plain label, EN LÍNEA/DESCONECTADO text. */
    private void resolveRow(StartupRow row, String serviceName, boolean configured, boolean online) {
        row.ellipsis.stop();
        row.lblService.setText(serviceName);
        row.lblService.setStyle("-fx-text-fill: #334155; -fx-font-size: 12px;");

        row.indicatorSlot.getChildren().setAll(new Circle(5, statusColor(configured, online)));

        if (!configured) {
            row.lblStatus.setText("NO CONFIGURADO");
            row.lblStatus.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px; -fx-font-weight: bold;");
        } else if (online) {
            row.lblStatus.setText("EN LÍNEA");
            row.lblStatus.setStyle("-fx-text-fill: #22c55e; -fx-font-size: 11px; -fx-font-weight: bold;");
        } else {
            row.lblStatus.setText("DESCONECTADO");
            row.lblStatus.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 11px; -fx-font-weight: bold;");
        }
    }

    /** Fades the loading card and the background blur out together, then closes the overlay. */
    private void fadeOutStartupOverlay(Stage loadingStage, VBox root, GaussianBlur blur) {
        Duration fadeDuration = Duration.millis(700);

        FadeTransition fade = new FadeTransition(fadeDuration, root);
        fade.setFromValue(1);
        fade.setToValue(0);

        Timeline unblur = new Timeline(new KeyFrame(fadeDuration, new KeyValue(blur.radiusProperty(), 0)));

        ParallelTransition fadeOut = new ParallelTransition(fade, unblur);
        fadeOut.setOnFinished(e -> {
            loadingStage.close();
            rootPane.setEffect(null);
            TechnicianSessionService session = TechnicianSessionService.getInstance();
            if (!session.isResolved()) {
                showWarningNotice("Perfil de técnico no disponible",
                    session.getLastError() + " Puede reintentar desde Mi Perfil con \"Actualizar Perfil desde AD\".");
            }
        });
        fadeOut.play();
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
        service.setDelay(Duration.seconds(60));
        service.setPeriod(Duration.seconds(60));
        service.setOnSucceeded(e -> {
            boolean[] r = service.getValue();
            updateADStatus(r[0]);
            updateGLPIStatus(r[1]);
            updateDBStatus(r[2]);
        });
        service.start();
    }

    private boolean checkAdReachable() {
        String username = TechnicianSessionService.getInstance().getUsername();
        if (username == null || username.isBlank()) return false;
        try {
            ServiceLocator.getInstance().getAdService().search(null, null, username);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Color statusColor(boolean configured, boolean online) {
        if (!configured) return Color.web("#94a3b8");
        return online ? Color.web("#22c55e") : Color.web("#ef4444");
    }

    private void updateADStatus(boolean online) {
        boolean configured = ServiceLocator.getInstance().getAdService().isConfigured();
        circleAD.setFill(statusColor(configured, online));
        tooltipAD.setText("Active Directory: " + (!configured ? "No configurado" : (online ? "En línea" : "Desconectado")));
    }

    private void updateGLPIStatus(boolean online) {
        circleGLPI.setFill(online ? Color.web("#22c55e") : Color.web("#ef4444"));
        tooltipGLPI.setText("GLPI API: " + (online ? "En línea" : "Desconectado"));
    }

    private void updateDBStatus(boolean online) {
        boolean configured = RemoteDatabaseService.getInstance().isConfigured();
        circleDB.setFill(statusColor(configured, online));
        tooltipDB.setText("Base de datos remota: " + (!configured ? "No configurada" : (online ? "En línea" : "Desconectada")));
    }

    private void updateAdminIndicator() {
        boolean active = AdminSession.getInstance().isActive();
        lblAdminIndicator.setVisible(active);
        // managed must follow visible, not just default true — otherwise the label still
        // reserves its layout space while hidden, which would throw off the welcome/username
        // block's vertical centering (MainView.fxml) for the common non-admin case.
        lblAdminIndicator.setManaged(active);
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

    @FXML private void handleShowGenerator() { showSection(viewFactory.getGeneratorView()); }

    @FXML
    private void handleShowHistory() {
        showSection(viewFactory.getHistoryView());
        // ViewFactory caches the History section for the session (see ViewFactory's doc), so
        // without this, notes generated after the first visit wouldn't appear until the
        // technician manually clicked "Buscar" — refresh() re-runs the currently-set filters
        // rather than resetting them.
        viewFactory.getHistoryController().refresh();
    }
    @FXML private void handleShowDatabase()  { showSection(viewFactory.getDatabaseView()); }
    @FXML private void handleShowSettings()  { showSection(viewFactory.getSettingsView()); }
    @FXML private void handleShowAbout()     { showSection(viewFactory.getAboutView()); }
    @FXML private void handleShowProfile()   { showSection(viewFactory.getProfileView()); }
}
