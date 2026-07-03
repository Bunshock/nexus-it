package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;

import com.bunshock.note_app_for_it_frontend.models.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.NoteGenerationService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.input.KeyCode;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
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
    @FXML private VBox    vboxItems;

    private NoteReport report;
    private boolean adminMode;
    private Runnable onUpdate;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final NoteGenerationService GEN_SVC = new NoteGenerationService();

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
        lblTitle.setText(report.getProfileType() + " — " + date);

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

        if (!item.isAsset()) {
            card.getChildren().add(statusBadge("— Sin acción GLPI", "#94a3b8"));
            return card;
        }

        card.getChildren().add(buildGlpiStatusRow(item));
        return card;
    }

    private HBox buildGlpiStatusRow(NoteReportItem item) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-padding: 4 0 0 0;");

        GlpiStatus status = item.getGlpiStatus();

        switch (status) {
            case SYNCED -> {
                String ts = item.getGlpiStatusUpdatedAt() != null ? " · " + item.getGlpiStatusUpdatedAt().substring(0, 16) : "";
                row.getChildren().add(statusBadge("✓ Sincronizado" + ts, "#22c55e"));
            }
            case REJECTED -> {
                String reason = item.getGlpiRejectionReason() != null ? ": " + item.getGlpiRejectionReason() : "";
                row.getChildren().add(statusBadge("✗ Rechazado" + reason, "#ef4444"));
            }
            case PENDING -> {
                row.getChildren().add(statusBadge("⏳ Pendiente", "#f97316"));
                if (adminMode && AdminSession.getInstance().isActive()) {
                    Button btnSync = new Button("Sincronizar");
                    btnSync.getStyleClass().add("button-primary");
                    btnSync.setStyle(btnSync.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
                    btnSync.setOnAction(e -> handleSync(item, btnSync));

                    Button btnReject = new Button("Rechazar");
                    btnReject.getStyleClass().add("button-secondary");
                    btnReject.setStyle(btnReject.getStyle() + "-fx-font-size: 10px; -fx-padding: 3 8;");
                    btnReject.setOnAction(e -> handleReject(item));

                    row.getChildren().addAll(btnSync, btnReject);
                }
            }
            default -> row.getChildren().add(statusBadge("—", "#94a3b8"));
        }

        return row;
    }

    private void handleSync(NoteReportItem item, Button btnSync) {
        AdminSession.getInstance().refreshActivity();
        btnSync.setDisable(true);
        ServiceLocator.getInstance().getHistoryService()
            .updateItemGlpiStatus(item.getId(), GlpiStatus.SYNCED, null);
        item.setGlpiStatus(GlpiStatus.SYNCED);
        buildItemCards();
        if (onUpdate != null) onUpdate.run();
    }

    private void handleReject(NoteReportItem item) {
        AdminSession.getInstance().refreshActivity();
        String reason = promptRejectionReason();
        if (reason == null) return;
        ServiceLocator.getInstance().getHistoryService()
            .updateItemGlpiStatus(item.getId(), GlpiStatus.REJECTED, reason);
        item.setGlpiStatus(GlpiStatus.REJECTED);
        item.setGlpiRejectionReason(reason);
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
