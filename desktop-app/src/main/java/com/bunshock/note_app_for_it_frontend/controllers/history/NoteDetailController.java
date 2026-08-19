package com.bunshock.note_app_for_it_frontend.controllers.history;

import java.io.IOException;

import com.bunshock.note_app_for_it_frontend.models.history.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.models.admin.Permission;
import com.bunshock.note_app_for_it_frontend.models.history.ReturnAllocationBatch;
import com.bunshock.note_app_for_it_frontend.models.history.ReturnStatus;
import com.bunshock.note_app_for_it_frontend.services.auth.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.admin.IUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.history.IHistoryService;
import com.bunshock.note_app_for_it_frontend.services.note.NoteGenerationService;
import com.bunshock.note_app_for_it_frontend.services.history.PendingCountsService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
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

public class NoteDetailController {

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
        lblTitle.setText(NoteReport.toDisplayName(report.getProfileType()) + " — " + date);

        try {
            String html = GEN_SVC.generateFromStoredReport(report);
            webPreview.getEngine().loadContent(html);
        } catch (IOException e) {
            webPreview.getEngine().loadContent("<p style='color:red'>Error al renderizar la nota.</p>");
        }

        buildApprovalSection();
        buildItemCards();
    }

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
    // approval status, since GLPI/return-tracking rows are Sede-scoped the same way even after
    // approval.
    private boolean isSedeMismatchForAdmin() {
        if (!adminMode || !AdminSession.getInstance().isActive()) return false;
        if (IUserRoleService.ROLE_SUPERADMIN.equals(AdminSession.getInstance().getEffectiveRole())) return false;
        Integer mySedeId = TechnicianSessionService.getInstance().getSedeId();
        Integer noteSedeId = noteSedeIdOrNull();
        return mySedeId == null || noteSedeId == null || !mySedeId.equals(noteSedeId);
    }

    private void handleApprove() {
        AdminSession.getInstance().refreshActivity();
        String oldStatus = report.getApprovalStatus();
        try {
            ServiceLocator.getInstance().getHistoryService().updateNoteApprovalStatus(report.getId(), "APPROVED", null);
        } catch (RuntimeException e) {
            // Approval can fail for a real, user-facing reason now — most commonly insufficient
            // stock at the note's Sede (see SqliteHistoryService.applyNoteStockIfNeeded()'s
            // IllegalArgumentException). Must not update local state/rebuild the UI as if it
            // succeeded when it didn't.
            showApprovalError(e.getMessage());
            return;
        }
        ServiceLocator.getInstance().getAuditService().recordAdminAction(
            TechnicianSessionService.getInstance().getUsername(), "APPROVE_NOTE", "NOTE_REPORT",
            String.valueOf(report.getId()), oldStatus, "APPROVED", null);
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
        String oldStatus = report.getApprovalStatus();
        ServiceLocator.getInstance().getHistoryService().updateNoteApprovalStatus(report.getId(), "REJECTED", reason);
        ServiceLocator.getInstance().getAuditService().recordAdminAction(
            TechnicianSessionService.getInstance().getUsername(), "REJECT_NOTE", "NOTE_REPORT",
            String.valueOf(report.getId()), oldStatus, "REJECTED", reason);
        report.setApprovalStatus("REJECTED");
        report.setRejectionReason(reason);
        PendingCountsService.getInstance().notifyChanged();
        buildApprovalSection();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private String promptNoteRejectionReason() {
        Stage stage = buildDialogStage();
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

        VBox root = buildDialogRoot(420);
        root.getChildren().addAll(lblT, lblSub, ta, buttons);

        Scene scene = buildDialogScene(root);
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

        // PENDING/RECHAZADO notes show no item-level action rows at all — an admin must approve
        // the note itself first (see buildApprovalSection() above) before GLPI sync/reject or the
        // Provider return-tracking row become reachable.
        if ("APPROVED".equals(report.getApprovalStatus())) {
            if (item.isAsset()) {
                card.getChildren().add(buildGlpiStatusRow(item));
                // Row absence means this dimension isn't applicable yet — only ever created once
                // the item's return has been validated (see handleProviderReceived()).
                if (item.getGlpiReturnStatus() != GlpiStatus.N_A) {
                    card.getChildren().add(buildGlpiReturnStatusRow(item));
                }
            } else {
                card.getChildren().add(DetailCardLabels.statusBadge("— Sin acción GLPI", "#94a3b8"));
            }
            if (isProviderReturnableNote()) {
                card.getChildren().add(buildProviderReturnStatusRow(item));
            }
        }
        return card;
    }

    // Config-driven (AppConfig.returnableMotivosProveedor) — a returnable Provider note's items
    // (asset or countable alike, same "whole note" semantics as Préstamo) get this row alongside
    // the GLPI row above; a non-returnable Provider note (or any other note type) gets neither
    // this nor any special handling here.
    private boolean isProviderReturnableNote() {
        return IHistoryService.isProviderReturnable(report.getProfileType(), report.getMotivo());
    }

    // Same underlying ReturnStatus/NOTE_ITEM_RETURN_TRACKING mechanism as Préstamo's own
    // buildReturnStatusRow() (PrestamoDetailController) — only the presentation differs, per
    // explicit design: "received back from the provider" reads differently than "returned by the
    // borrower", so the wording here is deliberately NOT a copy of Préstamo's.
    private VBox buildProviderReturnStatusRow(NoteReportItem item) {
        return item.isAsset() ? buildProviderAssetReturnStatusRow(item) : buildProviderCountableReturnStatusRow(item);
    }

    private VBox buildProviderAssetReturnStatusRow(NoteReportItem item) {
        VBox box = new VBox(8);
        box.setStyle("-fx-padding: 4 0 0 0;");

        ReturnStatus status = item.getReturnStatus();

        String badgeText;
        String badgeColor;
        boolean showActions = false;

        switch (status) {
            case RETURNED -> {
                String ts = item.getReturnStatusUpdatedAt() != null ? " · " + formatStatusTimestamp(item.getReturnStatusUpdatedAt()) : "";
                badgeText = "✓ Recibido" + ts;
                badgeColor = "#22c55e";
            }
            case LOST -> {
                String reason = item.getReturnRejectionReason() != null ? ": " + item.getReturnRejectionReason() : "";
                badgeText = "✗ No recibido" + reason;
                badgeColor = "#ef4444";
            }
            case PENDING -> {
                badgeText = "⏳ Pendiente recepción";
                badgeColor = "#f97316";
                // Recibido/No recibido only becomes available once GLPI sync (1) has actually
                // gone through — validating a return before GLPI even confirms the item was sent
                // out would let the two dimensions get out of order for no real benefit.
                showActions = item.getGlpiStatus() == GlpiStatus.SYNCED;
            }
            default -> {
                badgeText = "—";
                badgeColor = "#94a3b8";
            }
        }

        HBox statusRow = new HBox(4);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.getChildren().addAll(DetailCardLabels.prefixLabel("Proveedor: "), DetailCardLabels.statusBadge(badgeText, badgeColor));
        box.getChildren().add(statusRow);

        if (status == ReturnStatus.PENDING && item.getGlpiStatus() != GlpiStatus.SYNCED) {
            box.getChildren().add(DetailCardLabels.smallLabel("Debe sincronizar GLPI antes de validar la recepción"));
        }

        if (showActions && adminMode
                && AdminSession.getInstance().hasPermission(Permission.VALIDATE_RETURNS, noteSedeIdOrNull())) {
            Button btnReceived = new Button("Recibido");
            btnReceived.getStyleClass().add("button-primary");
            btnReceived.setStyle(btnReceived.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
            btnReceived.setOnAction(e -> handleProviderReceived(item, btnReceived));

            Button btnNotReceived = new Button("No recibido");
            btnNotReceived.getStyleClass().add("button-secondary");
            btnNotReceived.setStyle(btnNotReceived.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
            btnNotReceived.setOnAction(e -> handleProviderNotReceived(item));

            box.getChildren().add(new HBox(8, btnReceived, btnNotReceived));
        }

        return box;
    }

    // Same partial-quantity reasoning as PrestamoDetailController.buildCountableReturnStatusRow()
    // — a returnable Provider note's countable (e.g. 5 headsets sent for warranty) can come back
    // in batches too, just with "received from the provider" wording instead of "returned by the
    // borrower". Duplicated per this codebase's no-shared-abstraction convention.
    private VBox buildProviderCountableReturnStatusRow(NoteReportItem item) {
        VBox box = new VBox(8);
        box.setStyle("-fx-padding: 4 0 0 0;");

        if (item.getReturnStatus() == ReturnStatus.N_A) {
            HBox statusRow = new HBox(4);
            statusRow.setAlignment(Pos.CENTER_LEFT);
            statusRow.getChildren().addAll(DetailCardLabels.prefixLabel("Proveedor: "), DetailCardLabels.statusBadge("—", "#94a3b8"));
            box.getChildren().add(statusRow);
            return box;
        }

        int pending = item.getReturnPendingQuantity();

        HBox prefixRow = new HBox(4);
        prefixRow.setAlignment(Pos.CENTER_LEFT);
        prefixRow.getChildren().add(DetailCardLabels.prefixLabel("Proveedor: "));
        box.getChildren().add(prefixRow);

        if (pending > 0) box.getChildren().add(DetailCardLabels.statusBadge("⏳ " + qtyLabel("Pendiente", "Pendientes", pending) + ": " + pending, "#f97316"));
        // One badge per batch, not one aggregate line, for both "no recibido" and "recibido" —
        // each batch is one real event with its own single timestamp (a "no recibido" batch also
        // has its own reason), so this is the only way to show a meaningful "when" for each.
        for (ReturnAllocationBatch batch : item.getLostBatches()) {
            String reason = batch.getReason() != null && !batch.getReason().isBlank() ? " (" + batch.getReason() + ")" : "";
            String ts = batch.getUpdatedAt() != null ? " · " + formatStatusTimestamp(batch.getUpdatedAt()) : "";
            box.getChildren().add(DetailCardLabels.statusBadge("✗ " + qtyLabel("No recibido", "No recibidos", batch.getQuantity()) + ": " + batch.getQuantity() + reason + ts, "#ef4444"));
        }
        for (ReturnAllocationBatch batch : item.getReturnedBatches()) {
            String ts = batch.getUpdatedAt() != null ? " · " + formatStatusTimestamp(batch.getUpdatedAt()) : "";
            box.getChildren().add(DetailCardLabels.statusBadge("✓ " + qtyLabel("Recibido", "Recibidos", batch.getQuantity()) + ": " + batch.getQuantity() + ts, "#22c55e"));
        }

        if (pending > 0 && adminMode
                && AdminSession.getInstance().hasPermission(Permission.VALIDATE_RETURNS, noteSedeIdOrNull())) {
            TextField qtyReceived = buildQuantityField(pending);
            Button btnReceived = new Button("Recibido");
            btnReceived.getStyleClass().add("button-primary");
            btnReceived.setStyle(btnReceived.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
            btnReceived.setOnAction(e -> handleProviderCountableReceived(item, qtyReceived));
            box.getChildren().add(new HBox(6, qtyReceived, btnReceived));

            TextField qtyNotReceived = buildQuantityField(pending);
            Button btnNotReceived = new Button("No recibido");
            btnNotReceived.getStyleClass().add("button-secondary");
            btnNotReceived.setStyle(btnNotReceived.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
            btnNotReceived.setOnAction(e -> handleProviderCountableNotReceived(item, qtyNotReceived));
            box.getChildren().add(new HBox(6, qtyNotReceived, btnNotReceived));
        }

        return box;
    }

    private String qtyLabel(String singular, String plural, int count) {
        return count == 1 ? singular : plural;
    }

    // "dd/MM/yyyy HH:mm hs" for a status badge's own timestamp (GLPI sync, Préstamo/Proveedor
    // return) — was a raw ISO-ish substring (e.g. "2026-07-31T13:17"), unreadable at a glance.
    // Falls back to the raw stored value rather than throwing if it can't be parsed.
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

    private void handleProviderReceived(NoteReportItem item, Button btnReceived) {
        AdminSession.getInstance().refreshActivity();
        btnReceived.setDisable(true);
        ReturnStatus oldStatus = item.getReturnStatus();
        ServiceLocator.getInstance().getHistoryService()
            .updateItemReturnStatus(item.getId(), ReturnStatus.RETURNED, null);
        ServiceLocator.getInstance().getAuditService().recordItemStatusChange(item.getId(), "RETURN",
            oldStatus.toDbString(), ReturnStatus.RETURNED.toDbString(), null, 1,
            TechnicianSessionService.getInstance().getUsername());
        item.setReturnStatus(ReturnStatus.RETURNED);
        item.setReturnStatusUpdatedAt(java.time.LocalDateTime.now().toString());
        // Seeds the second, independent GLPI dimension now that the return is validated — GLPI
        // sync is one-way/no-revert, so the original sync-out can't be undone to reflect the item
        // coming back; this is a separate "synced back in" event instead. See
        // NOTE_ITEM_GLPI_RETURN_TRACKING's own doc in DatabaseService.
        ServiceLocator.getInstance().getHistoryService()
            .updateItemGlpiReturnStatus(item.getId(), GlpiStatus.PENDING, null);
        item.setGlpiReturnStatus(GlpiStatus.PENDING);
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private void handleProviderNotReceived(NoteReportItem item) {
        AdminSession.getInstance().refreshActivity();
        String reason = promptProviderNotReceivedReason();
        if (reason == null) return;
        ReturnStatus oldStatus = item.getReturnStatus();
        ServiceLocator.getInstance().getHistoryService()
            .updateItemReturnStatus(item.getId(), ReturnStatus.LOST, reason);
        ServiceLocator.getInstance().getAuditService().recordItemStatusChange(item.getId(), "RETURN",
            oldStatus.toDbString(), ReturnStatus.LOST.toDbString(), reason, 1,
            TechnicianSessionService.getInstance().getUsername());
        item.setReturnStatus(ReturnStatus.LOST);
        item.setReturnRejectionReason(reason);
        item.setReturnStatusUpdatedAt(java.time.LocalDateTime.now().toString());
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private void handleProviderCountableReceived(NoteReportItem item, TextField qtyField) {
        AdminSession.getInstance().refreshActivity();
        int qty = parseQuantity(qtyField, item.getReturnPendingQuantity());
        if (qty <= 0) return;
        ServiceLocator.getInstance().getHistoryService()
            .allocateCountableReturn(item.getId(), ReturnStatus.RETURNED, qty, null);
        ServiceLocator.getInstance().getAuditService().recordItemStatusChange(item.getId(), "RETURN",
            ReturnStatus.PENDING.toDbString(), ReturnStatus.RETURNED.toDbString(), null, qty,
            TechnicianSessionService.getInstance().getUsername());
        item.setReturnedQuantity(item.getReturnedQuantity() + qty);
        // Same "in-memory item must reflect what was just written" fix as the whole-item status
        // handlers above — without appending this batch here too, the new line wouldn't show up
        // in buildProviderCountableReturnStatusRow()'s loop until the popup was reopened.
        item.getReturnedBatches().add(new ReturnAllocationBatch(qty, null, java.time.LocalDateTime.now().toString()));
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private void handleProviderCountableNotReceived(NoteReportItem item, TextField qtyField) {
        AdminSession.getInstance().refreshActivity();
        int qty = parseQuantity(qtyField, item.getReturnPendingQuantity());
        if (qty <= 0) return;
        String reason = promptProviderNotReceivedReason();
        if (reason == null) return;
        ServiceLocator.getInstance().getHistoryService()
            .allocateCountableReturn(item.getId(), ReturnStatus.LOST, qty, reason);
        ServiceLocator.getInstance().getAuditService().recordItemStatusChange(item.getId(), "RETURN",
            ReturnStatus.PENDING.toDbString(), ReturnStatus.LOST.toDbString(), reason, qty,
            TechnicianSessionService.getInstance().getUsername());
        item.setLostQuantity(item.getLostQuantity() + qty);
        item.getLostBatches().add(new ReturnAllocationBatch(qty, reason, java.time.LocalDateTime.now().toString()));
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private String promptProviderNotReceivedReason() {
        Stage stage = buildDialogStage();
        String[] result = {null};

        Label lblT = new Label("Motivo de no recepción");
        lblT.getStyleClass().add("section-label");

        Label lblSub = new Label("Descripción obligatoria de por qué el equipo no fue recibido.");
        lblSub.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");

        TextArea ta = new TextArea();
        ta.setPromptText("Ej: Proveedor no envió el equipo / Equipo dañado en tránsito...");
        ta.setPrefRowCount(3);
        ta.setWrapText(true);
        ta.getStyleClass().add("form-input-main");
        ta.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= REJECTION_REASON_MAX_LENGTH ? change : null));

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnOk = new Button("Confirmar");
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

    // A VBox, not a single HBox row — the "GLPI: " prefix + status badge sit on their own row in
    // EVERY state (not just PENDING — previously the prefix only showed while pending, so it
    // vanished the moment Sincronizar/Rechazar was clicked and the row re-rendered as SYNCED/
    // REJECTED, which read as the label being deleted), with the Sincronizar/Rechazar buttons
    // (PENDING only) on a separate row below, given extra spacing (8, not 4) so the button row
    // doesn't crowd the label row above it.
    private VBox buildGlpiStatusRow(NoteReportItem item) {
        VBox box = new VBox(8);
        box.setStyle("-fx-padding: 4 0 0 0;");

        GlpiStatus status = item.getGlpiStatus();

        String badgeText;
        String badgeColor;
        boolean showActions = false;

        switch (status) {
            case SYNCED -> {
                String ts = item.getGlpiStatusUpdatedAt() != null ? " · " + formatStatusTimestamp(item.getGlpiStatusUpdatedAt()) : "";
                badgeText = "✓ Sincronizado" + ts;
                badgeColor = "#22c55e";
            }
            case REJECTED -> {
                String reason = item.getGlpiRejectionReason() != null ? ": " + item.getGlpiRejectionReason() : "";
                badgeText = "✗ Rechazado" + reason;
                badgeColor = "#ef4444";
            }
            case PENDING -> {
                badgeText = "⏳ Pendiente sincronización";
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
        statusRow.getChildren().addAll(DetailCardLabels.prefixLabel("GLPI: "), DetailCardLabels.statusBadge(badgeText, badgeColor));
        box.getChildren().add(statusRow);

        if (showActions && adminMode
                && AdminSession.getInstance().hasPermission(Permission.SYNC_GLPI, noteSedeIdOrNull())) {
            Button btnSync = new Button("Sincronizar");
            btnSync.getStyleClass().add("button-primary");
            btnSync.setStyle(btnSync.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
            btnSync.setOnAction(e -> handleSync(item, btnSync));

            Button btnReject = new Button("Rechazar");
            btnReject.getStyleClass().add("button-secondary");
            btnReject.setStyle(btnReject.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
            btnReject.setOnAction(e -> handleReject(item));

            box.getChildren().add(new HBox(8, btnSync, btnReject));
        }

        return box;
    }

    private void handleSync(NoteReportItem item, Button btnSync) {
        AdminSession.getInstance().refreshActivity();
        btnSync.setDisable(true);
        GlpiStatus oldStatus = item.getGlpiStatus();
        ServiceLocator.getInstance().getHistoryService()
            .updateItemGlpiStatus(item.getId(), GlpiStatus.SYNCED, null);
        ServiceLocator.getInstance().getAuditService().recordItemStatusChange(item.getId(), "GLPI",
            oldStatus.toDbString(), GlpiStatus.SYNCED.toDbString(), null, 1,
            TechnicianSessionService.getInstance().getUsername());
        item.setGlpiStatus(GlpiStatus.SYNCED);
        // Without this, the badge kept showing no timestamp at all until the popup was closed
        // and reopened — updateItemGlpiStatus() above writes the real timestamp to the DB, but
        // this in-memory item was never told about it, so buildGlpiStatusRow() had nothing to
        // format. A few ms off from the DB's own LocalDateTime.now() call is immaterial at this
        // display's minute-level granularity.
        item.setGlpiStatusUpdatedAt(java.time.LocalDateTime.now().toString());
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private void handleReject(NoteReportItem item) {
        AdminSession.getInstance().refreshActivity();
        String reason = promptRejectionReason();
        if (reason == null) return;
        GlpiStatus oldStatus = item.getGlpiStatus();
        ServiceLocator.getInstance().getHistoryService()
            .updateItemGlpiStatus(item.getId(), GlpiStatus.REJECTED, reason);
        ServiceLocator.getInstance().getAuditService().recordItemStatusChange(item.getId(), "GLPI",
            oldStatus.toDbString(), GlpiStatus.REJECTED.toDbString(), reason, 1,
            TechnicianSessionService.getInstance().getUsername());
        item.setGlpiStatus(GlpiStatus.REJECTED);
        item.setGlpiRejectionReason(reason);
        item.setGlpiStatusUpdatedAt(java.time.LocalDateTime.now().toString());
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    // Second, independent GLPI dimension — only ever rendered once a returnable Provider note's
    // asset has had its return validated (handleProviderReceived() is what seeds the row this
    // reads). Same shape as buildGlpiStatusRow() above, just targeting
    // updateItemGlpiReturnStatus() instead — see NOTE_ITEM_GLPI_RETURN_TRACKING's own doc in
    // DatabaseService for why this can't just reuse the original sync-out dimension.
    private VBox buildGlpiReturnStatusRow(NoteReportItem item) {
        VBox box = new VBox(8);
        box.setStyle("-fx-padding: 4 0 0 0;");

        GlpiStatus status = item.getGlpiReturnStatus();

        String badgeText;
        String badgeColor;
        boolean showActions = false;

        switch (status) {
            case SYNCED -> {
                String ts = item.getGlpiReturnStatusUpdatedAt() != null ? " · " + formatStatusTimestamp(item.getGlpiReturnStatusUpdatedAt()) : "";
                badgeText = "✓ Sincronizado" + ts;
                badgeColor = "#22c55e";
            }
            case REJECTED -> {
                String reason = item.getGlpiReturnRejectionReason() != null ? ": " + item.getGlpiReturnRejectionReason() : "";
                badgeText = "✗ Rechazado" + reason;
                badgeColor = "#ef4444";
            }
            case PENDING -> {
                badgeText = "⏳ Pendiente sincronización";
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
        statusRow.getChildren().addAll(DetailCardLabels.prefixLabel("GLPI ret.: "), DetailCardLabels.statusBadge(badgeText, badgeColor));
        box.getChildren().add(statusRow);

        if (showActions && adminMode
                && AdminSession.getInstance().hasPermission(Permission.SYNC_GLPI, noteSedeIdOrNull())) {
            Button btnSync = new Button("Sincronizar");
            btnSync.getStyleClass().add("button-primary");
            btnSync.setStyle(btnSync.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
            btnSync.setOnAction(e -> handleGlpiReturnSync(item, btnSync));

            Button btnReject = new Button("Rechazar");
            btnReject.getStyleClass().add("button-secondary");
            btnReject.setStyle(btnReject.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
            btnReject.setOnAction(e -> handleGlpiReturnReject(item));

            box.getChildren().add(new HBox(8, btnSync, btnReject));
        }

        return box;
    }

    private void handleGlpiReturnSync(NoteReportItem item, Button btnSync) {
        AdminSession.getInstance().refreshActivity();
        btnSync.setDisable(true);
        ServiceLocator.getInstance().getHistoryService()
            .updateItemGlpiReturnStatus(item.getId(), GlpiStatus.SYNCED, null);
        item.setGlpiReturnStatus(GlpiStatus.SYNCED);
        item.setGlpiReturnStatusUpdatedAt(java.time.LocalDateTime.now().toString());
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private void handleGlpiReturnReject(NoteReportItem item) {
        AdminSession.getInstance().refreshActivity();
        String reason = promptRejectionReason();
        if (reason == null) return;
        ServiceLocator.getInstance().getHistoryService()
            .updateItemGlpiReturnStatus(item.getId(), GlpiStatus.REJECTED, reason);
        item.setGlpiReturnStatus(GlpiStatus.REJECTED);
        item.setGlpiReturnRejectionReason(reason);
        item.setGlpiReturnStatusUpdatedAt(java.time.LocalDateTime.now().toString());
        PendingCountsService.getInstance().notifyChanged();
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private String promptRejectionReason() {
        Stage stage = buildDialogStage();
        String[] result = {null};

        Label lblT = new Label("Motivo de rechazo");
        lblT.getStyleClass().add("section-label");

        Label lblSub = new Label("Descripción obligatoria del motivo de rechazo.");
        lblSub.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");

        TextArea ta = new TextArea();
        ta.setPromptText("Ej: Equipo no fue entregado / Datos incorrectos / Duplicado...");
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
        FXMLLoader loader = new FXMLLoader(NoteDetailController.class.getResource(
            "/com/bunshock/note_app_for_it_frontend/views/NoteDetailView.fxml"));
        Parent root = loader.load();
        NoteDetailController ctrl = loader.getController();
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
        scene.getStylesheets().add(NoteDetailController.class.getResource(
            "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());

        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setScene(scene);
        stage.setOpacity(0);
        stage.setOnShown(e -> {
            // sidebar is 235px fixed — center within the content area to the right of it
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
