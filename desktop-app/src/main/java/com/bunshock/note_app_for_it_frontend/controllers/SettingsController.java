package com.bunshock.note_app_for_it_frontend.controllers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.bunshock.note_app_for_it_frontend.models.AppConfig;
import com.bunshock.note_app_for_it_frontend.models.SnValidationRow;
import com.bunshock.note_app_for_it_frontend.services.AdminAuthService;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.DatabaseService;
import com.bunshock.note_app_for_it_frontend.services.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.WindowsDPAPIService;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class SettingsController {

    // ── Settings panel fields ─────────────────────────────────────────
    @FXML private VBox panelSettings;
    @FXML private VBox panelSnValidation;

    @FXML private TextField txtAfPrefix;
    @FXML private TextField txtAfSeparator;
    @FXML private TextField txtAfLength;
    @FXML private TextField txtAfFiller;
    @FXML private Label     lblAfPreview;

    @FXML private TextField   txtSmtpSender;
    @FXML private PasswordField pfSmtpPassword;

    @FXML private TextField     txtGlpiUrl;
    @FXML private PasswordField pfGlpiApiKey;

    @FXML private Button btnSave;
    @FXML private Label  lblSaveStatus;

    // ── Admin mode fields ─────────────────────────────────────────────
    @FXML private Button btnToggleAdmin;
    @FXML private Label  lblAdminStatus;

    // ── S/N validation panel fields ───────────────────────────────────
    @FXML private TextField                              txtSnFilter;
    @FXML private TableView<SnValidationRow>             tblSnValidation;
    @FXML private TableColumn<SnValidationRow, String>   colSnType;
    @FXML private TableColumn<SnValidationRow, String>   colSnBrand;
    @FXML private TableColumn<SnValidationRow, String>   colSnModel;
    @FXML private TableColumn<SnValidationRow, String>   colSnRegex;
    @FXML private TableColumn<SnValidationRow, Boolean>  colSnActive;
    @FXML private TableColumn<SnValidationRow, Void>     colSnEdit;

    private IEquipmentService equipmentService;
    private ObservableList<SnValidationRow> allSnRows;
    private FilteredList<SnValidationRow>   filteredSnRows;

    public void initialize() {
        equipmentService = ServiceLocator.getInstance().getEquipmentService();

        AppConfig config = ConfigService.getInstance().getConfig();
        txtAfPrefix.setText(config.afFormat.prefix);
        txtAfSeparator.setText(config.afFormat.separator);
        txtAfLength.setText(String.valueOf(config.afFormat.length));
        txtAfFiller.setText(config.afFormat.filler);
        txtSmtpSender.setText(config.smtp.senderAddress);
        txtGlpiUrl.setText(config.glpiApi.baseUrl);

        txtAfPrefix.textProperty().addListener((o, a, b) -> updateAfPreview());
        txtAfSeparator.textProperty().addListener((o, a, b) -> updateAfPreview());
        txtAfLength.textProperty().addListener((o, a, b) -> updateAfPreview());
        txtAfFiller.textProperty().addListener((o, a, b) -> updateAfPreview());
        updateAfPreview();

        setupSnTable();

        AdminSession.getInstance().addOnActivateListener(this::onAdminStateChanged);
        AdminSession.getInstance().addOnDeactivateListener(this::onAdminStateChanged);
        onAdminStateChanged();
    }

    private void onAdminStateChanged() {
        updateAdminButton();
        updateFieldEditability();
    }

    private void updateFieldEditability() {
        boolean adminActive = AdminSession.getInstance().isActive();
        txtAfPrefix.setDisable(!adminActive);
        txtAfSeparator.setDisable(!adminActive);
        txtAfLength.setDisable(!adminActive);
        txtAfFiller.setDisable(!adminActive);
        txtSmtpSender.setDisable(!adminActive);
        pfSmtpPassword.setDisable(!adminActive);
        txtGlpiUrl.setDisable(!adminActive);
        pfGlpiApiKey.setDisable(!adminActive);
        btnSave.setDisable(!adminActive);
    }

    // ── A/F preview ───────────────────────────────────────────────────

    private void updateAfPreview() {
        try {
            String prefix = txtAfPrefix.getText();
            String sep    = txtAfSeparator.getText();
            int length    = Integer.parseInt(txtAfLength.getText().trim());
            String filler = txtAfFiller.getText().isEmpty() ? "0" : txtAfFiller.getText().substring(0, 1);
            String example = filler.repeat(Math.max(0, length - 3)) + "512";
            lblAfPreview.setText("Vista previa: " + prefix + sep
                + example.substring(Math.max(0, example.length() - length)));
        } catch (NumberFormatException e) {
            lblAfPreview.setText("Vista previa: —");
        }
    }

    // ── Save ──────────────────────────────────────────────────────────

    @FXML
    private void handleSave() {
        AppConfig config = ConfigService.getInstance().getConfig();

        config.afFormat.prefix    = txtAfPrefix.getText().trim();
        config.afFormat.separator = txtAfSeparator.getText();
        config.afFormat.filler    = txtAfFiller.getText().isEmpty() ? "0" : txtAfFiller.getText().substring(0, 1);
        config.smtp.senderAddress = txtSmtpSender.getText().trim();
        config.glpiApi.baseUrl    = txtGlpiUrl.getText().trim();

        try {
            config.afFormat.length = Integer.parseInt(txtAfLength.getText().trim());
        } catch (NumberFormatException e) {
            lblSaveStatus.setStyle("-fx-text-fill: #ef4444;");
            lblSaveStatus.setText("La longitud de A/F debe ser un número");
            return;
        }

        String smtpPassword = pfSmtpPassword.getText();
        if (!smtpPassword.isBlank()) {
            saveEncryptedSetting("smtp_password", smtpPassword);
            pfSmtpPassword.clear();
        }

        String glpiApiKey = pfGlpiApiKey.getText();
        if (!glpiApiKey.isBlank()) {
            saveEncryptedSetting("glpi_api_key", glpiApiKey);
            pfGlpiApiKey.clear();
        }

        try {
            ConfigService.getInstance().save();
            lblSaveStatus.setStyle("-fx-text-fill: #0c8570;");
            lblSaveStatus.setText("Configuración guardada");
        } catch (Exception e) {
            lblSaveStatus.setStyle("-fx-text-fill: #ef4444;");
            lblSaveStatus.setText("Error al guardar la configuración");
        }
    }

    private void saveEncryptedSetting(String key, String plainValue) {
        String encrypted = WindowsDPAPIService.getInstance().encrypt(plainValue);
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO APP_SETTINGS (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, encrypted);
            ps.executeUpdate();
        } catch (Exception e) {
            lblSaveStatus.setStyle("-fx-text-fill: #ef4444;");
            lblSaveStatus.setText("Error al guardar la configuración");
        }
    }

    // ── Admin mode toggle ─────────────────────────────────────────────

    @FXML
    private void handleToggleAdmin() {
        if (AdminSession.getInstance().isActive()) {
            AdminSession.getInstance().deactivate();
            showErrorDialog("Modo administrador", "El modo administrador fue desactivado.");
            return;
        }
        if (!AdminAuthService.isConfigured()) {
            showErrorDialog("Administrador no configurado",
                "El hash de contraseña de administrador no está configurado en el sistema.");
            return;
        }
        Optional<String> entered = promptPassword();
        if (entered.isEmpty()) return;
        if (!AdminAuthService.verify(entered.get())) {
            showErrorDialog("Acceso denegado", "Contraseña incorrecta.");
            return;
        }
        AdminSession.getInstance().activate();
    }

    private void updateAdminButton() {
        boolean active = AdminSession.getInstance().isActive();
        if (active) {
            btnToggleAdmin.setText("Desactivar modo administrador");
            btnToggleAdmin.getStyleClass().removeAll("button-secondary");
            if (!btnToggleAdmin.getStyleClass().contains("button-primary"))
                btnToggleAdmin.getStyleClass().add("button-primary");
            lblAdminStatus.setText("Activo");
            lblAdminStatus.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #0c8570;");
        } else {
            btnToggleAdmin.setText("Activar modo administrador");
            btnToggleAdmin.getStyleClass().removeAll("button-primary");
            if (!btnToggleAdmin.getStyleClass().contains("button-secondary"))
                btnToggleAdmin.getStyleClass().add("button-secondary");
            lblAdminStatus.setText("Inactivo");
            lblAdminStatus.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;");
        }
    }

    // ── S/N validation panel navigation ───────────────────────────────

    @FXML
    private void onOpenSnValidation() {
        loadSnValidationData();
        panelSettings.setVisible(false);
        panelSettings.setManaged(false);
        panelSnValidation.setVisible(true);
        panelSnValidation.setManaged(true);
    }

    @FXML
    private void onBackFromSnValidation() {
        panelSnValidation.setVisible(false);
        panelSnValidation.setManaged(false);
        panelSettings.setVisible(true);
        panelSettings.setManaged(true);
    }

    // ── S/N validation table setup ────────────────────────────────────

    private void setupSnTable() {
        colSnType.setCellValueFactory(d -> d.getValue().typeNameProperty());
        colSnBrand.setCellValueFactory(d -> d.getValue().brandNameProperty());
        colSnModel.setCellValueFactory(d -> d.getValue().modelNameProperty());
        colSnRegex.setCellValueFactory(d -> d.getValue().regexProperty());

        colSnActive.setCellValueFactory(d -> d.getValue().activeProperty().asObject());
        colSnActive.setCellFactory(col -> new TableCell<>() {
            {
                setAlignment(Pos.CENTER);
                tableRowProperty().addListener((obs, oldRow, newRow) -> {
                    if (newRow != null) {
                        newRow.selectedProperty().addListener((o, was, now) -> {
                            Boolean val = getItem();
                            if (val != null) refreshStyle(val, now);
                        });
                    }
                });
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); setStyle(""); return; }
                setText(item ? "✓" : "✗");
                boolean sel = getTableRow() != null && getTableRow().isSelected();
                refreshStyle(item, sel);
            }

            private void refreshStyle(boolean active, boolean selected) {
                if (active) {
                    setStyle(selected
                        ? "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: white;"
                        : "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #22c55e;");
                } else {
                    setStyle(selected
                        ? "-fx-font-size: 13px; -fx-text-fill: white;"
                        : "-fx-font-size: 13px; -fx-text-fill: #cbd5e1;");
                }
            }
        });

        colSnEdit.setCellFactory(col -> new TableCell<>() {
            private final Button btn = new Button("✏");
            {
                btn.getStyleClass().add("button-icon-edit");
                btn.setOnAction(e -> {
                    SnValidationRow row = getTableView().getItems().get(getIndex());
                    if (row != null) handleEditRow(row);
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setAlignment(Pos.CENTER);
                setGraphic(empty ? null : btn);
            }
        });

        txtSnFilter.textProperty().addListener((obs, old, val) -> {
            if (filteredSnRows == null) return;
            String lower = val == null ? "" : val.toLowerCase();
            filteredSnRows.setPredicate(r ->
                lower.isEmpty()
                || r.getTypeName().toLowerCase().contains(lower)
                || r.getBrandName().toLowerCase().contains(lower)
                || r.getModelName().toLowerCase().contains(lower)
                || r.getRegex().toLowerCase().contains(lower));
        });
    }

    private void loadSnValidationData() {
        List<SnValidationRow> rows = equipmentService.getAllSnValidationRows();
        allSnRows = FXCollections.observableArrayList(rows);
        filteredSnRows = new FilteredList<>(allSnRows, r -> true);

        String current = txtSnFilter.getText();
        if (current != null && !current.isBlank()) {
            String lower = current.toLowerCase();
            filteredSnRows.setPredicate(r ->
                r.getTypeName().toLowerCase().contains(lower)
                || r.getBrandName().toLowerCase().contains(lower)
                || r.getModelName().toLowerCase().contains(lower)
                || r.getRegex().toLowerCase().contains(lower));
        }

        Comparator<SnValidationRow> order = Comparator
            .<SnValidationRow, Boolean>comparing(r -> !r.isActive())
            .thenComparing(SnValidationRow::getTypeName)
            .thenComparing(SnValidationRow::getBrandName)
            .thenComparing(SnValidationRow::getModelName);

        SortedList<SnValidationRow> sorted = new SortedList<>(filteredSnRows, order);
        tblSnValidation.setItems(sorted);
    }

    // ── Admin auth ────────────────────────────────────────────────────

    private Optional<String> promptPassword() {
        Stage stage = buildDialogStage();
        centerOnContent(stage);
        String[] result = {null};

        Label title = new Label("Acceso de administrador");
        title.getStyleClass().add("section-label");

        Label subtitle = new Label("Ingrese la contraseña para editar esta configuración.");
        subtitle.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");
        subtitle.setWrapText(true);

        PasswordField pf = new PasswordField();
        pf.setPromptText("Contraseña");
        pf.getStyleClass().add("form-input-main");

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnOk = new Button("Confirmar");
        btnOk.getStyleClass().add("button-primary");
        btnOk.setOnAction(e -> { result[0] = pf.getText(); stage.close(); });
        pf.setOnAction(e -> btnOk.fire());

        HBox buttons = new HBox(8, btnCancel, btnOk);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380);
        root.getChildren().addAll(title, subtitle, pf, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        javafx.application.Platform.runLater(pf::requestFocus);
        stage.showAndWait();

        return Optional.ofNullable(result[0]);
    }

    // ── Edit row ──────────────────────────────────────────────────────

    private void handleEditRow(SnValidationRow row) {
        if (!AdminSession.getInstance().isActive()) {
            showErrorDialog("Acceso restringido",
                "Activa el modo administrador desde Configuración para editar la validación S/N.");
            return;
        }
        AdminSession.getInstance().refreshActivity();
        openEditDialog(row);
    }

    private void openEditDialog(SnValidationRow row) {
        Stage stage = buildDialogStage();
        centerOnContent(stage);
        boolean[] saved = {false};

        Label path = new Label(row.getTypeName() + " › " + row.getBrandName() + " › " + row.getModelName());
        path.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;");
        path.setWrapText(true);

        Label lblRegex = new Label("EXPRESIÓN REGULAR (REGEX)");
        lblRegex.getStyleClass().add("input-label-small");

        TextField tfRegex = new TextField(row.getRegex());
        tfRegex.setPromptText("Ej: [A-Z]{2}\\d{6}");
        tfRegex.getStyleClass().add("form-input-main");

        CheckBox chkActive = new CheckBox("Validación activa");
        chkActive.setSelected(row.isActive());
        chkActive.setStyle("-fx-font-size: 12px;");

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Guardar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            String newRegex = tfRegex.getText().trim();
            equipmentService.upsertSnValidation(row.getModelId(),
                newRegex.isEmpty() ? null : newRegex, chkActive.isSelected());
            saved[0] = true;
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(420);
        root.getChildren().addAll(path, new Separator(), lblRegex, tfRegex, chkActive, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        javafx.application.Platform.runLater(tfRegex::requestFocus);
        stage.showAndWait();

        if (saved[0]) loadSnValidationData();
    }

    // ── Dialog helpers ────────────────────────────────────────────────

    private void showErrorDialog(String title, String message) {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label(title);
        lblTitle.getStyleClass().add("section-label");

        Label lblMsg = new Label(message);
        lblMsg.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");
        lblMsg.setWrapText(true);

        Button btnOk = new Button("Aceptar");
        btnOk.getStyleClass().add("button-primary");
        btnOk.setOnAction(e -> stage.close());

        HBox buttons = new HBox(btnOk);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(360);
        root.getChildren().addAll(lblTitle, lblMsg, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();
    }

    private void centerOnContent(Stage stage) {
        stage.setOpacity(0);
        stage.setOnShown(e -> {
            VBox panel = panelSettings.isVisible() ? panelSettings : panelSnValidation;
            javafx.geometry.Bounds b = panel.localToScreen(panel.getBoundsInLocal());
            if (b != null) {
                stage.setX(b.getMinX() + (b.getWidth()  - stage.getWidth())  / 2);
                stage.setY(b.getMinY() + (b.getHeight() - stage.getHeight()) / 2);
            }
            stage.setOpacity(1);
        });
    }

    private Stage buildDialogStage() {
        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.initModality(Modality.APPLICATION_MODAL);
        return stage;
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
