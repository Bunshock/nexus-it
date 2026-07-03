package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.services.IEmailService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

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
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

public class NotePreviewController {

    @FXML private VBox rootContainer;
    @FXML private WebView webPreview;

    @FXML private CheckBox chkPrint;
    @FXML private CheckBox chkEmail;
    @FXML private VBox vboxEmailField;
    @FXML private TextField txtEmailRecipient;
    @FXML private Label lblStatus;
    @FXML private Button btnGenerate;

    private String renderedHtml;
    private String profileType;
    private NoteReport noteReport;
    private List<AssetItem> assets;
    private List<CountableItem> countables;

    public void initialize() {
        chkPrint.selectedProperty().addListener((o, a, b) -> updateGenerateButton());
        chkEmail.selectedProperty().addListener((o, a, b) -> updateGenerateButton());

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

        IEmailService email = ServiceLocator.getInstance().getEmailService();
        if (!email.isConfigured()) {
            chkEmail.setDisable(true);
            chkEmail.setText("Enviar por correo (no configurado)");
        }
    }

    @FXML
    private void handleEmailToggle() {
        vboxEmailField.setVisible(chkEmail.isSelected());
        vboxEmailField.setManaged(chkEmail.isSelected());
        updateGenerateButton();
    }

    private void updateGenerateButton() {
        boolean atLeastOne = chkPrint.isSelected() || chkEmail.isSelected();
        boolean emailOk = !chkEmail.isSelected() || !txtEmailRecipient.getText().isBlank();
        btnGenerate.setDisable(!atLeastOne || !emailOk);
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

            if (chkEmail.isSelected()) {
                ServiceLocator.getInstance().getEmailService().sendNote(
                    txtEmailRecipient.getText().trim(),
                    "Nota IT — " + profileType,
                    "Adjunto encontrará la nota generada por el área de Soporte IT.",
                    tempHtml
                );
            }

            ServiceLocator.getInstance().getHistoryService().save(buildReportWithItems());
            tempHtml.delete();

            lblStatus.setStyle("-fx-text-fill: #22c55e;");
            lblStatus.setText("Nota generada correctamente");
            btnGenerate.setDisable(false);
        } catch (Exception e) {
            lblStatus.setStyle("-fx-text-fill: #ef4444;");
            lblStatus.setText("Error: " + e.getMessage());
            btnGenerate.setDisable(false);
        }
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

        List<NoteReportItem> items = new java.util.ArrayList<>();
        for (AssetItem a : assets) {
            NoteReportItem i = new NoteReportItem();
            i.setTypeName(a.getType().get());
            i.setBrandName(a.getBrand().get());
            i.setModelName(a.getModel().get());
            i.setSerialNumber(a.getSerial().get());
            i.setAf(a.getAf().get());
            i.setQuantity(1);
            i.setObservations(a.getObservations().get());
            i.setAsset(true);
            i.setGlpiStatus(GlpiStatus.PENDING);
            items.add(i);
        }
        for (CountableItem c : countables) {
            NoteReportItem i = new NoteReportItem();
            i.setTypeName(c.getType().get());
            i.setBrandName(c.getBrand().get());
            i.setModelName(c.getModel().get());
            i.setQuantity(c.getQuantity().get());
            i.setObservations(c.getObservations().get());
            i.setAsset(false);
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
