package com.bunshock.note_app_for_it_frontend.controllers.admin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.bunshock.note_app_for_it_frontend.models.core.AppConfig;
import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.admin.Permission;
import com.bunshock.note_app_for_it_frontend.models.catalog.Sede;
import com.bunshock.note_app_for_it_frontend.services.auth.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.core.AppKeyEncryptionService;
import com.bunshock.note_app_for_it_frontend.services.core.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.core.DatabaseService;
import com.bunshock.note_app_for_it_frontend.services.catalog.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.admin.IUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.core.RemoteDatabaseService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
import com.bunshock.note_app_for_it_frontend.utils.core.DialogChrome;
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
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
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
    @FXML private ComboBox<Sede> cmbStockSede;
    @FXML private Label lblEquipmentCatalogSede;

    @FXML private Button btnAddType, btnEditType, btnRemoveType;
    @FXML private Button btnAddBrand, btnEditBrand, btnRemoveBrand;
    @FXML private Button btnAddModel, btnEditModel, btnModifyStock, btnRemoveModel;

    private IEquipmentService equipmentService;

    // Stock is tracked per Sede — null means "every Sede combined," selectable only by
    // SUPERADMIN. A plain ADMIN/USER is locked to their own assigned Sede.
    private Integer currentStockSedeId;

    // True only when a non-SUPERADMIN has no Sede assigned — currentStockSedeId is null in that
    // case too, but the two aren't the same: null from "Todas" means every Sede combined; null
    // here means no valid scope at all. stockTotalsByType()/ByBrandForType()/
    // ByModelForBrandAndType() below check this before reading currentStockSedeId.
    private boolean stockSedeUnassigned;

    // Guards listTypes'/listBrands' selection listeners while refreshStockRollupsOnly()
    // re-selects a Type/Brand by id after resorting (a different instance than what was
    // selected), which would otherwise cascade into refreshBrandsForType()/
    // refreshModelsForBrandType() and wipe listModels' state.
    private boolean suppressSelectionListeners = false;

    public void initialize() {
        equipmentService = ServiceLocator.getInstance().getEquipmentService();
        loadConnectionDisplay();
        initStockSedeSelector();
        refreshTypes();

        listTypes.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (suppressSelectionListeners) return;
            if (sel != null) refreshBrandsForType(sel.getId());
            else { listBrands.setItems(FXCollections.observableArrayList()); listModels.setItems(FXCollections.observableArrayList()); }
        });

        listBrands.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (suppressSelectionListeners) return;
            EquipmentType type = listTypes.getSelectionModel().getSelectedItem();
            if (sel != null && type != null) refreshModelsForBrandType(sel.getId(), type.getId());
            else listModels.setItems(FXCollections.observableArrayList());
        });

        AdminSession.getInstance().addOnActivateListener(this::updateCrudButtonVisibility);
        AdminSession.getInstance().addOnDeactivateListener(this::updateCrudButtonVisibility);
        updateCrudButtonVisibility();
    }

    // requirePermission() has no fallback for a plain technician, so these are hidden rather
    // than left visible-but-dead. Not Sede-scoped for MANAGE_STOCK — cmbStockSede's selection
    // changes live as the technician browses, so visibility is governed by the role permission
    // alone; the Sede match itself is enforced at click time via
    // requirePermission(Permission, Integer, Runnable).
    private void updateCrudButtonVisibility() {
        setButtonVisible(btnAddType,    AdminSession.getInstance().hasPermission(Permission.MANAGE_TYPES));
        setButtonVisible(btnEditType,   AdminSession.getInstance().hasPermission(Permission.MANAGE_TYPES));
        setButtonVisible(btnRemoveType, AdminSession.getInstance().hasPermission(Permission.MANAGE_TYPES));

        setButtonVisible(btnAddBrand,    AdminSession.getInstance().hasPermission(Permission.MANAGE_BRANDS));
        setButtonVisible(btnEditBrand,   AdminSession.getInstance().hasPermission(Permission.MANAGE_BRANDS));
        setButtonVisible(btnRemoveBrand, AdminSession.getInstance().hasPermission(Permission.MANAGE_BRANDS));

        setButtonVisible(btnAddModel,    AdminSession.getInstance().hasPermission(Permission.MANAGE_MODELS));
        setButtonVisible(btnEditModel,   AdminSession.getInstance().hasPermission(Permission.MANAGE_MODELS));
        setButtonVisible(btnRemoveModel, AdminSession.getInstance().hasPermission(Permission.MANAGE_MODELS));
        setButtonVisible(btnModifyStock, AdminSession.getInstance().hasPermission(Permission.MANAGE_STOCK));
    }

    private void setButtonVisible(Button button, boolean visible) {
        button.setVisible(visible);
        button.setManaged(visible);
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

    // Not admin-gated — db_host/port/name/username/password live in local-only APP_SETTINGS
    // (never synced), so this only ever affects the machine it's changed on; the
    // test-connection-before-accepting flow below still guards against saving an unreachable
    // config.
    @FXML
    private void handleEditConnection() {
        openEditConnectionDialog();
    }

    // Tests and reports both databases independently, since either can be reachable/configured
    // without the other.
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
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

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
        tfHost.setTextFormatter(connectionFieldFormatter());

        Label lblP = new Label("PUERTO"); lblP.getStyleClass().add("input-label-small");
        TextField tfPort = new TextField(curPort != null && !curPort.isBlank() ? curPort : "1433");
        tfPort.setPrefWidth(80); tfPort.getStyleClass().add("form-input-main");
        tfPort.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().matches("\\d{0,5}") ? change : null));

        VBox hostBox = new VBox(2, lblH, tfHost); HBox.setHgrow(hostBox, Priority.ALWAYS);
        VBox portBox = new VBox(2, lblP, tfPort);
        HBox hostPort = new HBox(8, hostBox, portBox);

        Label lblN = new Label("BASE DE DATOS"); lblN.getStyleClass().add("input-label-small");
        TextField tfName = new TextField(curName != null ? curName : "");
        tfName.setPromptText("Ej: noteapp_db"); tfName.getStyleClass().add("form-input-main");
        tfName.setTextFormatter(connectionFieldFormatter());

        Label lblU = new Label("USUARIO"); lblU.getStyleClass().add("input-label-small");
        TextField tfUser = new TextField(curUser != null ? curUser : "");
        tfUser.setPromptText("Ej: admin"); tfUser.getStyleClass().add("form-input-main");
        tfUser.setTextFormatter(connectionFieldFormatter());

        // Write-only, like every other secret field in this app — never pre-filled with the
        // decrypted value. A PasswordField's masked text can still be selected/copied, so
        // pre-filling would expose the real shared DB password to anyone who opens this screen.
        Label lblPw = new Label("CONTRASEÑA");
        lblPw.getStyleClass().add("input-label-small");
        Label lblPwError = buildErrorLabel();
        PasswordField pfPass = new PasswordField();
        pfPass.setPromptText("Dejar en blanco para no cambiarla");
        pfPass.getStyleClass().add("form-input-main");
        pfPass.setTextFormatter(connectionFieldFormatter());

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
            // Blank keeps the existing password, but only when host/port are unchanged —
            // reusing it against a different host would submit the live credential to wherever
            // the field now points, via the test-connection call below, before saving anything.
            String curHostNorm = curHost != null ? curHost : "";
            String curPortNorm = curPort != null && !curPort.isBlank() ? curPort : "1433";
            boolean destinationChanged = !host.equals(curHostNorm) || !portStr.equals(curPortNorm);

            String typedPass = pfPass.getText();
            if (typedPass.isBlank() && destinationChanged && !host.isEmpty()) {
                triggerFieldError(lblPwError, "Ingrese la contraseña al cambiar de servidor o puerto");
                return;
            }
            String pass = typedPass.isBlank() ? (curPass != null ? curPass : "") : typedPass;

            Runnable persistAndClose = () -> {
                saveSetting("db_host", host);
                saveSetting("db_port", portStr);
                saveSetting("db_name", name);
                saveEncryptedSetting("db_username", user);
                if (!typedPass.isBlank()) saveEncryptedSetting("db_password", typedPass);
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
            tfHost.setDisable(true);
            tfPort.setDisable(true);
            tfName.setDisable(true);
            tfUser.setDisable(true);
            pfPass.setDisable(true);

            int testPort = port;
            Thread t = new Thread(() -> {
                boolean ok = testPort > 0 && RemoteDatabaseService.getInstance()
                    .testConnection(host, testPort, name, user, pass);
                Platform.runLater(() -> {
                    btnSave.setDisable(false);
                    btnCancel.setDisable(false);
                    btnSave.setText("Guardar");
                    tfHost.setDisable(false);
                    tfPort.setDisable(false);
                    tfName.setDisable(false);
                    tfUser.setDisable(false);
                    pfPass.setDisable(false);
                    if (ok || confirmSaveDespiteFailedTest()) persistAndClose.run();
                });
            }, "db-connection-test");
            t.setDaemon(true);
            t.start();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = DialogChrome.buildDialogRoot(440, "#1a1a1a");
        root.getChildren().addAll(lblTitle, hostPort,
            new VBox(2, lblN, tfName),
            new VBox(2, lblU, tfUser),
            new VBox(2, buildFieldHeaderRow(lblPw, lblPwError), pfPass),
            new Separator(), buttons);

        Scene scene = DialogChrome.buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        Platform.runLater(tfHost::requestFocus);
        stage.showAndWait();
    }

    // ── Equipment catalog ────────────────────────────────────────────

    // Called on every navigation into this section — ViewFactory caches the section for the
    // whole session, so initialize()'s snapshot never re-runs on its own. Stock can change from
    // outside this controller entirely (note approval, Préstamo/Provider returns), with no
    // listener back to whichever instance is showing a given Model.
    public void refresh() {
        EquipmentType selectedType = listTypes.getSelectionModel().getSelectedItem();
        if (selectedType == null) {
            refreshTypes();
            return;
        }
        // Refreshes Tipos + Marcas' rollups in place, preserving both selections; never touches
        // listModels itself (see refreshStockRollupsOnly()'s own comment).
        refreshStockRollupsOnly(selectedType.getId());
        EquipmentBrand reselectedBrand = listBrands.getSelectionModel().getSelectedItem();
        if (reselectedBrand != null) {
            refreshModelsForBrandType(reselectedBrand.getId(), selectedType.getId());
        }
    }

    // Manual escape hatch for the same staleness refresh() fixes on navigation — for a technician
    // working a long stretch inside this section without leaving it. Not permission-gated: a
    // read-only refresh of already-visible data.
    @FXML
    private void handleRefreshCatalog() {
        refresh();
    }

    // Checks stockSedeUnassigned first — see that field's own comment.
    private Map<Integer, Integer> stockTotalsByType() {
        return stockSedeUnassigned ? Map.of() : equipmentService.getStockTotalsByType(currentStockSedeId);
    }

    private Map<Integer, Integer> stockTotalsByBrandForType(int typeId) {
        return stockSedeUnassigned ? Map.of() : equipmentService.getStockTotalsByBrandForType(typeId, currentStockSedeId);
    }

    private Map<Integer, Integer> stockTotalsByModelForBrandAndType(int brandId, int typeId) {
        return stockSedeUnassigned ? Map.of()
            : equipmentService.getStockTotalsByModelForBrandAndType(brandId, typeId, currentStockSedeId);
    }

    private void refreshTypes() {
        Map<Integer, Integer> stockByType = stockTotalsByType();
        List<EquipmentType> types = sortStockFirstThenAlphabetical(
            equipmentService.getAllTypes(), t -> stockByType.getOrDefault(t.getId(), 0), EquipmentType::getName);
        listTypes.setItems(FXCollections.observableArrayList(types));
        applyCatalogCellFactory(listTypes, t -> stockByType.getOrDefault(t.getId(), 0));
        listBrands.setItems(FXCollections.observableArrayList());
        listModels.setItems(FXCollections.observableArrayList());
    }

    // The global generic brand isn't linked to every type, so it's synthesized here the same way
    // ItemDialogController.onTypeSelected() does — otherwise it would only appear for types with
    // a real, now-vestigial BRAND_TYPE_LINK. Its final position (last within its stock group)
    // comes from sortStockFirstThenAlphabetical() below.
    private void refreshBrandsForType(int typeId) {
        List<EquipmentBrand> brands = new ArrayList<>(equipmentService.getBrandsForType(typeId));
        if (brands.stream().noneMatch(b -> genericLabel().equals(b.getName()))) {
            equipmentService.getAllBrands().stream()
                .filter(b -> genericLabel().equals(b.getName()))
                .findFirst()
                .ifPresent(brands::add);
        }
        Map<Integer, Integer> stockByBrand = stockTotalsByBrandForType(typeId);
        List<EquipmentBrand> sorted = sortStockFirstThenAlphabetical(
            brands, b -> stockByBrand.getOrDefault(b.getId(), 0), EquipmentBrand::getName);
        listBrands.setItems(FXCollections.observableArrayList(sorted));
        applyCatalogCellFactory(listBrands, b -> stockByBrand.getOrDefault(b.getId(), 0));
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

    // The global generic model's own final position (last within its stock group) comes from
    // sortStockFirstThenAlphabetical() below, same as the Brand list above.
    private void refreshModelsForBrandType(int brandId, int typeId) {
        List<EquipmentModel> models = equipmentService.getModelsForBrandAndType(brandId, typeId);
        Map<Integer, Integer> stockByModel = stockTotalsByModelForBrandAndType(brandId, typeId);
        List<EquipmentModel> sorted = sortStockFirstThenAlphabetical(
            models, m -> stockByModel.getOrDefault(m.getId(), 0), EquipmentModel::getName);
        listModels.setItems(FXCollections.observableArrayList(sorted));
        applyCatalogCellFactory(listModels, m -> stockByModel.getOrDefault(m.getId(), 0));
    }

    // Items with stock > 0 sort first, then zero-stock items, each group alphabetical
    // (case-insensitive); the synthesized "Genérico / Otro" fallback (Brand/Model lists only)
    // sorts last within its group.
    private <T> List<T> sortStockFirstThenAlphabetical(List<T> items, Function<T, Integer> stockLookup,
            Function<T, String> nameLookup) {
        Comparator<T> genericLast = Comparator.comparing(
            item -> genericLabel().equalsIgnoreCase(nameLookup.apply(item)));
        Comparator<T> alphabetical = Comparator.comparing(item -> nameLookup.apply(item).toLowerCase());
        Comparator<T> withinGroup = genericLast.thenComparing(alphabetical);

        List<T> hasStock = items.stream()
            .filter(item -> stockLookup.apply(item) > 0)
            .sorted(withinGroup)
            .collect(Collectors.toList());
        List<T> zeroStock = items.stream()
            .filter(item -> stockLookup.apply(item) <= 0)
            .sorted(withinGroup)
            .collect(Collectors.toList());
        hasStock.addAll(zeroStock);
        return hasStock;
    }

    // A stock edit changes the Type/Brand rollup totals too, which can move one between the
    // has-stock/zero-stock groups. Re-sorts/rebuilds listTypes'/listBrands' items but never
    // touches listModels — that list was already rebuilt by refreshModelsForBrandType(), called
    // right before this in every caller.
    //
    // Selection is preserved by id, not object identity: getAllTypes()/getBrandsForType() return
    // freshly-queried objects each call, so the previously-selected Type/Brand is never the same
    // instance as its post-sort replacement. Re-selecting by id under suppressSelectionListeners
    // avoids re-triggering the selection listeners and wiping listModels' state.
    private void refreshStockRollupsOnly(int typeId) {
        EquipmentType selectedType = listTypes.getSelectionModel().getSelectedItem();
        EquipmentBrand selectedBrand = listBrands.getSelectionModel().getSelectedItem();

        suppressSelectionListeners = true;
        try {
            Map<Integer, Integer> stockByType = stockTotalsByType();
            List<EquipmentType> sortedTypes = sortStockFirstThenAlphabetical(
                equipmentService.getAllTypes(), t -> stockByType.getOrDefault(t.getId(), 0), EquipmentType::getName);
            listTypes.setItems(FXCollections.observableArrayList(sortedTypes));
            applyCatalogCellFactory(listTypes, t -> stockByType.getOrDefault(t.getId(), 0));
            if (selectedType != null) {
                sortedTypes.stream().filter(t -> t.getId() == selectedType.getId()).findFirst()
                    .ifPresent(t -> listTypes.getSelectionModel().select(t));
            }

            List<EquipmentBrand> brands = new ArrayList<>(equipmentService.getBrandsForType(typeId));
            if (brands.stream().noneMatch(b -> genericLabel().equals(b.getName()))) {
                equipmentService.getAllBrands().stream()
                    .filter(b -> genericLabel().equals(b.getName()))
                    .findFirst().ifPresent(brands::add);
            }
            Map<Integer, Integer> stockByBrand = stockTotalsByBrandForType(typeId);
            List<EquipmentBrand> sortedBrands = sortStockFirstThenAlphabetical(
                brands, b -> stockByBrand.getOrDefault(b.getId(), 0), EquipmentBrand::getName);
            listBrands.setItems(FXCollections.observableArrayList(sortedBrands));
            applyCatalogCellFactory(listBrands, b -> stockByBrand.getOrDefault(b.getId(), 0));
            if (selectedBrand != null) {
                sortedBrands.stream().filter(b -> b.getId() == selectedBrand.getId()).findFirst()
                    .ifPresent(b -> listBrands.getSelectionModel().select(b));
            }
        } finally {
            suppressSelectionListeners = false;
        }
    }

    private static final String GENERIC_STYLE = "-fx-font-style: italic; -fx-text-fill: #94a3b8;";

    // Two-column row (name left, stock right-aligned). Stock comes from an already-fetched
    // rollup Map (one query per refresh, not per row). Selected-row text color is handled
    // manually since CSS text-fill on the cell doesn't reach into a custom graphic's child
    // Labels.
    private static final double STOCK_COLUMN_WIDTH = 50;
    private static final String STOCK_ALIGN_STYLE = "-fx-alignment: CENTER_RIGHT;";

    private <T> void applyCatalogCellFactory(ListView<T> listView, Function<T, Integer> stockLookup) {
        listView.setCellFactory(lv -> new ListCell<>() {
            private final Label lblName = new Label();
            private final Label lblStock = new Label();
            private final Region spacer = new Region();
            private final HBox row = new HBox(8, lblName, spacer, lblStock);
            {
                HBox.setHgrow(spacer, Priority.ALWAYS);
                row.setAlignment(Pos.CENTER_LEFT);
                lblStock.setMinWidth(STOCK_COLUMN_WIDTH);
                lblStock.setPrefWidth(STOCK_COLUMN_WIDTH);
                lblStock.setMaxWidth(STOCK_COLUMN_WIDTH);
                lblStock.setStyle(STOCK_ALIGN_STYLE);
                // A ListCell's graphic isn't stretched to the cell's own width by default, so the
                // spacer has nothing to grow into without this — 24 matches
                // ".modern-list .list-cell"'s own padding (7 12) so the bound width matches the
                // cell's actual content area.
                row.prefWidthProperty().bind(widthProperty().subtract(24));
                selectedProperty().addListener((obs, was, sel) -> refreshTextStyle());
            }

            private void refreshTextStyle() {
                if (getItem() == null) return;
                boolean generic = genericLabel().equals(getItem().toString());
                String base = "-fx-font-size: 12px;";
                if (generic) {
                    lblName.setStyle(base + GENERIC_STYLE);
                } else {
                    String color = isSelected() ? "-fx-text-fill: #f4f8f7;" : "-fx-text-fill: #334155;";
                    String weight = isSelected() ? "-fx-font-weight: bold;" : "";
                    lblName.setStyle(base + color + weight);
                }
                // Black (not green) for in-stock — green blended into the selected row's teal
                // background, low contrast.
                Integer stock = stockLookup.apply(getItem());
                String stockColor = stock != null && stock > 0 ? "#000000" : "#ef4444";
                lblStock.setStyle(base + "-fx-font-weight: bold; -fx-text-fill: " + stockColor + ";" + STOCK_ALIGN_STYLE);
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    lblName.setText(item.toString());
                    Integer stock = stockLookup.apply(item);
                    lblStock.setText(stock != null ? String.valueOf(stock) : "0");
                    setGraphic(row);
                    refreshTextStyle();
                }
            }
        });
    }

    // A plain ADMIN/USER is locked to their own assigned Sede — the title row already names it,
    // so the combo box is hidden entirely rather than shown disabled. A SUPERADMIN gets the full
    // list plus a leading null "Todas" entry (every Sede combined), defaulting to it.
    private void initStockSedeSelector() {
        cmbStockSede.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Sede s) { return s == null ? "Todas" : s.getName(); }
            @Override public Sede fromString(String s) { return null; }
        });
        boolean isSuperadmin = IUserRoleService.ROLE_SUPERADMIN
            .equals(TechnicianSessionService.getInstance().getRole());
        if (isSuperadmin) {
            List<Sede> items = new ArrayList<>();
            items.add(null);
            items.addAll(equipmentService.getAllSedes());
            cmbStockSede.setItems(FXCollections.observableArrayList(items));
            cmbStockSede.getSelectionModel().selectFirst();
            currentStockSedeId = null;
            stockSedeUnassigned = false;
            cmbStockSede.setVisible(true);
        } else {
            Integer mySedeId = TechnicianSessionService.getInstance().getSedeId();
            currentStockSedeId = mySedeId;
            stockSedeUnassigned = mySedeId == null;
            if (mySedeId != null) {
                equipmentService.getAllSedes().stream()
                    .filter(s -> s.getId() == mySedeId)
                    .findFirst()
                    .ifPresent(s -> cmbStockSede.setItems(FXCollections.observableArrayList(s)));
                cmbStockSede.getSelectionModel().selectFirst();
            }
            // visible=false only (not managed=false) — keeps the combo's layout space reserved
            // so the title row's height matches the SUPERADMIN case.
            cmbStockSede.setVisible(false);
        }
        cmbStockSede.valueProperty().addListener((obs, old, sel) -> {
            currentStockSedeId = sel == null ? null : sel.getId();
            refreshTypes();
            updateEquipmentCatalogTitle();
        });
        updateEquipmentCatalogTitle();
    }

    // Names the Sede the shown stock numbers belong to, so a technician can't mistake one Sede's
    // counts for another's.
    private void updateEquipmentCatalogTitle() {
        Sede sel = cmbStockSede.getValue();
        String sedeLabel;
        if (sel != null) {
            sedeLabel = sel.getName();
        } else if (IUserRoleService.ROLE_SUPERADMIN.equals(TechnicianSessionService.getInstance().getRole())) {
            sedeLabel = "Todas las sedes";
        } else {
            sedeLabel = "Sede no asignada";
        }
        lblEquipmentCatalogSede.setText(sedeLabel.toUpperCase());
    }

    // A combined "Todas" total has no single row to write to — called at the top of every
    // stock-writing dialog so a SUPERADMIN viewing "Todas" must pick a concrete Sede first. A
    // non-SUPERADMIN with no Sede assigned hits the same guard but gets a different message,
    // since there's no selector for them to pick from.
    private boolean requireConcreteStockSede() {
        if (currentStockSedeId != null) return true;
        if (stockSedeUnassigned) {
            showErrorDialog("Sede no asignada",
                "Todavía no tiene una Sede asignada. Solicite a un administrador que le asigne una "
                    + "para poder modificar el stock.");
        } else {
            showErrorDialog("Seleccione una sede",
                "Seleccione una sede específica (no \"Todas\") en el selector de Stock — Sede para modificar el stock.");
        }
        return false;
    }

    @FXML private void handleAddType()    { requirePermission(Permission.MANAGE_TYPES, this::openAddTypeDialog); }
    @FXML private void handleAddBrand()   { requirePermission(Permission.MANAGE_BRANDS, this::openAddBrandDialog); }
    @FXML private void handleAddModel()   { requirePermission(Permission.MANAGE_MODELS, this::openAddModelDialog); }

    @FXML
    private void handleEditType() {
        EquipmentType sel = listTypes.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requirePermission(Permission.MANAGE_TYPES, () -> openEditTypeDialog(sel));
    }

    @FXML
    private void handleEditBrand() {
        EquipmentBrand sel = listBrands.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requirePermission(Permission.MANAGE_BRANDS, () -> openRenameDialog(sel.getName(), newName -> {
            equipmentService.renameBrand(sel.getId(), newName);
            auditCatalogAction("RENAME_BRAND", "BRAND", String.valueOf(sel.getId()), sel.getName(), newName);
            EquipmentType type = listTypes.getSelectionModel().getSelectedItem();
            if (type != null) refreshBrandsForType(type.getId());
        }));
    }

    @FXML
    private void handleEditModel() {
        EquipmentModel sel = listModels.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        EquipmentType  type  = listTypes.getSelectionModel().getSelectedItem();
        EquipmentBrand brand = listBrands.getSelectionModel().getSelectedItem();
        if (type == null || brand == null) return;
        requirePermission(Permission.MANAGE_MODELS, () -> openEditModelDialog(sel, brand.getId(), type.getId()));
    }

    // Quick, stock-only alternative to "Editar" (which also renames). Its own permission since a
    // future role might adjust stock without full model-management rights. Sede-scoped, unlike
    // MANAGE_MODELS, which governs catalog structure rather than any one Sede's numbers.
    @FXML
    private void handleModifyStock() {
        EquipmentModel sel = listModels.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        EquipmentType  type  = listTypes.getSelectionModel().getSelectedItem();
        EquipmentBrand brand = listBrands.getSelectionModel().getSelectedItem();
        if (type == null || brand == null) return;
        requirePermission(Permission.MANAGE_STOCK, currentStockSedeId,
            () -> openModifyStockDialog(sel, brand.getId(), type.getId()));
    }

    @FXML
    private void handleRemoveType() {
        EquipmentType sel = listTypes.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requirePermission(Permission.MANAGE_TYPES, () -> {
            if (!confirmDelete(sel.getName())) return;
            try {
                equipmentService.removeType(sel.getId());
                auditCatalogAction("REMOVE_TYPE", "TYPE", String.valueOf(sel.getId()), sel.getName(), null);
                refreshTypes();
            }
            catch (Exception e) { showErrorDialog("Error al eliminar", e.getMessage()); }
        });
    }

    @FXML
    private void handleRemoveBrand() {
        EquipmentBrand sel = listBrands.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requirePermission(Permission.MANAGE_BRANDS, () -> {
            if (!confirmDelete(sel.getName())) return;
            try {
                equipmentService.removeBrand(sel.getId());
                auditCatalogAction("REMOVE_BRAND", "BRAND", String.valueOf(sel.getId()), sel.getName(), null);
                EquipmentType type = listTypes.getSelectionModel().getSelectedItem();
                if (type != null) refreshBrandsForType(type.getId());
            } catch (Exception e) { showErrorDialog("Error al eliminar", e.getMessage()); }
        });
    }

    @FXML
    private void handleRemoveModel() {
        EquipmentModel sel = listModels.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        requirePermission(Permission.MANAGE_MODELS, () -> {
            if (!confirmDelete(sel.getName())) return;
            try {
                equipmentService.removeModel(sel.getId());
                auditCatalogAction("REMOVE_MODEL", "MODEL", String.valueOf(sel.getId()), sel.getName(), null);
                EquipmentType  type  = listTypes.getSelectionModel().getSelectedItem();
                EquipmentBrand brand = listBrands.getSelectionModel().getSelectedItem();
                if (type != null && brand != null) refreshModelsForBrandType(brand.getId(), type.getId());
            } catch (Exception e) { showErrorDialog("Error al eliminar", e.getMessage()); }
        });
    }

    // ── Equipment dialogs ────────────────────────────────────────────

    // Only the word "Siempre" is bold — a plain CheckBox.setText(...) can't mix font weights
    // within one label, so this builds a small HBox of two Labels (one bold) and sets it as the
    // checkbox's graphic instead.
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

    // Matches TYPE/BRAND/MODEL/PROVIDER/SEDE.name's NVARCHAR(255) bound on SQL Server.
    private static final int CATALOG_NAME_MAX_LENGTH = 255;

    private TextFormatter<String> catalogNameFormatter() {
        return new TextFormatter<>(change ->
            change.getControlNewText().length() <= CATALOG_NAME_MAX_LENGTH ? change : null);
    }

    // db_host/port/name/username/password are local-only APP_SETTINGS values with no SQL Server
    // column bound to match — capped as a sanity guard against an accidental huge paste.
    private static final int CONNECTION_FIELD_MAX_LENGTH = 255;

    private TextFormatter<String> connectionFieldFormatter() {
        return new TextFormatter<>(change ->
            change.getControlNewText().length() <= CONNECTION_FIELD_MAX_LENGTH ? change : null);
    }

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
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

        Label lblTitle = new Label("Nuevo tipo de equipo");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblError = buildErrorLabel();
        TextField tfName = new TextField();
        tfName.setTextFormatter(catalogNameFormatter());
        tfName.setPromptText("Ej: LAPTOP"); tfName.getStyleClass().add("form-input-main");

        CheckBox chkAsset = new CheckBox("Es un activo (tiene número de serie)");
        chkAsset.setSelected(true);

        CheckBox chkRequiresSerial = buildRequiresSerialCheckbox(false);
        chkAsset.selectedProperty().addListener((obs, old, isAsset) -> {
            chkRequiresSerial.setDisable(!isAsset);
            chkRequiresSerial.setVisible(isAsset);
            chkRequiresSerial.setManaged(isAsset);
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
            auditCatalogAction("ADD_TYPE", "TYPE", name, null, name);
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

        VBox root = DialogChrome.buildDialogRoot(380, "#1a1a1a");
        root.getChildren().addAll(lblTitle, new VBox(2, buildFieldHeaderRow(lblN, lblError), tfName),
            chkAsset, chkRequiresSerial, buttons);

        buildAndShow(stage, root, tfName);
    }

    private void openEditTypeDialog(EquipmentType type) {
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

        Label lblTitle = new Label("Editar tipo de equipo");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblError = buildErrorLabel();
        TextField tfName = new TextField(type.getName());
        tfName.setTextFormatter(catalogNameFormatter());
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
                auditCatalogAction("RENAME_TYPE", "TYPE", String.valueOf(type.getId()), type.getName(), newName);
            }
            if (type.isAsset() && chkRequiresSerial.isSelected() != type.isRequiresSerial()) {
                equipmentService.setRequiresSerial(type.getId(), chkRequiresSerial.isSelected());
                auditCatalogAction("SET_REQUIRES_SERIAL", "TYPE", String.valueOf(type.getId()),
                    String.valueOf(type.isRequiresSerial()), String.valueOf(chkRequiresSerial.isSelected()));
            }
            refreshTypes();
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = DialogChrome.buildDialogRoot(380, "#1a1a1a");
        root.getChildren().addAll(lblTitle, new VBox(2, buildFieldHeaderRow(lblN, lblError), tfName));
        if (type.isAsset()) root.getChildren().add(chkRequiresSerial);
        root.getChildren().add(buttons);

        buildAndShow(stage, root, tfName);
    }

    private void openAddBrandDialog() {
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

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
        tfName.setTextFormatter(catalogNameFormatter());
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
            auditCatalogAction("ADD_BRAND", "BRAND", name, null, name, "Tipo: " + type.getName());
            EquipmentType selType = listTypes.getSelectionModel().getSelectedItem();
            if (selType != null && selType.getId() == type.getId())
                refreshBrandsForType(type.getId());
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = DialogChrome.buildDialogRoot(380, "#1a1a1a");
        root.getChildren().addAll(lblTitle,
            new VBox(2, buildFieldHeaderRow(lblT, lblErrorType), cmbType),
            new VBox(2, buildFieldHeaderRow(lblN, lblErrorName), tfName), buttons);

        buildAndShow(stage, root, tfName);
    }

    private void openAddModelDialog() {
        if (!requireConcreteStockSede()) return;
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

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
        tfName.setTextFormatter(catalogNameFormatter());
        tfName.setPromptText("Ej: ThinkBook 16 G8"); tfName.getStyleClass().add("form-input-main");

        Label lblS = new Label("STOCK INICIAL"); lblS.getStyleClass().add("input-label-small");
        TextField tfStock = new TextField("0");
        tfStock.getStyleClass().add("form-input-main");
        tfStock.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().matches("\\d{0,9}") ? change : null));

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
            auditCatalogAction("ADD_MODEL", "MODEL", name, null, name,
                "Tipo: " + type.getName() + ", Marca: " + brand.getName());
            // addModel() doesn't return the new/reactivated row's id — resolve it the same way
            // the catalog itself would (name match within this brand+type scope) rather than
            // guessing at what id it landed on.
            equipmentService.getModelsForBrandAndType(brand.getId(), type.getId()).stream()
                .filter(m -> m.getName().equalsIgnoreCase(name))
                .mapToInt(EquipmentModel::getId)
                .findFirst()
                .ifPresent(newModelId -> {
                    int stock = tfStock.getText().isBlank() ? 0 : Integer.parseInt(tfStock.getText().trim());
                    equipmentService.setModelStock(newModelId, brand.getId(), type.getId(), currentStockSedeId, stock);
                });
            EquipmentType  selT = listTypes.getSelectionModel().getSelectedItem();
            EquipmentBrand selB = listBrands.getSelectionModel().getSelectedItem();
            if (selT != null && selB != null
                && selT.getId() == type.getId() && selB.getId() == brand.getId())
                refreshModelsForBrandType(brand.getId(), type.getId());
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = DialogChrome.buildDialogRoot(400, "#1a1a1a");
        root.getChildren().addAll(lblTitle,
            new VBox(2, buildFieldHeaderRow(lblT, lblErrorType), cmbType),
            new VBox(2, buildFieldHeaderRow(lblB, lblErrorBrand), cmbBrand),
            new VBox(2, buildFieldHeaderRow(lblN, lblErrorName), tfName),
            new VBox(2, lblS, tfStock), buttons);

        buildAndShow(stage, root, tfName);
    }

    // Model is the only catalog entity whose edit dialog needs more than a rename — Stock is
    // scoped to this (Type,Brand) usage, not the model row alone, so it's edited here rather
    // than through the shared openRenameDialog().
    private void openEditModelDialog(EquipmentModel model, int brandId, int typeId) {
        if (!requireConcreteStockSede()) return;
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

        Label lblTitle = new Label("Editar modelo");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblErrorName = buildErrorLabel();
        TextField tfName = new TextField(model.getName());
        tfName.setTextFormatter(catalogNameFormatter());
        tfName.getStyleClass().add("form-input-main");

        int oldStock = equipmentService.getModelStock(model.getId(), brandId, typeId, currentStockSedeId);

        Label lblS = new Label("STOCK"); lblS.getStyleClass().add("input-label-small");
        TextField tfStock = new TextField(String.valueOf(oldStock));
        tfStock.getStyleClass().add("form-input-main");
        tfStock.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().matches("\\d{0,9}") ? change : null));

        Label lblR = new Label("MOTIVO DEL CAMBIO DE STOCK"); lblR.getStyleClass().add("input-label-small");
        Label lblErrorReason = buildErrorLabel();
        TextField tfReason = new TextField();
        tfReason.setPromptText("Ej: Reposición de stock, equipo dado de baja...");
        tfReason.getStyleClass().add("form-input-main");
        tfReason.setTextFormatter(stockReasonFormatter());

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Guardar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            String newName = tfName.getText().trim();
            if (newName.isEmpty()) {
                triggerFieldError(lblErrorName, "El nombre no puede estar vacío");
                return;
            }
            int newStock = tfStock.getText().isBlank() ? 0 : Integer.parseInt(tfStock.getText().trim());
            String reason = tfReason.getText().trim();
            if (newStock != oldStock && reason.isEmpty()) {
                triggerFieldError(lblErrorReason, "Debe indicar el motivo del cambio de stock");
                return;
            }
            if (!newName.equals(model.getName())) {
                try {
                    equipmentService.renameModel(model.getId(), newName);
                } catch (Exception ex) {
                    triggerFieldError(lblErrorName, ex.getMessage());
                    return;
                }
                auditCatalogAction("RENAME_MODEL", "MODEL", String.valueOf(model.getId()), model.getName(), newName);
            }
            // A rename swaps to a different MODEL row id (renameModel() deprecates the old one
            // and creates/reactivates a replacement) — resolve the current id fresh rather than
            // reusing model.getId(), or the stock below would land on the now-deprecated row.
            int targetModelId = equipmentService.getModelsForBrandAndType(brandId, typeId).stream()
                .filter(m -> m.getName().equalsIgnoreCase(newName))
                .mapToInt(EquipmentModel::getId)
                .findFirst()
                .orElse(model.getId());
            equipmentService.setModelStock(targetModelId, brandId, typeId, currentStockSedeId, newStock);
            if (newStock != oldStock) {
                ServiceLocator.getInstance().getAuditService().recordStockChange(brandId, typeId, targetModelId,
                    currentStockSedeId, TechnicianSessionService.getInstance().getUsername(), oldStock, newStock, reason);
            }
            refreshModelsForBrandType(brandId, typeId);
            refreshStockRollupsOnly(typeId);
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = DialogChrome.buildDialogRoot(380, "#1a1a1a");
        root.getChildren().addAll(lblTitle,
            new VBox(2, buildFieldHeaderRow(lblN, lblErrorName), tfName),
            new VBox(2, lblS, tfStock),
            new VBox(2, buildFieldHeaderRow(lblR, lblErrorReason), tfReason), buttons);

        buildAndShow(stage, root, tfName);
    }

    // Stock-only dialog — a faster path than openEditModelDialog() above when the name isn't
    // changing, reached via the Models list's own "Stock" button.
    private void openModifyStockDialog(EquipmentModel model, int brandId, int typeId) {
        if (!requireConcreteStockSede()) return;
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

        Label lblTitle = new Label("Modificar stock");
        lblTitle.getStyleClass().add("section-label");

        Label lblPath = new Label(model.getName());
        lblPath.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        int oldStock = equipmentService.getModelStock(model.getId(), brandId, typeId, currentStockSedeId);

        Label lblS = new Label("STOCK"); lblS.getStyleClass().add("input-label-small");
        TextField tfStock = new TextField(String.valueOf(oldStock));
        tfStock.getStyleClass().add("form-input-main");
        tfStock.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().matches("\\d{0,9}") ? change : null));

        Label lblR = new Label("MOTIVO DEL CAMBIO"); lblR.getStyleClass().add("input-label-small");
        Label lblErrorReason = buildErrorLabel();
        TextField tfReason = new TextField();
        tfReason.setPromptText("Ej: Reposición de stock, equipo dado de baja...");
        tfReason.getStyleClass().add("form-input-main");
        tfReason.setTextFormatter(stockReasonFormatter());

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Guardar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            int newStock = tfStock.getText().isBlank() ? 0 : Integer.parseInt(tfStock.getText().trim());
            String reason = tfReason.getText().trim();
            if (newStock != oldStock && reason.isEmpty()) {
                triggerFieldError(lblErrorReason, "Debe indicar el motivo del cambio de stock");
                return;
            }
            equipmentService.setModelStock(model.getId(), brandId, typeId, currentStockSedeId, newStock);
            if (newStock != oldStock) {
                ServiceLocator.getInstance().getAuditService().recordStockChange(brandId, typeId, model.getId(),
                    currentStockSedeId, TechnicianSessionService.getInstance().getUsername(), oldStock, newStock, reason);
            }
            refreshModelsForBrandType(brandId, typeId);
            refreshStockRollupsOnly(typeId);
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = DialogChrome.buildDialogRoot(340, "#1a1a1a");
        root.getChildren().addAll(lblTitle, lblPath, new VBox(2, lblS, tfStock),
            new VBox(2, buildFieldHeaderRow(lblR, lblErrorReason), tfReason), buttons);

        buildAndShow(stage, root, tfStock);
    }

    private static final int STOCK_REASON_MAX_LENGTH = 500;

    // Shared within this file only (not duplicated across controllers, since only this file's two
    // stock dialogs need it) — matches AUDIT_STOCK.reason's NVARCHAR(500) bound.
    private TextFormatter<String> stockReasonFormatter() {
        return new TextFormatter<>(change ->
            change.getControlNewText().length() <= STOCK_REASON_MAX_LENGTH ? change : null);
    }

    // Shared within this file only — every catalog CRUD action (Type/Brand/Model add/rename/
    // remove) funnels through here so the 9 call sites stay one-liners instead of repeating
    // ServiceLocator/TechnicianSessionService lookups each time.
    private void auditCatalogAction(String action, String targetType, String targetId,
            String oldValue, String newValue) {
        auditCatalogAction(action, targetType, targetId, oldValue, newValue, null);
    }

    private void auditCatalogAction(String action, String targetType, String targetId,
            String oldValue, String newValue, String reason) {
        ServiceLocator.getInstance().getAuditService().recordAdminAction(
            TechnicianSessionService.getInstance().getUsername(), action, targetType, targetId,
            oldValue, newValue, reason);
    }

    private void openRenameDialog(String currentName, java.util.function.Consumer<String> onSave) {
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

        Label lblTitle = new Label("Renombrar");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NUEVO NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblError = buildErrorLabel();
        TextField tfName = new TextField(currentName);
        tfName.setTextFormatter(catalogNameFormatter());
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

        VBox root = DialogChrome.buildDialogRoot(360, "#1a1a1a");
        root.getChildren().addAll(lblTitle, new VBox(2, buildFieldHeaderRow(lblN, lblError), tfName), buttons);

        Scene scene = DialogChrome.buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        Platform.runLater(() -> { tfName.requestFocus(); tfName.selectAll(); });
        stage.showAndWait();
    }

    private boolean confirmDelete(String itemName) {
        boolean[] confirmed = {false};
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

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

        VBox root = DialogChrome.buildDialogRoot(380, "#1a1a1a");
        root.getChildren().addAll(lblTitle, lblMsg, buttons);

        Scene scene = DialogChrome.buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();

        return confirmed[0];
    }

    private boolean confirmSaveDespiteFailedTest() {
        boolean[] confirmed = {false};
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

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

        VBox root = DialogChrome.buildDialogRoot(380, "#1a1a1a");
        root.getChildren().addAll(lblTitle, lblMsg, buttons);

        Scene scene = DialogChrome.buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();

        return confirmed[0];
    }

    private void buildAndShow(Stage stage, VBox root, TextField focusTarget) {
        Scene scene = DialogChrome.buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        Platform.runLater(focusTarget::requestFocus);
        stage.showAndWait();
    }

    // ── Admin auth ────────────────────────────────────────────────────

    // Hard permission check, no fallback. The corresponding buttons are already hidden when this
    // would fail; this check is what actually enforces it, not the button's visibility.
    private void requirePermission(Permission permission, Runnable action) {
        if (!AdminSession.getInstance().hasPermission(permission)) {
            showErrorDialog("Acceso restringido", "No tiene permisos para realizar esta acción.");
            return;
        }
        AdminSession.getInstance().refreshActivity();
        action.run();
    }

    // Sede-scoped variant, for MANAGE_STOCK — AdminSession.hasPermission(Permission, Integer)
    // already encodes the full rule (SUPERADMIN bypasses Sede scoping; a real ADMIN login must
    // have their own assigned Sede match sedeId), so there's nothing left to check here beyond
    // the permission itself.
    private void requirePermission(Permission permission, Integer sedeId, Runnable action) {
        if (!AdminSession.getInstance().hasPermission(permission, sedeId)) {
            showErrorDialog("Acceso restringido", "No tiene permisos para realizar esta acción.");
            return;
        }
        AdminSession.getInstance().refreshActivity();
        action.run();
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
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

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

        VBox root = DialogChrome.buildDialogRoot(360, "#1a1a1a");
        root.getChildren().addAll(lblTitle, lblMsg, buttons);

        Scene scene = DialogChrome.buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();
    }

    // Dialog helpers (buildDialogStage/buildDialogRoot/buildDialogScene/centerOnContent) live in
    // utils.core.DialogChrome, shared across every controller that opens a dialog.
}
