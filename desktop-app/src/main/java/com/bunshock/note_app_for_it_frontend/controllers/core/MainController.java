package com.bunshock.note_app_for_it_frontend.controllers.core;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.bunshock.note_app_for_it_frontend.App;
import com.bunshock.note_app_for_it_frontend.models.history.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.auth.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.history.IHistoryService;
import com.bunshock.note_app_for_it_frontend.services.admin.IUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.history.PendingCountsService;
import com.bunshock.note_app_for_it_frontend.services.core.RemoteDatabaseService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
import com.bunshock.note_app_for_it_frontend.utils.core.ViewFactory;
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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Popup;
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
    @FXML private Label lblUsernameWarningIcon;
    @FXML private Label lblSede;
    @FXML private Label lblAdminIndicator;

    @FXML private Label lblHistoryBadge;
    @FXML private Label lblApprovalBadge;
    @FXML private Label lblPrestamosBadge;
    @FXML private Label lblPrestamosApprovalBadge;
    @FXML private Label lblEnviosApprovalBadge;

    @FXML private Circle circleAD;
    @FXML private Circle circleGLPI;
    @FXML private Circle circleDB;
    @FXML private Tooltip tooltipAD;
    @FXML private Tooltip tooltipGLPI;
    @FXML private Tooltip tooltipDB;

    // Title-bar status preview (see setupTitleBarStatusHover()) — mini circles mirror
    // circleAD/circleGLPI/circleDB, reparented into a PopOver on hover.
    @FXML private HBox statusPreview;
    @FXML private Circle circleADMini;
    @FXML private Circle circleGLPIMini;
    @FXML private Circle circleDBMini;
    @FXML private VBox statusPanel;
    private final org.controlsfx.control.PopOver statusPopOver = new org.controlsfx.control.PopOver();
    private static final Duration STATUS_HOVER_DELAY = Duration.millis(400);

    @FXML private StackPane contentArea;

    // All six top-level sidebar entries — plain Button, not ToggleButton/ToggleGroup (see
    // setActiveTopLevelButton()).
    @FXML private Button btnMovimientosGroup;
    @FXML private Button btnPrestamosGroup;
    @FXML private Button btnEnviosGroup;
    @FXML private Button btnData;
    @FXML private Button btnProfile;
    @FXML private Button btnSettings;
    @FXML private Button btnAbout;
    private Button activeTopLevelButton;
    private static final javafx.css.PseudoClass ACTIVE_SECTION =
        javafx.css.PseudoClass.getPseudoClass("active-section");
    private static final javafx.css.PseudoClass FLYOUT_PREVIEW =
        javafx.css.PseudoClass.getPseudoClass("flyout-preview");

    // "Movimientos"/"Préstamos" flyout submenus — see MainView.fxml's own comment on these nodes
    // for why they're declared there (visible/managed=false) instead of built in Java.
    @FXML private VBox flyoutMovimientos;
    @FXML private VBox flyoutPrestamos;
    @FXML private VBox flyoutEnvios;
    @FXML private ToggleGroup flyoutMovimientosGroup;
    @FXML private ToggleGroup flyoutPrestamosGroup;
    @FXML private ToggleGroup flyoutEnviosGroup;
    private final Popup movimientosPopup = new Popup();
    private final Popup prestamosPopup = new Popup();
    private final Popup enviosPopup = new Popup();

    @FXML private HBox titleBar;
    @FXML private Button btnMinimizeWindow;
    @FXML private Button btnMaximizeRestoreWindow;
    @FXML private Button btnCloseWindow;
    @FXML private Button btnLogout;

    private final ViewFactory viewFactory = new ViewFactory();

    public void initialize() {
        TechnicianSessionService.getInstance().addOnChangeListener(this::updateWelcomeLabels);
        updateWelcomeLabels();

        showSection(viewFactory.getGeneratorView());
        // Deferred one pulse: at initialize() time the Stage isn't shown/attached yet, and
        // both centerOnContent() and setupWindowChrome() need a real Scene/Window to work with.
        Platform.runLater(this::runStartupChecks);
        Platform.runLater(this::setupWindowChrome);

        // A flyout's leaf ToggleGroup is allowed to have nothing selected — the leaf handlers
        // below clear the OTHER flyout's selection on navigation.
        setupNavFlyouts();
        setupTitleBarStatusHover();
        setActiveTopLevelButton(btnMovimientosGroup);

        AdminSession.getInstance().addOnActivateListener(this::updateAdminIndicator);
        AdminSession.getInstance().addOnDeactivateListener(this::updateAdminIndicator);

        PendingCountsService.getInstance().addOnChangeListener(this::refreshPendingCounts);
        refreshPendingCounts();
        startPendingCountsPolling();
    }

    // Scoped to badges only — they have no selection/scroll state a background reload could
    // clobber, unlike a TableView. refreshPendingCounts() already runs its DB work on a
    // background thread, so this tick just re-triggers it.
    private static final Duration PENDING_COUNTS_POLL_INTERVAL = Duration.seconds(30);

    private void startPendingCountsPolling() {
        Timeline poll = new Timeline(
            new KeyFrame(PENDING_COUNTS_POLL_INTERVAL, e -> refreshPendingCounts()));
        poll.setCycleCount(Timeline.INDEFINITE);
        poll.play();
    }

    private void updateWelcomeLabels() {
        TechnicianSessionService session = TechnicianSessionService.getInstance();
        String displayName = session.getDisplayName();
        String username = session.getUsername();
        lblWelcome.setText(displayName != null ? "Hola " + displayName + "!" : "Hola!");
        lblUsername.setText(username != null ? "Usuario: " + username : "Perfil no configurado");

        boolean unresolved = username == null;
        if (unresolved) {
            if (!lblUsername.getStyleClass().contains("username-unresolved")) {
                lblUsername.getStyleClass().add("username-unresolved");
            }
        } else {
            lblUsername.getStyleClass().remove("username-unresolved");
        }
        lblUsernameWarningIcon.setVisible(unresolved);
        lblUsernameWarningIcon.setManaged(unresolved);

        updateSedeLabel();
    }

    private void updateSedeLabel() {
        String sede = TechnicianSessionService.getInstance().getSede();
        boolean hasSede = sede != null && !sede.isBlank();
        // An unset Sede blocks note generation entirely (see
        // NoteGeneratorController/PrestamoNewLoanController), so this reads as a standing
        // warning rather than being hidden.
        lblSede.setText(hasSede ? "Sede: " + sede : "Sede no asignada");
        lblSede.getStyleClass().setAll(hasSede ? "user-sede" : "user-sede-warning");
        lblSede.setVisible(true);
        lblSede.setManaged(true);
    }

    /**
     * Runs the AD/DB/GLPI checks in parallel behind a blurred, non-dismissable loading overlay
     * and feeds the same sidebar dots the periodic monitor updates later.
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

        // Staggered starts (1s/2s/3s) so each row's pending state is visible for a moment and
        // the three spinners don't all flash at once; each still resolves independently.
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

    /**
     * Retries a live AD reachability check up to MAX_CONNECTION_ATTEMPTS times, using the
     * already-resolved technician username.
     */
    private void startAdCheck(StartupRow row, Runnable onCheckDone) {
        Thread t = new Thread(() -> {
            String username = TechnicianSessionService.getInstance().getUsername();
            boolean reachable = false;
            for (int attempt = 1; attempt <= MAX_CONNECTION_ATTEMPTS && !reachable; attempt++) {
                try {
                    reachable = username != null
                        && !ServiceLocator.getInstance().getAdService().search(null, null, username).isEmpty();
                } catch (Exception adUnreachable) {
                    reachable = false;
                }
                if (!reachable && attempt < MAX_CONNECTION_ATTEMPTS) sleepBetweenAttempts();
            }
            boolean finalReachable = reachable;
            Platform.runLater(() -> {
                updateADStatus(finalReachable);
                resolveRow(row, "Active Directory",
                    ServiceLocator.getInstance().getAdService().isConfigured(), finalReachable);
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
            // showAndWait() inside an animation's finished handler throws IllegalStateException
            // ("not allowed during animation or layout processing") — defer to the next pulse.
            Platform.runLater(this::warnIfSedeUnassigned);
        });
        fadeOut.play();
    }

    // Sede is mandatory to generate a note or register a Préstamo — a technician without one
    // needs to know immediately, not discover it only when generation fails.
    private void warnIfSedeUnassigned() {
        String sede = TechnicianSessionService.getInstance().getSede();
        if (sede == null || sede.isBlank()) {
            showDialogNotice("Sede no asignada",
                "Un administrador todavía no le asignó una Sede. No podrá generar notas ni "
                    + "registrar préstamos hasta que se le asigne una.",
                "#f59e0b", "⚠");
        }
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
                        // Promotes equipmentService/historyService/userRoleService off local-only
                        // if remote was down at app startup and has since come back.
                        if (dbUp) ServiceLocator.getInstance().retryRemoteConnectionIfDown();
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
        Color color = statusColor(configured, online);
        circleAD.setFill(color);
        circleADMini.setFill(color);
        tooltipAD.setText("Active Directory: " + (!configured ? "No configurado" : (online ? "En línea" : "Desconectado")));
    }

    private void updateGLPIStatus(boolean online) {
        Color color = online ? Color.web("#22c55e") : Color.web("#ef4444");
        circleGLPI.setFill(color);
        circleGLPIMini.setFill(color);
        tooltipGLPI.setText("GLPI API: " + (online ? "En línea" : "Desconectado"));
    }

    private void updateDBStatus(boolean online) {
        boolean configured = RemoteDatabaseService.getInstance().isConfigured();
        Color color = statusColor(configured, online);
        circleDB.setFill(color);
        circleDBMini.setFill(color);
        tooltipDB.setText("Base de datos remota: " + (!configured ? "No configurada" : (online ? "En línea" : "Desconectada")));
    }

    private void updateAdminIndicator() {
        boolean active = AdminSession.getInstance().isActive();
        lblAdminIndicator.setVisible(active);
        // managed must follow visible — otherwise the label reserves layout space while
        // hidden, throwing off the welcome block's vertical centering.
        lblAdminIndicator.setManaged(active);

        boolean superadmin = IUserRoleService.ROLE_SUPERADMIN.equals(AdminSession.getInstance().getEffectiveRole());
        lblAdminIndicator.setText(superadmin ? "MODO SUPERADMINISTRADOR" : "MODO ADMINISTRADOR");
        lblAdminIndicator.getStyleClass().setAll(superadmin ? "title-bar-superadmin-badge" : "title-bar-admin-badge");
    }

    // Same case/accent variants SqliteHistoryService.isPrestamo() tolerates — profile_type is
    // stored raw (see CLAUDE.md), so a filter for Préstamo has to match every form it's stored in.
    private static final List<String> PRESTAMO_PROFILE_TYPES = List.of("PRÉSTAMO", "PRESTAMO", "Préstamo");
    private static final List<String> ENVIO_PROFILE_TYPES = List.of("REMITO DE ENVÍO");

    private static boolean isPrestamoProfileType(String profileType) {
        return profileType != null && PRESTAMO_PROFILE_TYPES.stream().anyMatch(profileType::equalsIgnoreCase);
    }

    private static boolean isEnvioProfileType(String profileType) {
        return profileType != null && ENVIO_PROFILE_TYPES.stream().anyMatch(profileType::equalsIgnoreCase);
    }

    // Unset never matches (mirrors AdminSession.hasPermission(Permission, Integer)'s
    // Sede-scoping rule), so this count never disagrees with which notes the buttons can act on.
    private static boolean sameSede(NoteReport report, String mySede) {
        return mySede != null && !mySede.isBlank()
            && report.getSede() != null && mySede.equalsIgnoreCase(report.getSede());
    }

    /**
     * Recomputes the Historial/Préstamos/Envíos pending-badge counts on a background thread.
     * Not admin-gated — the underlying pending rows are already visible to any technician; only
     * the sync/validate/approve actions themselves are gated. A plain ADMIN is scoped to their
     * own Sede (see AdminSession.hasPermission's Sede-scoping); SUPERADMIN and non-admin
     * technicians see the global count. GLPI-pending and Préstamo return-pending only count an
     * APPROVED note — a still-pending or rejected note isn't real yet.
     */
    private void refreshPendingCounts() {
        Thread t = new Thread(() -> {
            int glpiPending;
            int approvalPending;
            int prestamoPending;
            int prestamoApprovalPending;
            int enviosApprovalPending;
            try {
                IHistoryService historyService = ServiceLocator.getInstance().getHistoryService();
                String effectiveRole = AdminSession.getInstance().getEffectiveRole();
                boolean scopeToOwnSede = IUserRoleService.ROLE_ADMIN.equals(effectiveRole);
                String mySede = scopeToOwnSede ? TechnicianSessionService.getInstance().getSede() : null;

                List<NoteReport> glpiSync = historyService.getPendingGlpiSync();
                if (scopeToOwnSede) {
                    glpiSync = glpiSync.stream().filter(r -> sameSede(r, mySede)).toList();
                }
                glpiPending = glpiSync.size();

                List<NoteReport> pendingApproval = historyService.getPendingApproval();
                if (scopeToOwnSede) {
                    pendingApproval = pendingApproval.stream().filter(r -> sameSede(r, mySede)).toList();
                }
                approvalPending = pendingApproval.size();
                // Historial counts every profile type; Préstamos/Envíos filter the same
                // already-fetched list down to their own subset — no second query needed.
                prestamoApprovalPending = (int) pendingApproval.stream()
                    .filter(r -> isPrestamoProfileType(r.getProfileType()))
                    .count();
                enviosApprovalPending = (int) pendingApproval.stream()
                    .filter(r -> isEnvioProfileType(r.getProfileType()))
                    .count();

                HistoryFilter prestamoFilter = new HistoryFilter();
                prestamoFilter.setProfileTypes(PRESTAMO_PROFILE_TYPES);
                prestamoFilter.setApprovalStatuses(List.of("APPROVED"));
                if (scopeToOwnSede && mySede != null && !mySede.isBlank()) {
                    prestamoFilter.setSedes(List.of(mySede));
                }
                prestamoPending = (int) historyService.getFiltered(prestamoFilter).stream()
                    .filter(r -> r.getReturnPendingItemCount() > 0)
                    .count();
            } catch (Exception e) {
                glpiPending = 0;
                approvalPending = 0;
                prestamoPending = 0;
                prestamoApprovalPending = 0;
                enviosApprovalPending = 0;
            }
            int finalGlpiPending = glpiPending;
            int finalApprovalPending = approvalPending;
            int finalPrestamoPending = prestamoPending;
            int finalPrestamoApprovalPending = prestamoApprovalPending;
            int finalEnviosApprovalPending = enviosApprovalPending;
            Platform.runLater(() -> {
                updateBadge(lblHistoryBadge, finalGlpiPending);
                updateBadge(lblApprovalBadge, finalApprovalPending);
                updateBadge(lblPrestamosBadge, finalPrestamoPending);
                updateBadge(lblPrestamosApprovalBadge, finalPrestamoApprovalPending);
                updateBadge(lblEnviosApprovalBadge, finalEnviosApprovalPending);
            });
        }, "pending-counts-refresh");
        t.setDaemon(true);
        t.start();
    }

    private void updateBadge(Label badge, int count) {
        boolean show = count > 0;
        badge.setText("(" + count + ")");
        badge.setVisible(show);
        badge.setManaged(show);
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

    // Speech-bubble pointer nub size, roughly centered on a .nav-button's own height.
    private static final double NAV_POINTER_WIDTH = 10;
    private static final double NAV_POINTER_HEIGHT = 16;
    private static final double NAV_POINTER_TOP_OFFSET = 13;
    private static final String NAV_FLYOUT_FILL = "#0c8570"; // matches .sidebar/.nav-flyout

    // Small rightward nudge to compensate for showFlyout() anchoring off effect-free
    // layoutBounds. Tune directly if the gap looks off.
    private static final double NAV_FLYOUT_X_OFFSET = 5;

    // Reparents statusPanel (hidden in MainView.fxml) into a PopOver anchored off statusPreview's
    // mini dots, shown on hover — a Popup (like the nav flyouts use) would need outside-click/
    // focus-loss handling a plain hover doesn't.
    private void setupTitleBarStatusHover() {
        // PopOver.setContentNode() doesn't reparent statusPanel immediately — its skin (and the
        // actual attachment) is only created lazily on first show(), so statusPanel would stay
        // visible in its FXML parent until then. Wrapping it in a fresh StackPane here forces an
        // immediate reparent instead.
        StackPane statusPopOverRoot = new StackPane(statusPanel);
        statusPanel.setVisible(true);
        statusPanel.setManaged(true);
        statusPopOver.setContentNode(statusPopOverRoot);
        statusPopOver.setArrowLocation(org.controlsfx.control.PopOver.ArrowLocation.TOP_RIGHT);
        statusPopOver.setCornerRadius(8);
        statusPopOver.setOpacity(0.95);
        statusPopOver.setAnimated(true);
        statusPopOver.setDetachable(false);
        statusPopOver.setAutoHide(false);

        PauseTransition hoverDelay = new PauseTransition(STATUS_HOVER_DELAY);
        hoverDelay.setOnFinished(e -> statusPopOver.show(statusPreview));
        statusPreview.setOnMouseEntered(e -> {
            if (!statusPopOver.isShowing()) hoverDelay.playFromStart();
        });
        statusPreview.setOnMouseExited(e -> {
            hoverDelay.stop();
            if (statusPopOver.isShowing()) statusPopOver.hide();
        });
    }

    // Wires up both flyouts: reparents each card into its own Popup, adds the pointer/arrow, and
    // sets up outside-click and window-focus handling.
    private void setupNavFlyouts() {
        setupFlyoutPopup(movimientosPopup, flyoutMovimientos);
        setupFlyoutPopup(prestamosPopup, flyoutPrestamos);
        setupFlyoutPopup(enviosPopup, flyoutEnvios);
        setupGroupButtonArrow(btnMovimientosGroup);
        setupGroupButtonArrow(btnPrestamosGroup);
        setupGroupButtonArrow(btnEnviosGroup);
        setupNavFlyoutOutsideClickHandling();
        setupNavFlyoutWindowFocusHandling();

        // Clears the preview highlight whenever a popup closes, whatever the reason.
        movimientosPopup.showingProperty().addListener((obs, was, isShowing) -> {
            if (!isShowing) btnMovimientosGroup.pseudoClassStateChanged(FLYOUT_PREVIEW, false);
        });
        prestamosPopup.showingProperty().addListener((obs, was, isShowing) -> {
            if (!isShowing) btnPrestamosGroup.pseudoClassStateChanged(FLYOUT_PREVIEW, false);
        });
        enviosPopup.showingProperty().addListener((obs, was, isShowing) -> {
            if (!isShowing) btnEnviosGroup.pseudoClassStateChanged(FLYOUT_PREVIEW, false);
        });
    }

    // Maps a group button to its own popup — used wherever the code needs to act on "whichever
    // flyout this button owns" without a growing if/else chain.
    private Popup popupForGroupButton(Button groupButton) {
        if (groupButton == btnMovimientosGroup) return movimientosPopup;
        if (groupButton == btnPrestamosGroup) return prestamosPopup;
        return enviosPopup;
    }

    // Sets which of the top-level buttons is shown as the active section. Not driven by
    // ToggleGroup: opening a flyout must not change the active section until a leaf inside it is
    // actually picked.
    private void setActiveTopLevelButton(Button active) {
        for (Button b : List.of(btnMovimientosGroup, btnPrestamosGroup, btnEnviosGroup, btnData, btnProfile, btnSettings, btnAbout)) {
            b.pseudoClassStateChanged(ACTIVE_SECTION, b == active);
        }
        activeTopLevelButton = active;
        updateGroupButtonGraphicStyle(btnMovimientosGroup, active == btnMovimientosGroup);
        updateGroupButtonGraphicStyle(btnPrestamosGroup, active == btnPrestamosGroup);
        updateGroupButtonGraphicStyle(btnEnviosGroup, active == btnEnviosGroup);
    }

    // Closes an open popup on any outside click without consuming the event, so the click still
    // reaches its real target in the same gesture. Group buttons are excluded since their own
    // toggle handler already manages opening/closing their popup.
    private void setupNavFlyoutOutsideClickHandling() {
        Platform.runLater(() -> {
            Scene scene = rootPane.getScene();
            if (scene == null) return;
            scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                hidePopupUnlessClickIsOnOwnGroupButton(movimientosPopup, btnMovimientosGroup, e);
                hidePopupUnlessClickIsOnOwnGroupButton(prestamosPopup, btnPrestamosGroup, e);
                hidePopupUnlessClickIsOnOwnGroupButton(enviosPopup, btnEnviosGroup, e);
            });
        });
    }

    private void hidePopupUnlessClickIsOnOwnGroupButton(Popup popup, Button groupButton, MouseEvent e) {
        if (!popup.isShowing()) return;
        if (e.getTarget() instanceof Node target && isSameOrDescendantOf(target, groupButton)) return;
        popup.hide();
    }

    // Walks up from the click target so a click on the button's own graphic (Labels/Region, not
    // the Button node itself) still counts as a match.
    private boolean isSameOrDescendantOf(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) return true;
        }
        return false;
    }

    // Hides an open popup when the main window loses focus, and reopens it at the same anchor
    // button once focus returns.
    private Button flyoutHiddenByFocusLossAnchor;

    private void setupNavFlyoutWindowFocusHandling() {
        Platform.runLater(() -> {
            Scene scene = rootPane.getScene();
            if (scene == null || !(scene.getWindow() instanceof Stage stage)) return;
            stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused) {
                    if (movimientosPopup.isShowing()) {
                        flyoutHiddenByFocusLossAnchor = btnMovimientosGroup;
                        movimientosPopup.hide();
                    } else if (prestamosPopup.isShowing()) {
                        flyoutHiddenByFocusLossAnchor = btnPrestamosGroup;
                        prestamosPopup.hide();
                    } else if (enviosPopup.isShowing()) {
                        flyoutHiddenByFocusLossAnchor = btnEnviosGroup;
                        enviosPopup.hide();
                    }
                } else if (flyoutHiddenByFocusLossAnchor != null) {
                    openFlyout(popupForGroupButton(flyoutHiddenByFocusLossAnchor), flyoutHiddenByFocusLossAnchor);
                    flyoutHiddenByFocusLossAnchor = null;
                }
            });
        });
    }

    private void setupFlyoutPopup(Popup popup, VBox card) {
        // MainView.fxml declares this VBox managed="false"; must be flipped to true here too, or
        // the wrapping HBox below treats it as zero-width.
        card.setVisible(true);
        card.setManaged(true);

        // Wrapped in a fixed-size Pane since Polygon (a Shape, not a Region) isn't resizable and
        // won't otherwise size correctly inside VBox/HBox. Stroke keeps it visible against the
        // card's own drop shadow.
        Polygon pointer = new Polygon(
            0, NAV_POINTER_HEIGHT / 2.0,
            NAV_POINTER_WIDTH, 0,
            NAV_POINTER_WIDTH, NAV_POINTER_HEIGHT);
        pointer.setFill(Color.web(NAV_FLYOUT_FILL));
        pointer.setStroke(Color.web("rgba(255, 255, 255, 0.18)"));
        pointer.setStrokeWidth(1);

        Pane pointerPane = new Pane(pointer);
        pointerPane.setMinSize(NAV_POINTER_WIDTH, NAV_POINTER_HEIGHT);
        pointerPane.setPrefSize(NAV_POINTER_WIDTH, NAV_POINTER_HEIGHT);
        pointerPane.setMaxSize(NAV_POINTER_WIDTH, NAV_POINTER_HEIGHT);

        VBox pointerColumn = new VBox(pointerPane);
        VBox.setMargin(pointerPane, new Insets(NAV_POINTER_TOP_OFFSET, 0, 0, 0));

        HBox wrapper = new HBox(pointerColumn, card);
        wrapper.setAlignment(Pos.TOP_LEFT);
        wrapper.getStylesheets().add(getClass().getResource(
            "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());

        popup.getContent().add(wrapper);
        // No setAutoHide(true) — see setupNavFlyoutOutsideClickHandling() instead.
    }

    // Custom graphic (label + growing spacer + arrow) so the arrow sits flush against the
    // button's right edge instead of packed next to the text. Spacer width is bound to the
    // button's own width since it won't grow to fill it otherwise (same gotcha as
    // DatabaseSectionController.applyCatalogCellFactory()'s ListCell graphic).
    private static final double NAV_BUTTON_HORIZONTAL_PADDING = 30; // .nav-button's own 15+15

    private void setupGroupButtonArrow(Button button) {
        Label lblText = new Label(button.getText());
        Label lblArrow = new Label("▸");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox graphic = new HBox(lblText, spacer, lblArrow);
        graphic.setAlignment(Pos.CENTER_LEFT);
        graphic.prefWidthProperty().bind(button.widthProperty().subtract(NAV_BUTTON_HORIZONTAL_PADDING));

        button.setText("");
        button.setGraphic(graphic);
        updateGroupButtonGraphicStyle(button, false);
    }

    // CSS can't reach into a custom graphic's child Labels, so active-state text styling is
    // synced here manually — called directly from setActiveTopLevelButton() since a manually
    // toggled PseudoClass has no property to listen to.
    private void updateGroupButtonGraphicStyle(Button button, boolean active) {
        HBox graphic = (HBox) button.getGraphic();
        Label lblText = (Label) graphic.getChildren().get(0);
        Label lblArrow = (Label) graphic.getChildren().get(2);
        String style = active
            ? "-fx-text-fill: white; -fx-font-weight: bold;"
            : "-fx-text-fill: #e0f2f1;";
        lblText.setStyle(style);
        lblArrow.setStyle(style);
    }

    @FXML
    private void handleToggleMovimientosFlyout() {
        if (movimientosPopup.isShowing()) {
            movimientosPopup.hide();
            return;
        }
        prestamosPopup.hide();
        enviosPopup.hide();
        openFlyout(movimientosPopup, btnMovimientosGroup);
    }

    @FXML
    private void handleTogglePrestamosFlyout() {
        if (prestamosPopup.isShowing()) {
            prestamosPopup.hide();
            return;
        }
        movimientosPopup.hide();
        enviosPopup.hide();
        openFlyout(prestamosPopup, btnPrestamosGroup);
    }

    @FXML
    private void handleToggleEnviosFlyout() {
        if (enviosPopup.isShowing()) {
            enviosPopup.hide();
            return;
        }
        movimientosPopup.hide();
        prestamosPopup.hide();
        openFlyout(enviosPopup, btnEnviosGroup);
    }

    // Applies the preview highlight only when opening a group that isn't already active.
    private void openFlyout(Popup popup, Button groupButton) {
        if (activeTopLevelButton != groupButton) {
            groupButton.pseudoClassStateChanged(FLYOUT_PREVIEW, true);
        }
        showFlyout(popup, groupButton);
    }

    // Anchored off getLayoutBounds(), not getBoundsInLocal() — boundsInLocal includes the
    // button's CSS -fx-effect (the active section's innershadow), which inflated the reported
    // bounds and shifted the flyout left only when opened from the currently active button.
    // layoutBounds excludes effect/clip, so positioning is stable regardless of hover/active state.
    private void showFlyout(Popup popup, Button anchor) {
        var bounds = anchor.localToScreen(anchor.getLayoutBounds());
        popup.show(anchor, bounds.getMaxX() + NAV_FLYOUT_X_OFFSET, bounds.getMinY());
    }

    @FXML
    private void handleShowGenerator() {
        showSection(viewFactory.getGeneratorView());
        // Same staleness fix as PrestamosController.showNewLoanView()/EnviosController.
        // showRemitoView() — a stock-shortage warning shown before navigating away must be
        // recomputed on return, not left stuck from before the shortage was actually fixed
        // elsewhere (e.g. Base de Datos).
        viewFactory.getGeneratorController().refreshStockWarning();
        setActiveTopLevelButton(btnMovimientosGroup);
        flyoutPrestamosGroup.selectToggle(null);
        flyoutEnviosGroup.selectToggle(null);
        movimientosPopup.hide();
    }

    @FXML
    private void handleShowRemito() {
        showSection(viewFactory.getEnviosView());
        viewFactory.getEnviosController().showRemitoTab();
        setActiveTopLevelButton(btnEnviosGroup);
        flyoutMovimientosGroup.selectToggle(null);
        flyoutPrestamosGroup.selectToggle(null);
        enviosPopup.hide();
    }

    @FXML
    private void handleShowHistorialEnvios() {
        showSection(viewFactory.getEnviosView());
        viewFactory.getEnviosController().showHistorialTab();
        // Envío notes count toward the Historial-wide approval-pending badge (every profile type,
        // Envío included) even though Envíos has no nav badge of its own — same nav-triggered
        // refresh as handleShowHistory()/handleShowPrestamoHistorial(), see that comment.
        refreshPendingCounts();
        setActiveTopLevelButton(btnEnviosGroup);
        flyoutMovimientosGroup.selectToggle(null);
        flyoutPrestamosGroup.selectToggle(null);
        enviosPopup.hide();
    }

    @FXML
    private void handleShowHistory() {
        showSection(viewFactory.getHistoryView());
        // ViewFactory caches this view for the session, so without this, notes generated after
        // the first visit wouldn't show until "Buscar" was clicked manually.
        viewFactory.getHistoryController().refresh();
        // Also re-syncs badges against another technician's changes on a shared remote DB.
        refreshPendingCounts();
        setActiveTopLevelButton(btnMovimientosGroup);
        flyoutPrestamosGroup.selectToggle(null);
        flyoutEnviosGroup.selectToggle(null);
        movimientosPopup.hide();
    }

    @FXML
    private void handleShowPrestamoCargar() {
        showSection(viewFactory.getPrestamosView());
        viewFactory.getPrestamosController().showNewLoanTab();
        setActiveTopLevelButton(btnPrestamosGroup);
        flyoutMovimientosGroup.selectToggle(null);
        flyoutEnviosGroup.selectToggle(null);
        prestamosPopup.hide();
    }

    @FXML
    private void handleShowPrestamoHistorial() {
        showSection(viewFactory.getPrestamosView());
        viewFactory.getPrestamosController().showHistoryTab();
        // Same nav-triggered badge refresh as handleShowHistory() — see its comment.
        refreshPendingCounts();
        setActiveTopLevelButton(btnPrestamosGroup);
        flyoutMovimientosGroup.selectToggle(null);
        flyoutEnviosGroup.selectToggle(null);
        prestamosPopup.hide();
    }

    @FXML
    private void handleShowDatabase() {
        showSection(viewFactory.getDatabaseView());
        viewFactory.getDatabaseSectionController().refresh();
        setActiveTopLevelButton(btnData);
        clearAllFlyoutLeafSelections();
    }

    @FXML
    private void handleShowSettings() {
        showSection(viewFactory.getSettingsView());
        setActiveTopLevelButton(btnSettings);
        clearAllFlyoutLeafSelections();
    }

    @FXML
    private void handleShowAbout() {
        showSection(viewFactory.getAboutView());
        setActiveTopLevelButton(btnAbout);
        clearAllFlyoutLeafSelections();
    }

    @FXML
    private void handleShowProfile() {
        showSection(viewFactory.getProfileView());
        setActiveTopLevelButton(btnProfile);
        clearAllFlyoutLeafSelections();
    }

    // Clears every flyout's leaf selection — called when navigating to Base de Datos/Mi
    // Perfil/Configuración/Acerca de, since none of the flyouts is the active section anymore.
    private void clearAllFlyoutLeafSelections() {
        flyoutMovimientosGroup.selectToggle(null);
        flyoutPrestamosGroup.selectToggle(null);
        flyoutEnviosGroup.selectToggle(null);
    }

    /**
     * Ends the session and returns to login. A fresh login rebuilds MainView (and this
     * controller) from scratch, so nothing else needs resetting here.
     */
    @FXML
    private void handleLogout() {
        if (!confirmLogout()) return;

        AdminSession.getInstance().deactivate();
        AdminSession.getInstance().clearListenersForLogout();
        PendingCountsService.getInstance().clearListenersForLogout();
        TechnicianSessionService.getInstance().clearSessionForLogout();

        App.getInstance().showLoginAgain();
    }

    private boolean confirmLogout() {
        boolean[] confirmed = {false};
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Cerrar sesión");
        lblTitle.getStyleClass().add("section-label");

        Label lblMsg = new Label("¿Cerrar la sesión actual? Deberá volver a iniciar sesión para continuar.");
        lblMsg.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");
        lblMsg.setWrapText(true);

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnConfirm = new Button("Cerrar sesión");
        btnConfirm.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; " +
            "-fx-background-radius: 6; -fx-font-weight: bold; -fx-cursor: hand;");
        btnConfirm.setOnAction(e -> { confirmed[0] = true; stage.close(); });

        HBox buttons = new HBox(8, btnCancel, btnConfirm);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380, "#1a1a1a");
        root.getChildren().addAll(lblTitle, lblMsg, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();

        return confirmed[0];
    }

    // ── Custom title bar (App.java sets StageStyle.UNDECORATED — no native chrome, so drag-to-
    // move, edge resize, and minimize/maximize/close all have to be reimplemented by hand here) ──

    private static final double RESIZE_MARGIN = 6;

    // Must match App.java's WINDOW_SHADOW_MARGIN — the padding value restored on un-maximize.
    private static final double WINDOW_SHADOW_MARGIN = 12;
    // Rectangle.arcWidth/arcHeight are corner *diameters*, not radii — 20 here gives the same
    // ~10px visual corner radius as .app-window-frame's CSS -fx-background-radius/-fx-border-radius.
    private static final double WINDOW_CORNER_ARC = 20;

    private enum ResizeDirection { E, W, S, SE, SW }

    private double dragAnchorX, dragAnchorY;
    private double resizeStartScreenX, resizeStartScreenY;
    private double resizeStartWidth, resizeStartHeight, resizeStartStageX;
    private ResizeDirection activeResizeDirection;
    private Rectangle windowClip;

    private void setupWindowChrome() {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        StackPane windowWrapper = (StackPane) rootPane.getParent();

        windowClip = new Rectangle();
        windowClip.setArcWidth(WINDOW_CORNER_ARC);
        windowClip.setArcHeight(WINDOW_CORNER_ARC);
        windowClip.widthProperty().bind(rootPane.widthProperty());
        windowClip.heightProperty().bind(rootPane.heightProperty());

        setupTitleBarDrag(stage);
        setupEdgeResize(stage);
        applyWindowFrame(windowWrapper, stage.isMaximized());
        stage.maximizedProperty().addListener((obs, was, isNow) -> applyWindowFrame(windowWrapper, isNow));
    }

    // Node.clip and Node.effect don't combine on the same node — a DropShadow needs to bleed
    // outside the node's bounds, but a clip cuts exactly at them. Shadow lives on windowWrapper
    // (unclipped), rounded-corner clip lives on rootPane; both are removed while maximized so
    // the window fills the screen edge-to-edge with square corners.
    private void applyWindowFrame(StackPane windowWrapper, boolean maximized) {
        updateMaximizeGlyph(maximized);
        if (maximized) {
            windowWrapper.setPadding(Insets.EMPTY);
            windowWrapper.setEffect(null);
            rootPane.setClip(null);
            if (!rootPane.getStyleClass().contains("maximized")) rootPane.getStyleClass().add("maximized");
        } else {
            windowWrapper.setPadding(new Insets(WINDOW_SHADOW_MARGIN));
            // Radius/offset kept within WINDOW_SHADOW_MARGIN's padding — a shadow that bleeds
            // further than the padding allows just gets clipped at the wrapper's edge.
            DropShadow shadow = new DropShadow();
            shadow.setColor(Color.rgb(0, 0, 0, 0.35));
            shadow.setRadius(14);
            shadow.setOffsetY(4);
            windowWrapper.setEffect(shadow);
            rootPane.setClip(windowClip);
            rootPane.getStyleClass().remove("maximized");
        }
    }

    private void setupTitleBarDrag(Stage stage) {
        titleBar.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            dragAnchorX = e.getSceneX();
            dragAnchorY = e.getSceneY();
        });
        titleBar.setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY || stage.isMaximized()) return;
            stage.setX(e.getScreenX() - dragAnchorX);
            stage.setY(e.getScreenY() - dragAnchorY);
        });
        titleBar.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) handleMaximizeRestoreWindow();
        });
    }

    // Only right/bottom/bottom-corner edges are resize-draggable — the top edge is reserved for
    // title-bar drag-to-move, so a top resize zone would fight that same strip.
    private void setupEdgeResize(Stage stage) {
        rootPane.setOnMouseMoved(e -> updateResizeCursor(stage, e));
        rootPane.setOnMouseExited(e -> { if (activeResizeDirection == null) rootPane.setCursor(Cursor.DEFAULT); });
        rootPane.setOnMousePressed(e -> {
            activeResizeDirection = resolveDirection(stage, e);
            if (activeResizeDirection == null) return;
            resizeStartScreenX = e.getScreenX();
            resizeStartScreenY = e.getScreenY();
            resizeStartWidth   = stage.getWidth();
            resizeStartHeight  = stage.getHeight();
            resizeStartStageX  = stage.getX();
        });
        rootPane.setOnMouseDragged(e -> {
            if (activeResizeDirection == null) return;
            applyResize(stage, e.getScreenX() - resizeStartScreenX, e.getScreenY() - resizeStartScreenY);
        });
        rootPane.setOnMouseReleased(e -> activeResizeDirection = null);
    }

    private ResizeDirection resolveDirection(Stage stage, MouseEvent e) {
        if (stage.isMaximized()) return null;
        boolean east  = e.getX() >= rootPane.getWidth()  - RESIZE_MARGIN;
        boolean west  = e.getX() <= RESIZE_MARGIN;
        boolean south = e.getY() >= rootPane.getHeight() - RESIZE_MARGIN;
        if (south && east) return ResizeDirection.SE;
        if (south && west) return ResizeDirection.SW;
        if (east)  return ResizeDirection.E;
        if (west)  return ResizeDirection.W;
        if (south) return ResizeDirection.S;
        return null;
    }

    private void updateResizeCursor(Stage stage, MouseEvent e) {
        if (activeResizeDirection != null) return;
        ResizeDirection dir = resolveDirection(stage, e);
        if (dir == null) { rootPane.setCursor(Cursor.DEFAULT); return; }
        switch (dir) {
            case E, W -> rootPane.setCursor(Cursor.H_RESIZE);
            case S    -> rootPane.setCursor(Cursor.V_RESIZE);
            case SE   -> rootPane.setCursor(Cursor.SE_RESIZE);
            case SW   -> rootPane.setCursor(Cursor.SW_RESIZE);
        }
    }

    private void applyResize(Stage stage, double dx, double dy) {
        double newWidth  = resizeStartWidth;
        double newHeight = resizeStartHeight;

        switch (activeResizeDirection) {
            case E  -> newWidth = resizeStartWidth + dx;
            case W  -> newWidth = resizeStartWidth - dx;
            case S  -> newHeight = resizeStartHeight + dy;
            case SE -> { newWidth = resizeStartWidth + dx; newHeight = resizeStartHeight + dy; }
            case SW -> { newWidth = resizeStartWidth - dx; newHeight = resizeStartHeight + dy; }
        }

        newWidth  = Math.max(newWidth, stage.getMinWidth());
        newHeight = Math.max(newHeight, stage.getMinHeight());

        // Dragging the west edge moves the window's X as it shrinks/grows — recomputed from the
        // final (possibly clamped-to-min-width) newWidth so the window edge tracks the cursor
        // exactly, instead of drifting once the min-width clamp kicks in.
        if (activeResizeDirection == ResizeDirection.W || activeResizeDirection == ResizeDirection.SW) {
            stage.setX(resizeStartStageX + (resizeStartWidth - newWidth));
        }
        stage.setWidth(newWidth);
        stage.setHeight(newHeight);
    }

    // Drawn as Rectangle shapes, not a Unicode glyph or CSS-styled Region — Rectangle.setFill()/
    // setStroke() are set directly via the Java API, so there's no stylesheet cascade to fight.
    // Restore uses the classic two-overlapping-squares convention.
    private void updateMaximizeGlyph(boolean maximized) {
        if (maximized) {
            // Both squares are outline-only (fill=TRANSPARENT) — a solid fill would visibly
            // mismatch .title-bar-button's :hover/:pressed background overlay.
            Rectangle back = new Rectangle(8, 8);
            back.setFill(Color.TRANSPARENT);
            back.setStroke(Color.WHITE);
            back.setStrokeWidth(1);
            Rectangle front = new Rectangle(8, 8);
            front.setFill(Color.TRANSPARENT);
            front.setStroke(Color.WHITE);
            front.setStrokeWidth(1);
            StackPane icon = new StackPane(back, front);
            StackPane.setAlignment(back, Pos.TOP_RIGHT);
            StackPane.setAlignment(front, Pos.BOTTOM_LEFT);
            // maxSize is required — StackPane's default max size is unbounded, so without it
            // the button's layout pass stretches the icon and pulls the two squares apart.
            icon.setMinSize(11, 11);
            icon.setPrefSize(11, 11);
            icon.setMaxSize(11, 11);
            icon.setMouseTransparent(true);
            btnMaximizeRestoreWindow.setGraphic(icon);
        } else {
            Rectangle square = new Rectangle(10, 10);
            square.setFill(Color.TRANSPARENT);
            square.setStroke(Color.WHITE);
            square.setStrokeWidth(1);
            square.setMouseTransparent(true);
            btnMaximizeRestoreWindow.setGraphic(square);
        }
    }

    @FXML
    private void handleMinimizeWindow() {
        ((Stage) rootPane.getScene().getWindow()).setIconified(true);
    }

    @FXML
    private void handleMaximizeRestoreWindow() {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        stage.setMaximized(!stage.isMaximized());
    }

    @FXML
    private void handleCloseWindow() {
        ((Stage) rootPane.getScene().getWindow()).close();
    }
}
