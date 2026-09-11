package com.bunshock.note_app_for_it_frontend.controllers.note;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.catalog.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.catalog.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.history.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.services.history.PendingCountsService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.shape.Rectangle;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

public class NotePreviewController {

    @FXML private VBox rootContainer;
    @FXML private WebView webPreview;

    @FXML private CheckBox chkPrint;
    @FXML private Label lblStatus;
    @FXML private Button btnGenerate;

    private String renderedHtml;
    private String profileType;
    private NoteReport noteReport;
    private List<AssetItem> assets;
    private List<CountableItem> countables;
    private Runnable onSuccess;

    public void setOnSuccess(Runnable onSuccess) {
        this.onSuccess = onSuccess;
    }

    public void initialize() {
        chkPrint.selectedProperty().addListener((o, a, b) -> updateGenerateButton());

        chkPrint.setSelected(true);
        updateGenerateButton();

        Rectangle clip = new Rectangle();
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        clip.widthProperty().bind(rootContainer.widthProperty());
        clip.heightProperty().bind(rootContainer.heightProperty());
        rootContainer.setClip(clip);
    }

    public void loadPreview(String html, String profileType, NoteReport report,
                             List<AssetItem> assets, List<CountableItem> countables) {
        this.renderedHtml = html;
        this.profileType = profileType;
        this.noteReport = report;
        this.assets = assets;
        this.countables = countables;

        webPreview.getEngine().loadContent(html);
    }

    private void updateGenerateButton() {
        btnGenerate.setDisable(!chkPrint.isSelected());
    }

    @FXML
    private void handleGenerate() {
        btnGenerate.setDisable(true);
        lblStatus.setStyle("-fx-text-fill: #64748b;");
        lblStatus.setText("Procesando...");

        try {
            File tempHtml = writeTempHtml();

            if (chkPrint.isSelected() && !printNote()) {
                tempHtml.delete();
                lblStatus.setStyle("-fx-text-fill: #64748b;");
                lblStatus.setText("Impresión cancelada — nota no generada");
                btnGenerate.setDisable(false);
                return;
            }

            ServiceLocator.getInstance().getHistoryService().save(buildReportWithItems());
            PendingCountsService.getInstance().notifyChanged();
            tempHtml.delete();

            if (onSuccess != null) onSuccess.run();
            fadeOutAndClose((Stage) rootContainer.getScene().getWindow());
        } catch (Exception e) {
            lblStatus.setStyle("-fx-text-fill: #ef4444;");
            lblStatus.setText("Error: " + e.getMessage());
            btnGenerate.setDisable(false);
        }
    }

    private static final Duration CLOSE_FADE = Duration.millis(200);

    private void fadeOutAndClose(Stage stage) {
        Timeline fade = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(stage.opacityProperty(), 1.0)),
            new KeyFrame(CLOSE_FADE, new KeyValue(stage.opacityProperty(), 0.0))
        );
        fade.setOnFinished(e -> stage.close());
        fade.play();
    }

    private boolean printNote() {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) return true;
        Stage stage = (Stage) rootContainer.getScene().getWindow();
        if (!job.showPrintDialog(stage)) return false;
        PageLayout layout = job.getPrinter().createPageLayout(
            Paper.A4, PageOrientation.PORTRAIT, Printer.MarginType.HARDWARE_MINIMUM);
        job.getJobSettings().setPageLayout(layout);
        webPreview.getEngine().print(job);
        job.endJob();
        return true;
    }

    private File writeTempHtml() throws IOException {
        File tmp = Files.createTempFile("nota_it_", ".html").toFile();
        try (PrintWriter pw = new PrintWriter(tmp, StandardCharsets.UTF_8)) {
            pw.print(renderedHtml);
        }
        return tmp;
    }

    private NoteReport buildReportWithItems() {
        NoteReport r = noteReport;
        r.setCreatedAt(LocalDateTime.now());
        r.setProfileType(profileType);

        // Préstamo assets are deliberately excluded from GLPI sync (N_A, not PENDING) — GLPI
        // sync in this app is a one-way, manual, no-revert action (see GlpiStatus/
        // buildGlpiStatusRow()), and nothing in the Préstamo return flow ever un-syncs an item,
        // so syncing a temporary loan would leave GLPI permanently believing the asset is still
        // assigned to the borrower after it's returned. Préstamo already has its own dedicated
        // return-tracking dimension (ReturnStatus) — see CLAUDE.md's "Préstamos section". A
        // Remito's assets are excluded for the same reason — they're moving between Sedes, not
        // being assigned to a person, and this app has no way to update GLPI's location for them.
        boolean isPrestamo = "PRÉSTAMO".equals(profileType);
        boolean isRemito = "REMITO DE ENVÍO".equals(profileType);

        List<NoteReportItem> items = new java.util.ArrayList<>();
        for (AssetItem a : assets) {
            NoteReportItem i = new NoteReportItem();
            i.setTypeName(a.getType().get());
            i.setBrandName(a.getBrand().get());
            i.setModelName(a.getModel().get());
            i.setTypeId(a.getTypeId());
            i.setBrandId(a.getBrandId());
            i.setModelId(a.getModelId());
            i.setSerialNumber(a.getSerial().get());
            i.setAf(a.getAf().get());
            i.setQuantity(1);
            i.setObservations(a.getObservations().get());
            i.setAsset(true);
            i.setModifiesStock(a.isModifiesStock());
            i.setModifiesStockReason(a.getModifiesStockReason());
            i.setGlpiStatus((isPrestamo || isRemito) ? GlpiStatus.N_A : GlpiStatus.PENDING);
            items.add(i);
        }
        for (CountableItem c : countables) {
            NoteReportItem i = new NoteReportItem();
            i.setTypeName(c.getType().get());
            i.setBrandName(c.getBrand().get());
            i.setModelName(c.getModel().get());
            i.setTypeId(c.getTypeId());
            i.setBrandId(c.getBrandId());
            i.setModelId(c.getModelId());
            i.setQuantity(c.getQuantity().get());
            i.setObservations(c.getObservations().get());
            i.setAsset(false);
            i.setModifiesStock(c.isModifiesStock());
            i.setModifiesStockReason(c.getModifiesStockReason());
            i.setGlpiStatus(GlpiStatus.N_A);
            items.add(i);
        }
        r.setItems(items);
        return r;
    }

    @FXML
    private void handleCancel() {
        ((Stage) rootContainer.getScene().getWindow()).close();
    }
}
