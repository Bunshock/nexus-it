package com.bunshock.note_app_for_it_frontend.controllers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.bunshock.note_app_for_it_frontend.models.AppConfig;
import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentProvider;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.Sede;
import com.bunshock.note_app_for_it_frontend.services.AdminAuthService;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.AppKeyEncryptionService;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.DatabaseService;
import com.bunshock.note_app_for_it_frontend.services.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.RemoteDatabaseService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class DatabaseSectionController {

    @FXML private VBox rootContainer;
    @FXML private Label lblDbServer;
    @FXML private Label lblDbName;
    @FXML private Label lblConnectionStatus;
    @FXML private Label lblLocalConnectionStatus;
    @FXML private ListView<EquipmentType>  listTypes;
    @FXML private ListView<EquipmentBrand> listBrands;
    @FXML private ListView<EquipmentModel> listModels;
    @FXML private ListView<EquipmentProvider> listProviders;
    @FXML private ListView<Sede> listSedes;

    // Toggle between the cascading Types/Brands/Models row and the flat/independent
    // Providers/Sedes row — added 2026-07-24 once a 5th catalog list (Sedes) made a single
    // shared row too cramped.
    @FXML private ToggleGroup catalogGroup;
    @FXML private ToggleButton btnEquipmentCatalog;
    @FXML private ToggleButton btnOtherCatalogs;
    @FXML private HBox rowEquipmentCatalog;
    @FXML private HBox rowOtherCatalogs;

    private IEquipmentService equipmentService;

    public void initialize() {
        equipmentService = ServiceLocator.getInstance().getEquipmentService();
        loadConnectionDisplay();
        refreshTypes();
        refreshSedes();

        catalogGroup.selectedToggleProperty().addListener((obs, old, next) -> {
            boolean showEquipment = next == btnEquipmentCatalog;
            rowEquipmentCatalog.setVisible(showEquipment);
            rowEquipmentCatalog.setManaged(showEquipment);
            rowOtherCatalogs.setVisible(!showEquipment);
            rowOtherCatalogs.setManaged(!showEquipment);
        });
        refreshProviders();
        applyGenericCellStyle(listBrands);
        applyGenericCellStyle(listModels);

        listTypes.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) refreshBrandsForType(sel.getId());
            else { listBrands.setItems(FXCollections.observableArrayList()); listModels.setItems(FXCollections.observableArrayList()); }
        });

        listBrands.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            EquipmentType type = listTypes.getSelectionModel().getSelectedItem();
            if (sel != null && type != null) refreshModelsForBrandType(sel.getId(), type.getId());
            else listModels.setItems(FXCollections.observableArrayList());
        });
    }

    // ── Connection display ───────────────────────────────────────────

    private void loadConnectionDisplay() {
        String host = getSetting("db_host");
        String port = getSetting("db_port");
        String name = getSetting("db_name");
        if (host == null || host.isBlank()) {
            lblDbServer.setText("No configurado");
            lblDbServer.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px;");
            lblDbName.setText("—");
            lblDbName.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px;");
        } else {
            lblDbServer.setText(host + ":" + (port != null && !port.isBlank() ? port : "1433"));
            lblDbServer.setStyle("-fx-text-fill: #334155; -fx-font-size: 13px;");
            lblDbName.setText(name != null && !name.isBlank() ? name : "—");
            lblDbName.setStyle("-fx-text-fill: #334155; -fx-font-size: 13px;");
        }
        lblConnectionStatus.setText("Sin verificar");
        lblConnectionStatus.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px;");
        lblLocalConnectionStatus.setText("Sin verificar");
        lblLocalConnectionStatus.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px;");
    }

    @FXML
    private void handleEditConnection() {
        requireAdmin(this::openEditConnectionDialog);
    }

    // Tests and reports both databases independently every time — previously this only ever
    // tested one or the other (remote if configured, local otherwise), so clicking "Probar
    // conexión" with no remote database configured silently reported the *local* database's
    // status as if that were the answer, with no indication that the remote side was never
    // configured in the first place.
    @FXML
    private void handleTestConnection() {
        RemoteDatabaseService remote = RemoteDatabaseService.getInstance();
        if (!remote.isConfigured()) {
            lblConnectionStatus.setStyle("-fx-text-fill: #f59e0b; -fx-font-size: 13px;");
            lblConnectionStatus.setText("⚠ Base de datos remota no configurada");
        } else if (remote.testConnection()) {
            lblConnectionStatus.setStyle("-fx-text-fill: #22c55e; -fx-font-size: 13px;");
            lblConnectionStatus.setText("✓ Conexión remota activa");
        } else {
            lblConnectionStatus.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 13px;");
            lblConnectionStatus.setText("✗ Servidor remoto configurado no disponible");
        }

        try (Connection c = DatabaseService.getInstance().getConnection()) {
            c.createStatement().execute("SELECT 1");
            lblLocalConnectionStatus.setStyle("-fx-text-fill: #22c55e; -fx-font-size: 13px;");
            lblLocalConnectionStatus.setText("✓ Base de datos local activa");
        } catch (Exception e) {
            lblLocalConnectionStatus.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 13px;");
            lblLocalConnectionStatus.setText("✗ Error: " + e.getMessage());
        }
    }

    private void openEditConnectionDialog() {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        String curHost = getSetting("db_host");
        String curPort = getSetting("db_port");
        String curName = getSetting("db_name");
        String curUser = decryptSetting("db_username");
        String curPass = decryptSetting("db_password");

        Label lblTitle = new Label("Conexión a base de datos");
        lblTitle.getStyleClass().add("section-label");

        Label lblH = new Label("SERVIDOR (HOST)"); lblH.getStyleClass().add("input-label-small");
        TextField tfHost = new TextField(curHost != null ? curHost : "");
        tfHost.setPromptText("Ej: 192.168.1.100"); tfHost.getStyleClass().add("form-input-main");

        Label lblP = new Label("PUERTO"); lblP.getStyleClass().add("input-label-small");
        TextField tfPort = new TextField(curPort != null && !curPort.isBlank() ? curPort : "1433");
        tfPort.setPrefWidth(80); tfPort.getStyleClass().add("form-input-main");

        VBox hostBox = new VBox(2, lblH, tfHost); HBox.setHgrow(hostBox, Priority.ALWAYS);
        VBox portBox = new VBox(2, lblP, tfPort);
        HBox hostPort = new HBox(8, hostBox, portBox);

        Label lblN = new Label("BASE DE DATOS"); lblN.getStyleClass().add("input-label-small");
        TextField tfName = new TextField(curName != null ? curName : "");
        tfName.setPromptText("Ej: noteapp_db"); tfName.getStyleClass().add("form-input-main");

        Label lblU = new Label("USUARIO"); lblU.getStyleClass().add("input-label-small");
        TextField tfUser = new TextField(curUser != null ? curUser : "");
        tfUser.setPromptText("Ej: admin"); tfUser.getStyleClass().add("form-input-main");

        Label lblPw = new Label("CONTRASEÑA"); lblPw.getStyleClass().add("input-label-small");
        PasswordField pfPass = new PasswordField();
        pfPass.setText(curPass != null ? curPass : "");
        pfPass.getStyleClass().add("form-input-main");

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Guardar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            String host   = tfHost.getText().trim();
            String portStr = tfPort.getText().trim().isEmpty() ? "1433" : tfPort.getText().trim();
            String name   = tfName.getText().trim();
            String user   = tfUser.getText().trim();
            String pass   = pfPass.getText();

            Runnable persistAndClose = () -> {
                saveSetting("db_host", host);
                saveSetting("db_port", portStr);
                saveSetting("db_name", name);
                saveEncryptedSetting("db_username", user);
                saveEncryptedSetting("db_password", pass);
                int configuredPort;
                try { configuredPort = Integer.parseInt(portStr); }
                catch (NumberFormatException nfe) { configuredPort = 1433; }
                RemoteDatabaseService.getInstance().configure(host, configuredPort, name, user, pass);
                loadConnectionDisplay();
                stage.close();
            };

            if (host.isEmpty()) {
                persistAndClose.run();
                return;
            }

            int port;
            try { port = Integer.parseInt(portStr); }
            catch (NumberFormatException nfe) { port = -1; }

            btnSave.setDisable(true);
            btnCancel.setDisable(true);
            btnSave.setText("Probando...");

            int testPort = port;
            Thread t = new Thread(() -> {
                boolean ok = testPort > 0 && RemoteDatabaseService.getInstance()
                    .testConnection(host, testPort, name, user, pass);
                Platform.runLater(() -> {
                    btnSave.setDisable(false);
                    btnCancel.setDisable(false);
                    btnSave.setText("Guardar");
                    if (ok || confirmSaveDespiteFailedTest()) persistAndClose.run();
                });
            }, "db-connection-test");
            t.setDaemon(true);
            t.start();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(440);
        root.getChildren().addAll(lblTitle, hostPort,
            new VBox(2, lblN, tfName),
            new VBox(2, lblU, tfUser),
            new VBox(2, lblPw, pfPass),
            new Separator(), buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        Platform.runLater(tfHost::requestFocus);
        stage.showAndWait();
    }

    // ── Equipment catalog ────────────────────────────────────────────

    private void refreshTypes() {
        listTypes.setItems(FXCollections.observableArrayList(equipmentService.getAllTypes()));
        listBrands.setItems(FXCollections.observableArrayList());
        listModels.setItems(FXCollections.observableArrayList());
    }

    // The global generic brand isn't linked to every type (it doesn't need to be — see
    // SqliteEquipmentService.addBrandForType()), so it's synthesized into the list here exactly
    // like ItemDialogController.onTypeSelected() already does for the note-generation Item
    // dialog — otherwise it would only appear for whichever type(s) happen to have a real,
    // now-vestigial BRAND_TYPE_LINK, and be missing everywhere else.
    private void refreshBrandsForType(int typeId) {
        List<EquipmentBrand> brands = new ArrayList<>(equipmentService.getBrandsForType(typeId));
        if (brands.stream().noneMatch(b -> genericLabel().equals(b.getName()))) {
            equipmentService.getAllBrands().stream()
                .filter(b -> genericLabel().equals(b.getName()))
                .findFirst()
                .ifPresent(brands::add);
        }
        listBrands.setItems(FXCollections.observableArrayList(brands));
        listModels.setItems(FXCollections.observableArrayList());
    }

    private static final String DEFAULT_GENERIC_LABEL = "Genérico / Otro";

    // Duplicated from SqliteEquipmentService's/ItemDialogController's identical helper per this
    // codebase's no-shared-abstraction convention.
    private String genericLabel() {
        try {
            AppConfig.CatalogConfig catalog = ConfigService.getInstance().getConfig().catalog;
            if (catalog != null && catalog.genericLabel != null && !catalog.genericLabel.isBlank()) {
                return catalog.genericLabel.trim();
            }
        } catch (IllegalStateException notLoaded) {
            // ConfigService not loaded in this context (e.g. some test setups) — use the default
        }
        return DEFAULT_GENERIC_LABEL;
    }

    // The global generic model comes back from getModelsForBrandAndType() sorted alphabetically
    // among the real models (its own SQL just orders everything by name) — move it to the end
    // here to match the Brand list's own bottom placement above, and the same "generic sits last"
    // convention ItemDialogController's combo boxes already use.
    private void refreshModelsForBrandType(int brandId, int typeId) {
        List<EquipmentModel> models = new ArrayList<>(
            equipmentService.getModelsForBrandAndType(brandId, typeId));
        EquipmentModel generic = models.stream()
            .filter(m -> genericLabel().equals(m.getName()))
            .findFirst().orElse(null);
        if (generic != null) {
            models.remove(generic);
            models.add(generic);
        }
        listModels.setItems(FXCollections.observableArrayList(models));
    }

    private static final String GENERIC_STYLE = "-fx-font-style: italic; -fx-text-fill: #94a3b8;";

    // Same italic/grey treatment ItemDialogController's combo boxes already give the generic
    // fallback — applied here to listBrands/listModels so it reads consistently as "not a real
    // catalog entry" in Base de Datos too.
    private <T> void applyGenericCellStyle(ListView<T> listView) {
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item.toString());
                    setStyle(genericLabel().equals(item.toString()) ? GENERIC_STYLE : "");
                }
            }
        });
    }

    private void refreshProviders() {
        listProviders.setItems(FXCollections.observableArrayList(equipmentService.getAllProviders()));
    }

    private void refreshSedes() {
        listSedes.setItems(FXCollections.observableArrayList(equipmentService.getAllSedes()));
    }

    @FXML private void handleAddType()    { requireAdmin(this::openAddTypeDialog); }
    @FXML private void handleAddBrand()   { requireAdmin(this::openAddBrandDialog); }
    @FXML private void handleAddModel()   { requireAdmin(this::openAddModelDialog); }
    @FXML private void handleAddProvider(){ requireAdmin(this::openAddProviderDialog); }
    @FXML private void handleAddSede()    { requireAdmin(this::openAddSedeDialog); }

    @FXML
    private void handleEditType() {
        EquipmentType sel = listTypes.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requireAdmin(() -> openEditTypeDialog(sel));
    }

    @FXML
    private void handleEditBrand() {
        EquipmentBrand sel = listBrands.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requireAdmin(() -> openRenameDialog(sel.getName(), newName -> {
            equipmentService.renameBrand(sel.getId(), newName);
            EquipmentType type = listTypes.getSelectionModel().getSelectedItem();
            if (type != null) refreshBrandsForType(type.getId());
        }));
    }

    @FXML
    private void handleEditModel() {
        EquipmentModel sel = listModels.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requireAdmin(() -> openRenameDialog(sel.getName(), newName -> {
            equipmentService.renameModel(sel.getId(), newName);
            EquipmentType  type  = listTypes.getSelectionModel().getSelectedItem();
            EquipmentBrand brand = listBrands.getSelectionModel().getSelectedItem();
            if (type != null && brand != null) refreshModelsForBrandType(brand.getId(), type.getId());
        }));
    }

    @FXML
    private void handleEditProvider() {
        EquipmentProvider sel = listProviders.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requireAdmin(() -> openRenameDialog(sel.getName(), newName -> {
            equipmentService.renameProvider(sel.getId(), newName);
            refreshProviders();
        }));
    }

    @FXML
    private void handleEditSede() {
        Sede sel = listSedes.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requireAdmin(() -> openRenameDialog(sel.getName(), newName -> {
            equipmentService.renameSede(sel.getId(), newName);
            refreshSedes();
        }));
    }

    @FXML
    private void handleRemoveType() {
        EquipmentType sel = listTypes.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requireAdmin(() -> {
            if (!confirmDelete(sel.getName())) return;
            try { equipmentService.removeType(sel.getId()); refreshTypes(); }
            catch (Exception e) { showErrorDialog("Error al eliminar", e.getMessage()); }
        });
    }

    @FXML
    private void handleRemoveBrand() {
        EquipmentBrand sel = listBrands.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requireAdmin(() -> {
            if (!confirmDelete(sel.getName())) return;
            try {
                equipmentService.removeBrand(sel.getId());
                EquipmentType type = listTypes.getSelectionModel().getSelectedItem();
                if (type != null) refreshBrandsForType(type.getId());
            } catch (Exception e) { showErrorDialog("Error al eliminar", e.getMessage()); }
        });
    }

    @FXML
    private void handleRemoveModel() {
        EquipmentModel sel = listModels.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requireAdmin(() -> {
            if (!confirmDelete(sel.getName())) return;
            try {
                equipmentService.removeModel(sel.getId());
                EquipmentType  type  = listTypes.getSelectionModel().getSelectedItem();
                EquipmentBrand brand = listBrands.getSelectionModel().getSelectedItem();
                if (type != null && brand != null) refreshModelsForBrandType(brand.getId(), type.getId());
            } catch (Exception e) { showErrorDialog("Error al eliminar", e.getMessage()); }
        });
    }

    @FXML
    private void handleRemoveProvider() {
        EquipmentProvider sel = listProviders.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requireAdmin(() -> {
            if (!confirmDelete(sel.getName())) return;
            try { equipmentService.removeProvider(sel.getId()); refreshProviders(); }
            catch (Exception e) { showErrorDialog("Error al eliminar", e.getMessage()); }
        });
    }

    @FXML
    private void handleRemoveSede() {
        Sede sel = listSedes.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requireAdmin(() -> {
            if (!confirmDelete(sel.getName())) return;
            try { equipmentService.removeSede(sel.getId()); refreshSedes(); }
            catch (Exception e) { showErrorDialog("Error al eliminar", e.getMessage()); }
        });
    }

    // ── Equipment dialogs ────────────────────────────────────────────

    private CheckBox buildRequiresSerialCheckbox(boolean selected) {
        Label bold = new Label("  Siempre");
        bold.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        Label rest = new Label(" requerir número de serie");
        rest.setStyle("-fx-font-size: 12px;");
        HBox labelBox = new HBox(bold, rest);
        labelBox.setAlignment(Pos.CENTER_LEFT);

        CheckBox chk = new CheckBox();
        chk.setGraphic(labelBox);
        chk.setSelected(selected);
        return chk;
    }

    // Same timing as UserNoteController's triggerFeedback()/triggerLabelFeedback(), so every
    // inline validation error in the app holds then fades at the same speed.
    private static final Duration FIELD_ERROR_HOLD = Duration.millis(2000);
    private static final Duration FIELD_ERROR_FADE = Duration.millis(650);

    private Label buildErrorLabel() {
        Label lbl = new Label();
        lbl.getStyleClass().add("input-label-small");
        return lbl;
    }

    // Places the error label on the same line as the field's own header label (right-aligned,
    // like UserNoteView's MOTIVO row) instead of below the field, so a validation error never
    // grows the dialog's height.
    private HBox buildFieldHeaderRow(Label headerLabel, Label errorLabel) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(headerLabel, spacer, errorLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void triggerFieldError(Label errorLabel, String message) {
        if (errorLabel.getUserData() instanceof FadeTransition previous) previous.stop();

        errorLabel.setText(message);
        errorLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
        errorLabel.setOpacity(1.0);

        FadeTransition fade = new FadeTransition(FIELD_ERROR_FADE, errorLabel);
        fade.setDelay(FIELD_ERROR_HOLD);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            errorLabel.setText("");
            errorLabel.setStyle("");
            errorLabel.setOpacity(1.0);
            errorLabel.setUserData(null);
        });
        errorLabel.setUserData(fade);
        fade.play();
    }

    private void openAddTypeDialog() {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Nuevo tipo de equipo");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblError = buildErrorLabel();
        TextField tfName = new TextField();
        tfName.setPromptText("Ej: LAPTOP"); tfName.getStyleClass().add("form-input-main");

        CheckBox chkAsset = new CheckBox("Es un activo (tiene número de serie)");
        chkAsset.setSelected(true);
        chkAsset.setStyle("-fx-font-size: 12px;");

        CheckBox chkRequiresSerial = buildRequiresSerialCheckbox(false);
        chkAsset.selectedProperty().addListener((obs, old, isAsset) -> {
            chkRequiresSerial.setDisable(!isAsset);
            if (!isAsset) chkRequiresSerial.setSelected(false);
        });

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Agregar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            String name = tfName.getText().trim();
            if (name.isEmpty()) {
                triggerFieldError(lblError, "El nombre no puede estar vacío");
                return;
            }
            boolean isAsset = chkAsset.isSelected();
            try {
                equipmentService.addType(name, isAsset);
            } catch (Exception ex) {
                triggerFieldError(lblError, ex.getMessage());
                return;
            }
            if (isAsset && chkRequiresSerial.isSelected()) {
                equipmentService.getAllTypes().stream()
                    .filter(t -> t.getName().equalsIgnoreCase(name))
                    .findFirst()
                    .ifPresent(t -> equipmentService.setRequiresSerial(t.getId(), true));
            }
            refreshTypes();
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380);
        root.getChildren().addAll(lblTitle, new VBox(2, buildFieldHeaderRow(lblN, lblError), tfName), chkAsset, chkRequiresSerial, buttons);

        buildAndShow(stage, root, tfName);
    }

    private void openEditTypeDialog(EquipmentType type) {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Editar tipo de equipo");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblError = buildErrorLabel();
        TextField tfName = new TextField(type.getName());
        tfName.getStyleClass().add("form-input-main");

        CheckBox chkRequiresSerial = buildRequiresSerialCheckbox(type.isRequiresSerial());

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Guardar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            String newName = tfName.getText().trim();
            if (newName.isEmpty()) {
                triggerFieldError(lblError, "El nombre no puede estar vacío");
                return;
            }
            if (!newName.equals(type.getName())) {
                try {
                    equipmentService.renameType(type.getId(), newName);
                } catch (Exception ex) {
                    triggerFieldError(lblError, ex.getMessage());
                    return;
                }
            }
            if (type.isAsset()) {
                equipmentService.setRequiresSerial(type.getId(), chkRequiresSerial.isSelected());
            }
            refreshTypes();
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380);
        root.getChildren().addAll(lblTitle, new VBox(2, buildFieldHeaderRow(lblN, lblError), tfName));
        if (type.isAsset()) root.getChildren().add(chkRequiresSerial);
        root.getChildren().add(buttons);

        buildAndShow(stage, root, tfName);
    }

    private void openAddBrandDialog() {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Nueva marca");
        lblTitle.getStyleClass().add("section-label");

        Label lblT = new Label("TIPO DE EQUIPO"); lblT.getStyleClass().add("input-label-small");
        Label lblErrorType = buildErrorLabel();
        ComboBox<EquipmentType> cmbType = new ComboBox<>();
        cmbType.setItems(FXCollections.observableArrayList(equipmentService.getAllTypes()));
        cmbType.setPromptText("Seleccione un tipo...");
        cmbType.setMaxWidth(Double.MAX_VALUE);
        cmbType.getStyleClass().add("form-input-main");
        EquipmentType preType = listTypes.getSelectionModel().getSelectedItem();
        if (preType != null) cmbType.setValue(preType);

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblErrorName = buildErrorLabel();
        TextField tfName = new TextField();
        tfName.setPromptText("Ej: LENOVO"); tfName.getStyleClass().add("form-input-main");

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Agregar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            EquipmentType type = cmbType.getValue();
            String name = tfName.getText().trim();
            if (type == null) {
                triggerFieldError(lblErrorType, "Debe seleccionar un tipo de equipo");
                return;
            }
            if (name.isEmpty()) {
                triggerFieldError(lblErrorName, "El nombre no puede estar vacío");
                return;
            }
            try {
                equipmentService.addBrandForType(name, type.getId());
            } catch (Exception ex) {
                triggerFieldError(lblErrorName, ex.getMessage());
                return;
            }
            EquipmentType selType = listTypes.getSelectionModel().getSelectedItem();
            if (selType != null && selType.getId() == type.getId())
                refreshBrandsForType(type.getId());
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380);
        root.getChildren().addAll(lblTitle,
            new VBox(2, buildFieldHeaderRow(lblT, lblErrorType), cmbType),
            new VBox(2, buildFieldHeaderRow(lblN, lblErrorName), tfName), buttons);

        buildAndShow(stage, root, tfName);
    }

    private void openAddModelDialog() {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Nuevo modelo");
        lblTitle.getStyleClass().add("section-label");

        Label lblT = new Label("TIPO DE EQUIPO"); lblT.getStyleClass().add("input-label-small");
        Label lblErrorType = buildErrorLabel();
        ComboBox<EquipmentType> cmbType = new ComboBox<>();
        cmbType.setItems(FXCollections.observableArrayList(equipmentService.getAllTypes()));
        cmbType.setPromptText("Seleccione un tipo...");
        cmbType.setMaxWidth(Double.MAX_VALUE);
        cmbType.getStyleClass().add("form-input-main");

        Label lblB = new Label("MARCA"); lblB.getStyleClass().add("input-label-small");
        Label lblErrorBrand = buildErrorLabel();
        ComboBox<EquipmentBrand> cmbBrand = new ComboBox<>();
        cmbBrand.setPromptText("Seleccione primero un tipo...");
        cmbBrand.setMaxWidth(Double.MAX_VALUE);
        cmbBrand.setDisable(true);
        cmbBrand.getStyleClass().add("form-input-main");

        cmbType.valueProperty().addListener((obs, old, newType) -> {
            if (newType != null) {
                cmbBrand.setItems(FXCollections.observableArrayList(
                    equipmentService.getBrandsForType(newType.getId())));
                cmbBrand.setValue(null);
                cmbBrand.setDisable(false);
                cmbBrand.setPromptText("Seleccione una marca...");
            }
        });

        EquipmentType  preType  = listTypes.getSelectionModel().getSelectedItem();
        EquipmentBrand preBrand = listBrands.getSelectionModel().getSelectedItem();
        if (preType != null) {
            cmbType.setValue(preType);
            if (preBrand != null) cmbBrand.setValue(preBrand);
        }

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblErrorName = buildErrorLabel();
        TextField tfName = new TextField();
        tfName.setPromptText("Ej: ThinkBook 16 G8"); tfName.getStyleClass().add("form-input-main");

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Agregar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            EquipmentType  type  = cmbType.getValue();
            EquipmentBrand brand = cmbBrand.getValue();
            String name = tfName.getText().trim();
            if (type == null) {
                triggerFieldError(lblErrorType, "Debe seleccionar un tipo de equipo");
                return;
            }
            if (brand == null) {
                triggerFieldError(lblErrorBrand, "Debe seleccionar una marca");
                return;
            }
            if (name.isEmpty()) {
                triggerFieldError(lblErrorName, "El nombre no puede estar vacío");
                return;
            }
            try {
                equipmentService.addModel(name, brand.getId(), type.getId());
            } catch (Exception ex) {
                triggerFieldError(lblErrorName, ex.getMessage());
                return;
            }
            EquipmentType  selT = listTypes.getSelectionModel().getSelectedItem();
            EquipmentBrand selB = listBrands.getSelectionModel().getSelectedItem();
            if (selT != null && selB != null
                && selT.getId() == type.getId() && selB.getId() == brand.getId())
                refreshModelsForBrandType(brand.getId(), type.getId());
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(400);
        root.getChildren().addAll(lblTitle,
            new VBox(2, buildFieldHeaderRow(lblT, lblErrorType), cmbType),
            new VBox(2, buildFieldHeaderRow(lblB, lblErrorBrand), cmbBrand),
            new VBox(2, buildFieldHeaderRow(lblN, lblErrorName), tfName), buttons);

        buildAndShow(stage, root, tfName);
    }

    private void openAddProviderDialog() {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Nuevo proveedor");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblError = buildErrorLabel();
        TextField tfName = new TextField();
        tfName.setPromptText("Ej: TechCorp S.A."); tfName.getStyleClass().add("form-input-main");

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Agregar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            String name = tfName.getText().trim();
            if (name.isEmpty()) {
                triggerFieldError(lblError, "El nombre no puede estar vacío");
                return;
            }
            try {
                equipmentService.addProvider(name);
            } catch (Exception ex) {
                triggerFieldError(lblError, ex.getMessage());
                return;
            }
            refreshProviders();
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380);
        root.getChildren().addAll(lblTitle, new VBox(2, buildFieldHeaderRow(lblN, lblError), tfName), buttons);

        buildAndShow(stage, root, tfName);
    }

    private void openAddSedeDialog() {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Nueva sede");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblError = buildErrorLabel();
        TextField tfName = new TextField();
        tfName.setPromptText("Ej: Campus Córdoba"); tfName.getStyleClass().add("form-input-main");

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Agregar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            String name = tfName.getText().trim();
            if (name.isEmpty()) {
                triggerFieldError(lblError, "El nombre no puede estar vacío");
                return;
            }
            try {
                equipmentService.addSede(name);
            } catch (Exception ex) {
                triggerFieldError(lblError, ex.getMessage());
                return;
            }
            refreshSedes();
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380);
        root.getChildren().addAll(lblTitle, new VBox(2, buildFieldHeaderRow(lblN, lblError), tfName), buttons);

        buildAndShow(stage, root, tfName);
    }

    private void openRenameDialog(String currentName, java.util.function.Consumer<String> onSave) {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Renombrar");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NUEVO NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblError = buildErrorLabel();
        TextField tfName = new TextField(currentName);
        tfName.getStyleClass().add("form-input-main");

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Guardar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            String newName = tfName.getText().trim();
            if (newName.isEmpty()) {
                triggerFieldError(lblError, "El nombre no puede estar vacío");
                return;
            }
            if (newName.equals(currentName)) { stage.close(); return; }
            try {
                onSave.accept(newName);
            } catch (Exception ex) {
                triggerFieldError(lblError, ex.getMessage());
                return;
            }
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(360);
        root.getChildren().addAll(lblTitle, new VBox(2, buildFieldHeaderRow(lblN, lblError), tfName), buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        Platform.runLater(() -> { tfName.requestFocus(); tfName.selectAll(); });
        stage.showAndWait();
    }

    private boolean confirmDelete(String itemName) {
        boolean[] confirmed = {false};
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Confirmar eliminación");
        lblTitle.getStyleClass().add("section-label");

        Label lblMsg = new Label("¿Eliminar \"" + itemName + "\"? Esta acción no se puede deshacer.");
        lblMsg.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");
        lblMsg.setWrapText(true);

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnDel = new Button("Eliminar");
        btnDel.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; " +
            "-fx-background-radius: 6; -fx-font-weight: bold; -fx-cursor: hand;");
        btnDel.setOnAction(e -> { confirmed[0] = true; stage.close(); });

        HBox buttons = new HBox(8, btnCancel, btnDel);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380);
        root.getChildren().addAll(lblTitle, lblMsg, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();

        return confirmed[0];
    }

    private boolean confirmSaveDespiteFailedTest() {
        boolean[] confirmed = {false};
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("No se pudo conectar");
        lblTitle.getStyleClass().add("section-label");

        Label lblMsg = new Label(
            "No se pudo establecer conexión con el servidor remoto usando estos datos. "
                + "¿Guardar de todas formas?");
        lblMsg.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");
        lblMsg.setWrapText(true);

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnConfirm = new Button("Guardar de todas formas");
        btnConfirm.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; " +
            "-fx-background-radius: 6; -fx-font-weight: bold; -fx-cursor: hand;");
        btnConfirm.setOnAction(e -> { confirmed[0] = true; stage.close(); });

        HBox buttons = new HBox(8, btnCancel, btnConfirm);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380);
        root.getChildren().addAll(lblTitle, lblMsg, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();

        return confirmed[0];
    }

    private void buildAndShow(Stage stage, VBox root, TextField focusTarget) {
        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        Platform.runLater(focusTarget::requestFocus);
        stage.showAndWait();
    }

    // ── Admin auth ────────────────────────────────────────────────────

    private void requireAdmin(Runnable action) {
        if (AdminSession.getInstance().isActive()) {
            AdminSession.getInstance().refreshActivity();
            action.run();
            return;
        }
        if (!AdminAuthService.isConfigured()) {
            showErrorDialog("Administrador no configurado",
                "Contacte al desarrollador para configurar el acceso de administrador.");
            return;
        }
        Optional<String> pwd = promptPassword();
        if (pwd.isEmpty()) return;
        if (!AdminAuthService.verify(pwd.get())) {
            showErrorDialog("Acceso denegado", "Contraseña incorrecta.");
            return;
        }
        action.run();
    }

    private Optional<String> promptPassword() {
        Stage stage = buildDialogStage();
        centerOnContent(stage);
        String[] result = {null};

        Label title = new Label("Acceso de administrador");
        title.getStyleClass().add("section-label");

        Label subtitle = new Label("Ingrese la contraseña para continuar.");
        subtitle.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");

        PasswordField pf = new PasswordField();
        pf.setPromptText("Contraseña"); pf.getStyleClass().add("form-input-main");

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
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        Platform.runLater(pf::requestFocus);
        stage.showAndWait();

        return Optional.ofNullable(result[0]);
    }

    // ── APP_SETTINGS helpers ─────────────────────────────────────────

    private String getSetting(String key) {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT value FROM APP_SETTINGS WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private void saveSetting(String key, String value) {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT OR REPLACE INTO APP_SETTINGS (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save setting: " + key, e);
        }
    }

    private String decryptSetting(String key) {
        String enc = getSetting(key);
        if (enc == null || enc.isBlank()) return null;
        try { return AppKeyEncryptionService.getInstance().decrypt(enc); }
        catch (Exception e) { return null; }
    }

    private void saveEncryptedSetting(String key, String value) {
        if (value == null || value.isBlank()) {
            saveSetting(key, "");
        } else {
            saveSetting(key, AppKeyEncryptionService.getInstance().encrypt(value));
        }
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
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();
    }

    private void centerOnContent(Stage stage) {
        stage.setOpacity(0);
        stage.setOnShown(e -> {
            javafx.geometry.Bounds b = rootContainer.localToScreen(rootContainer.getBoundsInLocal());
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
