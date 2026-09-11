package com.bunshock.note_app_for_it_frontend.controllers.history;

import java.io.IOException;

import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.models.admin.Permission;
import com.bunshock.note_app_for_it_frontend.models.history.ReturnAllocationBatch;
import com.bunshock.note_app_for_it_frontend.models.history.ReturnStatus;
import com.bunshock.note_app_for_it_frontend.services.auth.AdminSession;
import com.bunshock.note_app_for_it_frontend.models.auth.Roles;
import com.bunshock.note_app_for_it_frontend.services.note.NoteGenerationService;
import com.bunshock.note_app_for_it_frontend.services.history.PendingCountsService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
import com.bunshock.note_app_for_it_frontend.utils.core.DialogChrome;
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
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
    @FXML private VBox    vboxApproval;
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

        buildApprovalSection();
        buildItemCards();
    }

    // Duplicated from NoteDetailController's identical section per this codebase's
    // no-shared-abstraction convention (same precedent as buildReturnStatusRow()/
    // buildGlpiStatusRow() already being independent between these two controllers).
    private void buildApprovalSection() {
        vboxApproval.getChildren().clear();
        String status = report.getApprovalStatus();
        if (status == null) status = "PENDING";

        String badgeText;
        String badgeColor;
        switch (status) {
            case "APPROVED" -> {
                badgeText = "✓ Aprobada";
                badgeColor = "#22c55e";
            }
            case "REJECTED" -> {
                String reason = report.getRejectionReason() != null ? ": " + report.getRejectionReason() : "";
                badgeText = "✗ Rechazada" + reason;
                badgeColor = "#ef4444";
            }
            default -> {
                badgeText = "⏳ Pendiente de aprobación";
                badgeColor = "#f97316";
            }
        }

        HBox statusRow = new HBox(4);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.getChildren().addAll(DetailCardLabels.prefixLabel("Aprobación: "), DetailCardLabels.statusBadge(badgeText, badgeColor));
        vboxApproval.getChildren().add(statusRow);

        if (isSedeMismatchForAdmin()) {
            Label warning = new Label(
                "⚠ Esta nota pertenece a otra Sede y no puede ser auditada por este administrador.");
            warning.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #f59e0b;");
            warning.setWrapText(true);
            vboxApproval.getChildren().add(warning);
        }

        if ("PENDING".equals(status) && adminMode
                && AdminSession.getInstance().hasPermission(Permission.APPROVE_NOTES, noteSedeIdOrNull())) {
            Button btnApprove = new Button("Aprobar");
            btnApprove.getStyleClass().add("button-primary");
            btnApprove.setOnAction(e -> handleApprove());

            Button btnRejectNote = new Button("Rechazar");
            btnRejectNote.getStyleClass().add("button-secondary");
            btnRejectNote.setOnAction(e -> handleRejectNote());

            vboxApproval.getChildren().add(new HBox(8, btnApprove, btnRejectNote));
        }
    }

    // 0 means "no Sede on this note" (SEDE ids are auto-increment starting at 1, so it can never
    // be a real one) — treated as null, same fail-safe "no match" outcome as an admin with no
    // Sede assigned.
    private Integer noteSedeIdOrNull() {
        int id = report.getSedeId();
        return id > 0 ? id : null;
    }

    // SUPERADMIN bypasses Sede scoping entirely (see AdminSession.hasPermission(Permission,
    // Integer)) — this is purely a UI hint for the ADMIN case, shown regardless of the note's own
    // approval status, since the return-tracking row is Sede-scoped the same way even after
    // approval.
    private boolean isSedeMismatchForAdmin() {
        if (!adminMode || !AdminSession.getInstance().isActive()) return false;
        if (Roles.SUPERADMIN.equals(AdminSession.getInstance().getEffectiveRole())) return false;
        Integer mySedeId = TechnicianSessionService.getInstance().getSedeId();
        Integer noteSedeId = noteSedeIdOrNull();
        return mySedeId == null || noteSedeId == null || !mySedeId.equals(noteSedeId);
    }

    private void handleApprove() {
        AdminSession.getInstance().refreshActivity();
        try {
            ServiceLocator.getInstance().getHistoryService().updateNoteApprovalStatus(report.getId(), "APPROVED", null);
        } catch (RuntimeException e) {
            // Approval can fail for a real, user-facing reason now — most commonly insufficient
            // stock at the note's Sede (409 STOCK_WOULD_GO_NEGATIVE from the middleware). Must not
            // update local state/rebuild the UI as if it succeeded when it didn't.
            showApprovalError(e.getMessage());
            return;
        }
        // Middleware writes AUDIT_ADMIN_ACTION server-side now — no local audit call needed.
        report.setApprovalStatus("APPROVED");
        report.setRejectionReason(null);
        PendingCountsService.getInstance().notifyChanged();
        buildApprovalSection();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private void showApprovalError(String message) {
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle("No se pudo aprobar la nota");
        alert.setHeaderText(null);
        alert.setContentText(message != null ? message : "Ocurrió un error inesperado al aprobar la nota.");
        alert.showAndWait();
    }

    private void handleRejectNote() {
        AdminSession.getInstance().refreshActivity();
        String reason = promptNoteRejectionReason();
        if (reason == null) return;
        ServiceLocator.getInstance().getHistoryService().updateNoteApprovalStatus(report.getId(), "REJECTED", reason);
        // Middleware writes AUDIT_ADMIN_ACTION server-side now — no local audit call needed.
        report.setApprovalStatus("REJECTED");
        report.setRejectionReason(reason);
        PendingCountsService.getInstance().notifyChanged();
        buildApprovalSection();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private String promptNoteRejectionReason() {
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);
        String[] result = {null};

        Label lblT = new Label("Motivo de rechazo de la nota");
        lblT.getStyleClass().add("section-label");

        Label lblSub = new Label("Descripción obligatoria del motivo de rechazo de esta nota.");
        lblSub.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");

        TextArea ta = new TextArea();
        ta.setPromptText("Ej: Tipo de nota incorrecto / Nota duplicada / Creada por error...");
        ta.setPrefRowCount(3);
        ta.setWrapText(true);
        ta.getStyleClass().add("form-input-main");
        ta.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= REJECTION_REASON_MAX_LENGTH ? change : null));

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnOk = new Button("Confirmar rechazo");
        btnOk.getStyleClass().add("button-primary");
        btnOk.setDisable(true);
        ta.textProperty().addListener((o, ov, nv) -> btnOk.setDisable(nv.isBlank()));
        btnOk.setOnAction(e -> { result[0] = ta.getText().trim(); stage.close(); });

        HBox buttons = new HBox(8, btnCancel, btnOk);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = DialogChrome.buildDialogRoot(420, "#1a1a1a");
        root.getChildren().addAll(lblT, lblSub, ta, buttons);

        Scene scene = DialogChrome.buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        Platform.runLater(ta::requestFocus);
        stage.showAndWait();

        return result[0];
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
                card.getChildren().add(DetailCardLabels.smallLabel("S/N: " + item.getSerialNumber()));
            if (item.getAf() != null && !item.getAf().isBlank())
                card.getChildren().add(DetailCardLabels.smallLabel("A/F: " + item.getAf()));
        } else {
            card.getChildren().add(DetailCardLabels.smallLabel("Cantidad: " + item.getQuantity()));
        }

        if (item.getObservations() != null && !item.getObservations().isBlank())
            card.getChildren().add(DetailCardLabels.smallLabel("Obs: " + item.getObservations()));

        // Shown unconditionally, regardless of approval status — this is exactly the information
        // an admin needs before deciding whether to approve the note (ItemDialogController's
        // "Modifica stock" checkbox), so it can't be gated behind approval already having happened.
        if (!item.isModifiesStock()) {
            String reason = item.getModifiesStockReason();
            String badgeText = "⚠ No modifica stock" + (reason != null && !reason.isBlank() ? ": " + reason : "");
            card.getChildren().add(DetailCardLabels.statusBadge(badgeText, "#f97316"));
        }

        // Same gate as NoteDetailController's buildItemCard() — no item-level action row until
        // the note itself has been approved.
        if ("APPROVED".equals(report.getApprovalStatus())) {
            card.getChildren().add(buildReturnStatusRow(item));
        }
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
        return item.isAsset() ? buildAssetReturnStatusRow(item) : buildCountableReturnStatusRow(item);
    }

    private VBox buildAssetReturnStatusRow(NoteReportItem item) {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setStyle("-fx-padding: 4 0 0 0;");

        ReturnStatus status = item.getReturnStatus();

        String badgeText;
        String badgeColor;
        boolean showActions = false;

        switch (status) {
            case RETURNED -> {
                String ts = item.getReturnStatusUpdatedAt() != null ? " · " + formatStatusTimestamp(item.getReturnStatusUpdatedAt()) : "";
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
        statusRow.getChildren().addAll(DetailCardLabels.prefixLabel("Préstamo: "), DetailCardLabels.statusBadge(badgeText, badgeColor));
        box.getChildren().add(statusRow);

        if (showActions && adminMode
                && AdminSession.getInstance().hasPermission(Permission.VALIDATE_RETURNS, noteSedeIdOrNull())) {
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

    // A countable item (quantity > 1) can be resolved in partial batches — e.g. 5 loaned
    // headsets: 3 returned now, 1 lost later, 1 still pending — so unlike an asset it never
    // collapses to one whole-item badge. Instead shows one badge per non-zero quantity bucket
    // (pending/devuelto/perdido), and while some quantity is still pending, a quantity field +
    // action button per outcome, each on its own row (same width-constrained-panel precedent as
    // the asset buttons above, which is why "Devuelto"/"No devuelto" already sit on separate rows
    // here rather than side-by-side with an extra input each).
    private VBox buildCountableReturnStatusRow(NoteReportItem item) {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setStyle("-fx-padding: 4 0 0 0;");

        if (item.getReturnStatus() == ReturnStatus.N_A) {
            HBox statusRow = new HBox(4);
            statusRow.setAlignment(Pos.CENTER_LEFT);
            statusRow.getChildren().addAll(DetailCardLabels.prefixLabel("Préstamo: "), DetailCardLabels.statusBadge("—", "#94a3b8"));
            box.getChildren().add(statusRow);
            return box;
        }

        int pending = item.getReturnPendingQuantity();

        HBox prefixRow = new HBox(4);
        prefixRow.setAlignment(Pos.CENTER_LEFT);
        prefixRow.getChildren().add(DetailCardLabels.prefixLabel("Préstamo: "));
        box.getChildren().add(prefixRow);

        if (pending > 0) box.getChildren().add(DetailCardLabels.statusBadge("⏳ " + qtyLabel("Pendiente", "Pendientes", pending) + ": " + pending, "#f97316"));
        // One badge per batch, not one aggregate line, for both LOST and RETURNED — each batch is
        // one real event with its own single timestamp (LOST also has its own reason), so this is
        // the only way to show a meaningful "when" for each (an aggregate sum can represent
        // several separate events with no single correct moment to show).
        for (ReturnAllocationBatch batch : item.getLostBatches()) {
            String reason = batch.getReason() != null && !batch.getReason().isBlank() ? " (" + batch.getReason() + ")" : "";
            String ts = batch.getUpdatedAt() != null ? " · " + formatStatusTimestamp(batch.getUpdatedAt()) : "";
            box.getChildren().add(DetailCardLabels.statusBadge("✗ " + qtyLabel("Perdido", "Perdidos", batch.getQuantity()) + ": " + batch.getQuantity() + reason + ts, "#ef4444"));
        }
        for (ReturnAllocationBatch batch : item.getReturnedBatches()) {
            String ts = batch.getUpdatedAt() != null ? " · " + formatStatusTimestamp(batch.getUpdatedAt()) : "";
            box.getChildren().add(DetailCardLabels.statusBadge("✓ " + qtyLabel("Devuelto", "Devueltos", batch.getQuantity()) + ": " + batch.getQuantity() + ts, "#22c55e"));
        }

        if (pending > 0 && adminMode
                && AdminSession.getInstance().hasPermission(Permission.VALIDATE_RETURNS, noteSedeIdOrNull())) {
            TextField qtyReturn = buildQuantityField(pending);
            Button btnReturn = new Button("Devuelto");
            btnReturn.getStyleClass().add("button-primary");
            btnReturn.setStyle(btnReturn.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
            btnReturn.setOnAction(e -> handleCountableReturn(item, qtyReturn));
            box.getChildren().add(new HBox(6, qtyReturn, btnReturn));

            TextField qtyLost = buildQuantityField(pending);
            Button btnLost = new Button("No devuelto");
            btnLost.getStyleClass().add("button-secondary");
            btnLost.setStyle(btnLost.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
            btnLost.setOnAction(e -> handleCountableLost(item, qtyLost));
            box.getChildren().add(new HBox(6, qtyLost, btnLost));
        }

        return box;
    }

    private String qtyLabel(String singular, String plural, int count) {
        return count == 1 ? singular : plural;
    }

    // "dd/MM/yyyy HH:mm hs" for a status badge's own timestamp — was a raw ISO-ish substring
    // (e.g. "2026-07-31T13:17"), unreadable at a glance. Falls back to the raw stored value
    // rather than throwing if it can't be parsed.
    private String formatStatusTimestamp(String storedTimestamp) {
        if (storedTimestamp == null) return "";
        try {
            return java.time.LocalDateTime.parse(storedTimestamp)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'hs'"));
        } catch (Exception e) {
            return storedTimestamp;
        }
    }

    private TextField buildQuantityField(int max) {
        TextField tf = new TextField(String.valueOf(max));
        tf.setPrefWidth(50);
        tf.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.isEmpty()) return change;
            if (!newText.matches("\\d+")) return null;
            try {
                return Integer.parseInt(newText) <= max ? change : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }));
        return tf;
    }

    private int parseQuantity(TextField field, int max) {
        try {
            int v = Integer.parseInt(field.getText().trim());
            return Math.min(Math.max(v, 0), max);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void handleCountableReturn(NoteReportItem item, TextField qtyField) {
        AdminSession.getInstance().refreshActivity();
        int qty = parseQuantity(qtyField, item.getReturnPendingQuantity());
        if (qty <= 0) return;
        ServiceLocator.getInstance().getHistoryService()
            .allocateCountableReturn(item.getId(), ReturnStatus.RETURNED, qty, null);
        // Middleware writes AUDIT_ITEM_STATUS server-side now — no local audit call needed.
        item.setReturnedQuantity(item.getReturnedQuantity() + qty);
        // Without this, the new batch wouldn't show up in buildCountableReturnStatusRow()'s loop
        // until the popup was closed and reopened — updateItemReturnStatus()/allocateCountableReturn()
        // write the real data to the DB, but this in-memory item was never told about it.
        item.getReturnedBatches().add(new ReturnAllocationBatch(qty, null, java.time.LocalDateTime.now().toString()));
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private void handleCountableLost(NoteReportItem item, TextField qtyField) {
        AdminSession.getInstance().refreshActivity();
        int qty = parseQuantity(qtyField, item.getReturnPendingQuantity());
        if (qty <= 0) return;
        String reason = promptRejectionReason();
        if (reason == null) return;
        ServiceLocator.getInstance().getHistoryService()
            .allocateCountableReturn(item.getId(), ReturnStatus.LOST, qty, reason);
        // Middleware writes AUDIT_ITEM_STATUS server-side now — no local audit call needed.
        item.setLostQuantity(item.getLostQuantity() + qty);
        item.getLostBatches().add(new ReturnAllocationBatch(qty, reason, java.time.LocalDateTime.now().toString()));
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private void handleReturn(NoteReportItem item, Button btnReturn) {
        AdminSession.getInstance().refreshActivity();
        btnReturn.setDisable(true);
        ServiceLocator.getInstance().getHistoryService()
            .updateItemReturnStatus(item.getId(), ReturnStatus.RETURNED, null);
        // Middleware writes AUDIT_ITEM_STATUS server-side now — no local audit call needed.
        item.setReturnStatus(ReturnStatus.RETURNED);
        item.setReturnStatusUpdatedAt(java.time.LocalDateTime.now().toString());
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
        // Middleware writes AUDIT_ITEM_STATUS server-side now — no local audit call needed.
        item.setReturnStatus(ReturnStatus.LOST);
        item.setReturnRejectionReason(reason);
        item.setReturnStatusUpdatedAt(java.time.LocalDateTime.now().toString());
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private String promptRejectionReason() {
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);
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

        VBox root = DialogChrome.buildDialogRoot(420, "#1a1a1a");
        root.getChildren().addAll(lblT, lblSub, ta, buttons);

        Scene scene = DialogChrome.buildDialogScene(root);
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

    // Dialog helpers (buildDialogStage/buildDialogRoot/buildDialogScene/centerOnContent) live in
    // utils.core.DialogChrome, shared across every controller that opens a dialog.
}
