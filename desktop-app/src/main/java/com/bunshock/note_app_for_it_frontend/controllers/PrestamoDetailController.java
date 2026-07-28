package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;

import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.models.ReturnStatus;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.NoteGenerationService;
import com.bunshock.note_app_for_it_frontend.services.PendingCountsService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.Parent;
import javafx.scene.input.KeyCode;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.time.format.DateTimeFormatter;

// Préstamo-specific sibling of NoteDetailController — same popup chrome/structure, but each
// item row shows RETURN status (Devuelto/Perdido/Pendiente) instead of GLPI sync status, with
// admin-gated "Validar devolución"/"Marcar como perdido" buttons. Kept separate on purpose: regular
// History's own detail popup (NoteDetailController) stays GLPI-only and untouched even for a
// Préstamo note — return validation only happens from the Préstamos section. See CLAUDE.md.
public class PrestamoDetailController {

    @FXML private VBox    rootContainer;
    @FXML private Label   lblTitle;
    @FXML private WebView webPreview;
    @FXML private VBox    vboxItems;

    private NoteReport report;
    private boolean adminMode;
    private Runnable onUpdate;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final NoteGenerationService GEN_SVC = new NoteGenerationService();
    private static final int REJECTION_REASON_MAX_LENGTH = 300;

    public void initialize() {
        Rectangle clip = new Rectangle();
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        clip.widthProperty().bind(rootContainer.widthProperty());
        clip.heightProperty().bind(rootContainer.heightProperty());
        rootContainer.setClip(clip);
    }

    public void load(NoteReport report, boolean adminMode, Runnable onUpdate) {
        this.report    = report;
        this.adminMode = adminMode;
        this.onUpdate  = onUpdate;

        String date = report.getCreatedAt() != null ? report.getCreatedAt().format(DT_FMT) : "";
        lblTitle.setText("Préstamo — " + date);

        try {
            String html = GEN_SVC.generateFromStoredReport(report);
            webPreview.getEngine().loadContent(html);
        } catch (IOException e) {
            webPreview.getEngine().loadContent("<p style='color:red'>Error al renderizar la nota.</p>");
        }

        buildItemCards();
    }

    private void buildItemCards() {
        vboxItems.getChildren().clear();
        if (report.getItems() == null) return;
        for (NoteReportItem item : report.getItems()) {
            vboxItems.getChildren().add(buildItemCard(item));
            vboxItems.getChildren().add(new Separator());
        }
    }

    private VBox buildItemCard(NoteReportItem item) {
        VBox card = new VBox(5);
        card.setStyle("-fx-padding: 10 8; -fx-background-color: white; -fx-background-radius: 6;");

        String title = item.getTypeName() + " " + item.getBrandName() + " " + item.getModelName();
        Label lblTitle = new Label(title);
        lblTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #1e293b; -fx-wrap-text: true;");
        card.getChildren().add(lblTitle);

        if (item.isAsset()) {
            if (item.getSerialNumber() != null && !item.getSerialNumber().isBlank())
                card.getChildren().add(smallLabel("S/N: " + item.getSerialNumber()));
            if (item.getAf() != null && !item.getAf().isBlank())
                card.getChildren().add(smallLabel("A/F: " + item.getAf()));
        } else {
            card.getChildren().add(smallLabel("Cantidad: " + item.getQuantity()));
        }

        if (item.getObservations() != null && !item.getObservations().isBlank())
            card.getChildren().add(smallLabel("Obs: " + item.getObservations()));

        card.getChildren().add(buildReturnStatusRow(item));
        return card;
    }

    // A VBox, not a single HBox row — the "Préstamo: " prefix + status badge now render on their
    // own row in EVERY state, not just PENDING (previously it only appeared while pending, so it
    // vanished once Devuelto/No devuelto was clicked and the row re-rendered as RETURNED/LOST,
    // which read as the label being deleted), with the action buttons (PENDING only) on a
    // separate row below, given extra spacing (8, not 4) so the buttons don't crowd the label
    // row above them. Unlike GLPI's Sincronizar/Rechazar, the two action labels here were
    // shortened to "Devuelto"/"No devuelto" specifically so they still fit side-by-side at this
    // panel's ~270px usable width — "Validar devolución"/"Marcar como perdido" together needed
    // ~270-280px and were shrinking.
    private VBox buildReturnStatusRow(NoteReportItem item) {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setStyle("-fx-padding: 4 0 0 0;");

        ReturnStatus status = item.getReturnStatus();

        String badgeText;
        String badgeColor;
        boolean showActions = false;

        switch (status) {
            case RETURNED -> {
                String ts = item.getReturnStatusUpdatedAt() != null ? " · " + item.getReturnStatusUpdatedAt().substring(0, 16) : "";
                badgeText = "✓ Devuelto" + ts;
                badgeColor = "#22c55e";
            }
            case LOST -> {
                String reason = item.getReturnRejectionReason() != null ? ": " + item.getReturnRejectionReason() : "";
                badgeText = "✗ Perdido" + reason;
                badgeColor = "#ef4444";
            }
            case PENDING -> {
                badgeText = "⏳ Pendiente devolución";
                badgeColor = "#f97316";
                showActions = true;
            }
            default -> {
                badgeText = "—";
                badgeColor = "#94a3b8";
            }
        }

        HBox statusRow = new HBox(4);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.getChildren().addAll(prefixLabel("Préstamo: "), statusBadge(badgeText, badgeColor));
        box.getChildren().add(statusRow);

        if (showActions && adminMode && AdminSession.getInstance().isActive()) {
            Button btnReturn = new Button("Devuelto");
            btnReturn.getStyleClass().add("button-primary");
            btnReturn.setStyle(btnReturn.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
            btnReturn.setOnAction(e -> handleReturn(item, btnReturn));

            Button btnLost = new Button("No devuelto");
            btnLost.getStyleClass().add("button-secondary");
            btnLost.setStyle(btnLost.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
            btnLost.setOnAction(e -> handleLost(item));

            box.getChildren().add(new HBox(8, btnReturn, btnLost));
        }

        return box;
    }

    private Label prefixLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #475569;");
        return l;
    }

    private void handleReturn(NoteReportItem item, Button btnReturn) {
        AdminSession.getInstance().refreshActivity();
        btnReturn.setDisable(true);
        ServiceLocator.getInstance().getHistoryService()
            .updateItemReturnStatus(item.getId(), ReturnStatus.RETURNED, null);
        item.setReturnStatus(ReturnStatus.RETURNED);
        logAuditAction("PRESTAMO_RETURN", item.getId(), null);
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private void handleLost(NoteReportItem item) {
        AdminSession.getInstance().refreshActivity();
        String reason = promptRejectionReason();
        if (reason == null) return;
        ServiceLocator.getInstance().getHistoryService()
            .updateItemReturnStatus(item.getId(), ReturnStatus.LOST, reason);
        item.setReturnStatus(ReturnStatus.LOST);
        item.setReturnRejectionReason(reason);
        logAuditAction("PRESTAMO_LOST", item.getId(), reason);
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    /** Best-effort — IAuditService's writes already fail open; this call site is wrapped
     *  defensively too, so an audit-logging problem never blocks the real return/lost action. */
    private void logAuditAction(String eventType, Integer noteItemId, String details) {
        try {
            String username = TechnicianSessionService.getInstance().getUsername();
            ServiceLocator.getInstance().getAuditService().logAction(username, eventType, noteItemId, details);
        } catch (Exception ignored) { }
    }

    private String promptRejectionReason() {
        Stage stage = buildDialogStage();
        String[] result = {null};

        Label lblT = new Label("Motivo de pérdida");
        lblT.getStyleClass().add("section-label");

        Label lblSub = new Label("Descripción obligatoria de qué ocurrió con el equipo.");
        lblSub.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");

        TextArea ta = new TextArea();
        ta.setPromptText("Ej: Equipo robado / Extraviado / No devuelto...");
        ta.setPrefRowCount(3);
        ta.setWrapText(true);
        ta.getStyleClass().add("form-input-main");
        ta.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= REJECTION_REASON_MAX_LENGTH ? change : null));

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnOk = new Button("Confirmar pérdida");
        btnOk.getStyleClass().add("button-primary");
        btnOk.setDisable(true);
        ta.textProperty().addListener((o, ov, nv) -> btnOk.setDisable(nv.isBlank()));
        btnOk.setOnAction(e -> { result[0] = ta.getText().trim(); stage.close(); });

        HBox buttons = new HBox(8, btnCancel, btnOk);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(420);
        root.getChildren().addAll(lblT, lblSub, ta, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        Platform.runLater(ta::requestFocus);
        stage.showAndWait();

        return result[0];
    }

    @FXML
    private void handleClose() {
        ((Stage) rootContainer.getScene().getWindow()).close();
    }

    // Reprints the already-registered note shown in webPreview — for when the original physical
    // copy was lost. Not admin-gated: this only opens the OS print dialog on read-only, already-
    // saved data, the same low-risk action as viewing the note itself.
    @FXML
    private void handleReprint() {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) return;
        Stage stage = (Stage) rootContainer.getScene().getWindow();
        if (!job.showPrintDialog(stage)) return;
        PageLayout layout = job.getPrinter().createPageLayout(
            Paper.A4, PageOrientation.PORTRAIT, Printer.MarginType.HARDWARE_MINIMUM);
        job.getJobSettings().setPageLayout(layout);
        webPreview.getEngine().print(job);
        job.endJob();
    }

    // ── Static factory: open as modal stage ──────────────────────────────────

    public static void open(NoteReport report, boolean adminMode, Window owner, Runnable onUpdate)
            throws IOException {
        FXMLLoader loader = new FXMLLoader(PrestamoDetailController.class.getResource(
            "/com/bunshock/note_app_for_it_frontend/views/PrestamoDetailView.fxml"));
        Parent root = loader.load();
        PrestamoDetailController ctrl = loader.getController();
        ctrl.load(report, adminMode, onUpdate);

        VBox rootVBox = (VBox) root;

        StackPane borderFrame = new StackPane(rootVBox);
        borderFrame.setStyle("""
            -fx-background-color: #1a1a1a;
            -fx-background-radius: 12;
            -fx-padding: 2;
            """);

        Region shadowBacking = new Region();
        shadowBacking.prefWidthProperty().bind(borderFrame.widthProperty());
        shadowBacking.prefHeightProperty().bind(borderFrame.heightProperty());
        shadowBacking.setStyle("""
            -fx-background-color: white;
            -fx-background-radius: 12;
            -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 20, 0, 0, 5);
            """);

        StackPane wrapper = new StackPane(shadowBacking, borderFrame);
        wrapper.setStyle("-fx-background-color: transparent; -fx-padding: 32;");

        Scene scene = new Scene(wrapper);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(PrestamoDetailController.class.getResource(
            "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());

        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setScene(scene);
        stage.setOpacity(0);
        stage.setOnShown(e -> {
            double sidebarW = 235;
            double contentX = owner.getX() + sidebarW;
            double contentW = owner.getWidth() - sidebarW;
            stage.setX(contentX + (contentW - stage.getWidth())  / 2);
            stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
            stage.setOpacity(1);
        });
        stage.show();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Label smallLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: #475569; -fx-wrap-text: true;");
        return l;
    }

    private Label statusBadge(String text, String color) {
        Label l = new Label(text);
        l.setStyle(String.format(
            "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: %s; -fx-wrap-text: true;", color));
        return l;
    }

    // ── Dialog helpers (per admin dialog pattern — no shared base class) ──────

    private Stage buildDialogStage() {
        Stage s = new Stage();
        s.initStyle(StageStyle.TRANSPARENT);
        s.initModality(Modality.APPLICATION_MODAL);
        s.setOpacity(0);
        s.setOnShown(e -> {
            javafx.geometry.Bounds b = rootContainer.localToScreen(rootContainer.getBoundsInLocal());
            if (b != null) {
                s.setX(b.getMinX() + (b.getWidth()  - s.getWidth())  / 2);
                s.setY(b.getMinY() + (b.getHeight() - s.getHeight()) / 2);
            }
            s.setOpacity(1);
        });
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
}
