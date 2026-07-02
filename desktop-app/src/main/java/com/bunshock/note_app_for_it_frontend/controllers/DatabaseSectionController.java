package com.bunshock.note_app_for_it_frontend.controllers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.services.AdminAuthService;
import com.bunshock.note_app_for_it_frontend.services.DatabaseService;
import com.bunshock.note_app_for_it_frontend.services.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.RemoteDatabaseService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.WindowsDPAPIService;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class DatabaseSectionController {

    @FXML private VBox rootContainer;
    @FXML private Label lblDbServer;
    @FXML private Label lblConnectionStatus;
    @FXML private ListView<EquipmentType>  listTypes;
    @FXML private ListView<EquipmentBrand> listBrands;
    @FXML private ListView<EquipmentModel> listModels;

    private IEquipmentService equipmentService;

    public void initialize() {
        equipmentService = ServiceLocator.getInstance().getEquipmentService();
        loadConnectionDisplay();
        refreshTypes();

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
        } else {
            lblDbServer.setText(host + ":" + (port != null && !port.isBlank() ? port : "5432")
                + "/" + (name != null ? name : ""));
            lblDbServer.setStyle("-fx-text-fill: #334155; -fx-font-size: 13px;");
        }
        lblConnectionStatus.setText("");
    }

    @FXML
    private void handleEditConnection() {
        requireAdmin(this::openEditConnectionDialog);
    }

    @FXML
    private void handleTestConnection() {
        RemoteDatabaseService remote = RemoteDatabaseService.getInstance();
        if (remote.isConfigured()) {
            if (remote.testConnection()) {
                lblConnectionStatus.setStyle("-fx-text-fill: #22c55e; -fx-font-size: 11px;");
                lblConnectionStatus.setText("✓ Conexión remota activa");
            } else {
                lblConnectionStatus.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 11px;");
                lblConnectionStatus.setText("✗ No se pudo conectar al servidor remoto");
            }
        } else {
            try (Connection c = DatabaseService.getInstance().getConnection()) {
                c.createStatement().execute("SELECT 1");
                lblConnectionStatus.setStyle("-fx-text-fill: #22c55e; -fx-font-size: 11px;");
                lblConnectionStatus.setText("✓ Base de datos local activa");
            } catch (Exception e) {
                lblConnectionStatus.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 11px;");
                lblConnectionStatus.setText("✗ Error: " + e.getMessage());
            }
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
        TextField tfPort = new TextField(curPort != null && !curPort.isBlank() ? curPort : "5432");
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
            saveSetting("db_host", tfHost.getText().trim());
            saveSetting("db_port", tfPort.getText().trim().isEmpty() ? "5432" : tfPort.getText().trim());
            saveSetting("db_name", tfName.getText().trim());
            saveEncryptedSetting("db_username", tfUser.getText().trim());
            saveEncryptedSetting("db_password", pfPass.getText());
            loadConnectionDisplay();
            stage.close();
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

    private void refreshBrandsForType(int typeId) {
        listBrands.setItems(FXCollections.observableArrayList(equipmentService.getBrandsForType(typeId)));
        listModels.setItems(FXCollections.observableArrayList());
    }

    private void refreshModelsForBrandType(int brandId, int typeId) {
        listModels.setItems(FXCollections.observableArrayList(
            equipmentService.getModelsForBrandAndType(brandId, typeId)));
    }

    @FXML private void handleAddType()    { requireAdmin(this::openAddTypeDialog); }
    @FXML private void handleAddBrand()   { requireAdmin(this::openAddBrandDialog); }
    @FXML private void handleAddModel()   { requireAdmin(this::openAddModelDialog); }

    @FXML
    private void handleRenameType() {
        EquipmentType sel = listTypes.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requireAdmin(() -> openRenameDialog(sel.getName(), newName -> {
            equipmentService.renameType(sel.getId(), newName);
            refreshTypes();
        }));
    }

    @FXML
    private void handleRenameBrand() {
        EquipmentBrand sel = listBrands.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requireAdmin(() -> openRenameDialog(sel.getName(), newName -> {
            equipmentService.renameBrand(sel.getId(), newName);
            EquipmentType type = listTypes.getSelectionModel().getSelectedItem();
            if (type != null) refreshBrandsForType(type.getId());
        }));
    }

    @FXML
    private void handleRenameModel() {
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

    // ── Equipment dialogs ────────────────────────────────────────────

    private void openAddTypeDialog() {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Nuevo tipo de equipo");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
        TextField tfName = new TextField();
        tfName.setPromptText("Ej: LAPTOP"); tfName.getStyleClass().add("form-input-main");

        CheckBox chkAsset = new CheckBox("Es un activo (tiene número de serie)");
        chkAsset.setSelected(true);
        chkAsset.setStyle("-fx-font-size: 12px;");

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Agregar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            String name = tfName.getText().trim();
            if (name.isEmpty()) return;
            equipmentService.addType(name, chkAsset.isSelected());
            refreshTypes();
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380);
        root.getChildren().addAll(lblTitle, new VBox(2, lblN, tfName), chkAsset, buttons);

        buildAndShow(stage, root, tfName);
    }

    private void openAddBrandDialog() {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Nueva marca");
        lblTitle.getStyleClass().add("section-label");

        Label lblT = new Label("TIPO DE EQUIPO"); lblT.getStyleClass().add("input-label-small");
        ComboBox<EquipmentType> cmbType = new ComboBox<>();
        cmbType.setItems(FXCollections.observableArrayList(equipmentService.getAllTypes()));
        cmbType.setPromptText("Seleccione un tipo...");
        cmbType.setMaxWidth(Double.MAX_VALUE);
        cmbType.getStyleClass().add("form-input-main");
        EquipmentType preType = listTypes.getSelectionModel().getSelectedItem();
        if (preType != null) cmbType.setValue(preType);

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
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
            if (type == null || name.isEmpty()) return;
            equipmentService.addBrandForType(name, type.getId());
            EquipmentType selType = listTypes.getSelectionModel().getSelectedItem();
            if (selType != null && selType.getId() == type.getId())
                refreshBrandsForType(type.getId());
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380);
        root.getChildren().addAll(lblTitle,
            new VBox(2, lblT, cmbType), new VBox(2, lblN, tfName), buttons);

        buildAndShow(stage, root, tfName);
    }

    private void openAddModelDialog() {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Nuevo modelo");
        lblTitle.getStyleClass().add("section-label");

        Label lblT = new Label("TIPO DE EQUIPO"); lblT.getStyleClass().add("input-label-small");
        ComboBox<EquipmentType> cmbType = new ComboBox<>();
        cmbType.setItems(FXCollections.observableArrayList(equipmentService.getAllTypes()));
        cmbType.setPromptText("Seleccione un tipo...");
        cmbType.setMaxWidth(Double.MAX_VALUE);
        cmbType.getStyleClass().add("form-input-main");

        Label lblB = new Label("MARCA"); lblB.getStyleClass().add("input-label-small");
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
            if (type == null || brand == null || name.isEmpty()) return;
            equipmentService.addModel(name, brand.getId(), type.getId());
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
            new VBox(2, lblT, cmbType), new VBox(2, lblB, cmbBrand),
            new VBox(2, lblN, tfName), buttons);

        buildAndShow(stage, root, tfName);
    }

    private void openRenameDialog(String currentName, java.util.function.Consumer<String> onSave) {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Renombrar");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NUEVO NOMBRE"); lblN.getStyleClass().add("input-label-small");
        TextField tfName = new TextField(currentName);
        tfName.getStyleClass().add("form-input-main");

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Guardar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            String newName = tfName.getText().trim();
            if (newName.isEmpty() || newName.equals(currentName)) { stage.close(); return; }
            onSave.accept(newName);
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(360);
        root.getChildren().addAll(lblTitle, new VBox(2, lblN, tfName), buttons);

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

    private void buildAndShow(Stage stage, VBox root, TextField focusTarget) {
        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        Platform.runLater(focusTarget::requestFocus);
        stage.showAndWait();
    }

    // ── Admin auth ────────────────────────────────────────────────────

    private void requireAdmin(Runnable action) {
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
        try { return WindowsDPAPIService.getInstance().decrypt(enc); }
        catch (Exception e) { return null; }
    }

    private void saveEncryptedSetting(String key, String value) {
        if (value == null || value.isBlank()) {
            saveSetting(key, "");
        } else {
            saveSetting(key, WindowsDPAPIService.getInstance().encrypt(value));
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
