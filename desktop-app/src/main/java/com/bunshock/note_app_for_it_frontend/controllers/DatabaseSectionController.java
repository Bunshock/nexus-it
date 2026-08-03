package com.bunshock.note_app_for_it_frontend.controllers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.bunshock.note_app_for_it_frontend.models.AppConfig;
import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.Permission;
import com.bunshock.note_app_for_it_frontend.models.Sede;
import com.bunshock.note_app_for_it_frontend.services.AdminAuthService;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.AppKeyEncryptionService;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.DatabaseService;
import com.bunshock.note_app_for_it_frontend.services.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.IUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.RemoteDatabaseService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;

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
    @FXML private ComboBox<Sede> cmbStockSede;
    @FXML private Label lblEquipmentCatalogTitle;

    private IEquipmentService equipmentService;

    // Stock is now tracked per Sede (see IEquipmentService.getModelStock/setModelStock) — null
    // means "every Sede combined" (summed), only ever selectable by a SUPERADMIN; a plain
    // ADMIN/USER is locked to their own superadmin-assigned Sede, mirroring the same
    // ADMIN-own-Sede/SUPERADMIN-sees-all split already used for note approval/GLPI/return
    // permissions (AdminSession.hasPermission(Permission, Integer)).
    private Integer currentStockSedeId;

    // Guards listTypes'/listBrands' own selectedItemProperty listeners (below) while
    // refreshStockRollupsOnly() re-selects a Type/Brand by id after resorting — that re-selection
    // targets a freshly-queried object (a different instance than whatever was selected before,
    // even for "the same" Type/Brand), which would otherwise register as a real selection change
    // and cascade into refreshBrandsForType()/refreshModelsForBrandType(), wiping the very
    // listModels state that method exists to preserve during a stock edit.
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
    // Not admin-gated (explicit user decision) — any technician can point
    // their own local install at a different remote database. db_host/port/name/username/
    // password all live in local APP_SETTINGS only (never synced), so this only ever affects
    // the machine it's changed on, and the existing test-connection-before-accepting flow below
    // still guards against silently saving an unreachable/wrong config either way.
    private void handleEditConnection() {
        openEditConnectionDialog();
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

        // Write-only, like every other secret field in this app (SMTP/GLPI/AD) — never
        // pre-filled with the decrypted current value. Removing the admin gate on this dialog
        // means any technician can open it now, and a PasswordField's masked text can still be
        // selected/copied in plain text, so pre-filling it here would hand out the real shared
        // DB password to anyone who opens this screen.
        Label lblPw = new Label("CONTRASEÑA");
        lblPw.getStyleClass().add("input-label-small");
        Label lblPwError = buildErrorLabel();
        PasswordField pfPass = new PasswordField();
        pfPass.setPromptText("Dejar en blanco para no cambiarla");
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
            // Blank means "keep the existing password" — write-only field, see above — but
            // ONLY when the destination itself (host/port) is unchanged. Reusing the stored
            // password against a genuinely different host would submit the real, live
            // credential to wherever the field was just pointed at — via the test-connection
            // call below, before anything is even saved — letting anyone who can type a new
            // host effectively exfiltrate the password to a server of their choosing without
            // ever needing to read it back. Changing just the database name or username against
            // the SAME already-trusted host is fine and still allowed with a blank password.
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
            new VBox(2, buildFieldHeaderRow(lblPw, lblPwError), pfPass),
            new Separator(), buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        Platform.runLater(tfHost::requestFocus);
        stage.showAndWait();
    }

    // ── Equipment catalog ────────────────────────────────────────────

    private void refreshTypes() {
        Map<Integer, Integer> stockByType = equipmentService.getStockTotalsByType(currentStockSedeId);
        List<EquipmentType> types = sortStockFirstThenAlphabetical(
            equipmentService.getAllTypes(), t -> stockByType.getOrDefault(t.getId(), 0), EquipmentType::getName);
        listTypes.setItems(FXCollections.observableArrayList(types));
        applyCatalogCellFactory(listTypes, t -> stockByType.getOrDefault(t.getId(), 0));
        listBrands.setItems(FXCollections.observableArrayList());
        listModels.setItems(FXCollections.observableArrayList());
    }

    // The global generic brand isn't linked to every type (it doesn't need to be — see
    // SqliteEquipmentService.addBrandForType()), so it's synthesized into the list here exactly
    // like ItemDialogController.onTypeSelected() already does for the note-generation Item
    // dialog — otherwise it would only appear for whichever type(s) happen to have a real,
    // now-vestigial BRAND_TYPE_LINK, and be missing everywhere else. Its own final position
    // (last within its stock group) comes from sortStockFirstThenAlphabetical() below, not from
    // where it's inserted here.
    private void refreshBrandsForType(int typeId) {
        List<EquipmentBrand> brands = new ArrayList<>(equipmentService.getBrandsForType(typeId));
        if (brands.stream().noneMatch(b -> genericLabel().equals(b.getName()))) {
            equipmentService.getAllBrands().stream()
                .filter(b -> genericLabel().equals(b.getName()))
                .findFirst()
                .ifPresent(brands::add);
        }
        Map<Integer, Integer> stockByBrand = equipmentService.getStockTotalsByBrandForType(typeId, currentStockSedeId);
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
        Map<Integer, Integer> stockByModel =
            equipmentService.getStockTotalsByModelForBrandAndType(brandId, typeId, currentStockSedeId);
        List<EquipmentModel> sorted = sortStockFirstThenAlphabetical(
            models, m -> stockByModel.getOrDefault(m.getId(), 0), EquipmentModel::getName);
        listModels.setItems(FXCollections.observableArrayList(sorted));
        applyCatalogCellFactory(listModels, m -> stockByModel.getOrDefault(m.getId(), 0));
    }

    // Direct user request: every cascading list shows every item with stock > 0 first, then
    // every item with 0 stock, each group sorted alphabetically (case-insensitive) — not one
    // flat alphabetical list. Within each of those two groups, the synthesized "Genérico / Otro"
    // fallback (Brand/Model lists only — genericLabel() never matches a real Type name, so this
    // is a no-op there) sorts last, per its own pre-existing "generic sits last" convention —
    // scoped to its own stock group now, confirmed with the user, rather than globally last
    // across both groups like before.
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

    // A stock edit changes the Type- and Brand-level rollup totals too (they sum every Model
    // under their scope), which can move a Type/Brand between the has-stock/zero-stock groups —
    // direct user report that the lists weren't reordering after a stock edit. Re-sorts and
    // rebuilds listTypes'/listModels' items (unlike a plain applyCatalogCellFactory() repaint),
    // but explicitly does NOT touch listModels — that list was already just fully rebuilt by
    // refreshModelsForBrandType(), called right before this in every caller, and this method has
    // no reason to touch it a second time.
    //
    // Selection is preserved by id, not by object identity: equipmentService.getAllTypes()/
    // getBrandsForType() return freshly-queried objects each call, so the Type/Brand the
    // technician had selected before this runs is never the same instance as its post-sort
    // replacement, even when nothing about it actually changed. Re-selecting it explicitly (by
    // matching id in the newly-sorted list) is done under suppressSelectionListeners — selecting
    // a different instance still fires listTypes'/listBrands' own selectedItemProperty listeners,
    // which would otherwise cascade into refreshBrandsForType()/refreshModelsForBrandType() and
    // wipe out listModels' state, defeating the whole point of doing this instead of just calling
    // refreshTypes()/refreshBrandsForType() again.
    private void refreshStockRollupsOnly(int typeId) {
        EquipmentType selectedType = listTypes.getSelectionModel().getSelectedItem();
        EquipmentBrand selectedBrand = listBrands.getSelectionModel().getSelectedItem();

        suppressSelectionListeners = true;
        try {
            Map<Integer, Integer> stockByType = equipmentService.getStockTotalsByType(currentStockSedeId);
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
            Map<Integer, Integer> stockByBrand = equipmentService.getStockTotalsByBrandForType(typeId, currentStockSedeId);
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

    // Two-column row (name left, stock right-aligned) instead of a single text string — lines up
    // under the "NOMBRE"/"STOCK" header row each list gets in FXML, which (being a plain sibling
    // above the ListView, not inside its scrollable viewport) stays fixed while the list scrolls.
    // Stock comes from the already-fetched rollup Map (one query per list refresh, not one per
    // row) — applied to all three cascading lists (Types/Brands/Models), each with its own
    // id-to-stock lookup. Same italic/grey treatment ItemDialogController's combo boxes give the
    // generic fallback; selected-row text color is handled manually (matching
    // .modern-list .list-cell:filled:selected) since CSS text-fill on the cell itself doesn't
    // reach into a custom graphic's child Labels.
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
                // A ListCell's graphic isn't stretched to the cell's own width by default (unlike
                // a plain HBox living directly in a VBox, which IS stretched via VBox's own
                // fillWidth=true default — that's why the FXML header row lines up on its own).
                // Without this, the spacer has no extra space to grow into, and lblStock ends up
                // sitting immediately after lblName instead of pinned to the row's right edge —
                // matching HistoryView's ".modern-list .list-cell" padding (7 12, i.e. 24px total
                // horizontal) so the bound width matches the cell's actual content area.
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
                // Stock's color is driven by its own value (black = in stock, red = none),
                // independent of selection/generic state, and always bold so it reads as a number
                // at a glance rather than plain label text. Was green (#22c55e) for in-stock, but
                // that blended into the selected-row background (.modern-list's #6ebdb0 teal) —
                // low contrast, hard to read — direct user report with a screenshot. Black reads
                // clearly against both the white unselected background and the teal selected one;
                // red (out of stock) already had enough contrast against both and was left as-is.
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

    // A plain ADMIN/USER is locked to their own superadmin-assigned Sede (no way to view or edit
    // another Sede's stock from here) — the title row already names that Sede, so the combo box
    // is hidden entirely rather than shown disabled. A SUPERADMIN gets the full list plus a
    // leading null entry meaning "Todas" (every Sede combined/summed), defaulting to it —
    // confirmed with the user rather than assumed, since "Todas" isn't itself an editable target
    // (see requireConcreteStockSede()).
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
            cmbStockSede.setVisible(true);
        } else {
            Integer mySedeId = TechnicianSessionService.getInstance().getSedeId();
            currentStockSedeId = mySedeId;
            if (mySedeId != null) {
                equipmentService.getAllSedes().stream()
                    .filter(s -> s.getId() == mySedeId)
                    .findFirst()
                    .ifPresent(s -> cmbStockSede.setItems(FXCollections.observableArrayList(s)));
                cmbStockSede.getSelectionModel().selectFirst();
            }
            // visible=false only (not managed=false) — keeps the combo's layout space reserved
            // so the title row's height matches the SUPERADMIN case exactly, instead of
            // collapsing down to just the Label.
            cmbStockSede.setVisible(false);
        }
        cmbStockSede.valueProperty().addListener((obs, old, sel) -> {
            currentStockSedeId = sel == null ? null : sel.getId();
            refreshTypes();
            updateEquipmentCatalogTitle();
        });
        updateEquipmentCatalogTitle();
    }

    private static final String EQUIPMENT_CATALOG_BASE_LABEL = "CATÁLOGO DE EQUIPOS";

    // Names the Sede the shown stock numbers belong to, so a technician can't mistake one Sede's
    // counts for another's. All caps, matching every other section title in this app.
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
        lblEquipmentCatalogTitle.setText((EQUIPMENT_CATALOG_BASE_LABEL + " : " + sedeLabel).toUpperCase());
    }

    // Editing a stock number always requires one concrete Sede — a combined "Todas" number has
    // no single row to write to. Called at the top of every stock-writing dialog (Add/Edit
    // Model, Modify Stock) so a SUPERADMIN viewing "Todas" is asked to pick a specific Sede from
    // cmbStockSede first, rather than silently writing to an arbitrary one.
    private boolean requireConcreteStockSede() {
        if (currentStockSedeId != null) return true;
        showErrorDialog("Seleccione una sede",
            "Seleccione una sede específica (no \"Todas\") en el selector de Stock — Sede para modificar el stock.");
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

    // Quick, stock-only alternative to "Editar" (which also lets you rename) — added per direct
    // user request for a faster path when only the quantity needs to change. Its own permission
    // since a future role might adjust stock without full model-management rights. Sede-scoped
    // (unlike MANAGE_MODELS above, which governs catalog structure, not any one Sede's numbers):
    // an ADMIN can only ever be viewing their own assigned Sede here anyway (see
    // initStockSedeSelector()), and SUPERADMIN bypasses Sede-scoping entirely, so this only
    // actually changes behavior for the shared-password fallback path.
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
            try { equipmentService.removeType(sel.getId()); refreshTypes(); }
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
                EquipmentType  type  = listTypes.getSelectionModel().getSelectedItem();
                EquipmentBrand brand = listBrands.getSelectionModel().getSelectedItem();
                if (type != null && brand != null) refreshModelsForBrandType(brand.getId(), type.getId());
            } catch (Exception e) { showErrorDialog("Error al eliminar", e.getMessage()); }
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

    // Matches TYPE/BRAND/MODEL/PROVIDER/SEDE.name's NVARCHAR(255) bound on SQL Server —
    // every catalog name dialog's tfName field had no length cap of any kind
    // before this. One shared constant since every use is within this same class (unlike the
    // per-controller duplication convention used for fields shared *across* controllers).
    private static final int CATALOG_NAME_MAX_LENGTH = 255;

    private TextFormatter<String> catalogNameFormatter() {
        return new TextFormatter<>(change ->
            change.getControlNewText().length() <= CATALOG_NAME_MAX_LENGTH ? change : null);
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
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Nuevo tipo de equipo");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblError = buildErrorLabel();
        TextField tfName = new TextField();
        tfName.setTextFormatter(catalogNameFormatter());
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
        if (!requireConcreteStockSede()) return;
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

        VBox root = buildDialogRoot(400);
        root.getChildren().addAll(lblTitle,
            new VBox(2, buildFieldHeaderRow(lblT, lblErrorType), cmbType),
            new VBox(2, buildFieldHeaderRow(lblB, lblErrorBrand), cmbBrand),
            new VBox(2, buildFieldHeaderRow(lblN, lblErrorName), tfName),
            new VBox(2, lblS, tfStock), buttons);

        buildAndShow(stage, root, tfName);
    }

    // Model is the one catalog entity whose Base de Datos edit dialog needs more than a plain
    // rename — Stock (see IEquipmentService.setModelStock()) is scoped to this specific
    // (Type,Brand) usage, not the model row alone, so it's edited alongside the name here rather
    // than through the shared openRenameDialog() every other entity still uses. Same precedent as
    // openEditTypeDialog() getting its own dedicated dialog for the requires_serial flag.
    private void openEditModelDialog(EquipmentModel model, int brandId, int typeId) {
        if (!requireConcreteStockSede()) return;
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Editar modelo");
        lblTitle.getStyleClass().add("section-label");

        Label lblN = new Label("NOMBRE"); lblN.getStyleClass().add("input-label-small");
        Label lblErrorName = buildErrorLabel();
        TextField tfName = new TextField(model.getName());
        tfName.setTextFormatter(catalogNameFormatter());
        tfName.getStyleClass().add("form-input-main");

        Label lblS = new Label("STOCK"); lblS.getStyleClass().add("input-label-small");
        TextField tfStock = new TextField(
            String.valueOf(equipmentService.getModelStock(model.getId(), brandId, typeId, currentStockSedeId)));
        tfStock.getStyleClass().add("form-input-main");
        tfStock.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().matches("\\d{0,9}") ? change : null));

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
            if (!newName.equals(model.getName())) {
                try {
                    equipmentService.renameModel(model.getId(), newName);
                } catch (Exception ex) {
                    triggerFieldError(lblErrorName, ex.getMessage());
                    return;
                }
            }
            // A rename swaps to a different MODEL row id (renameModel() deprecates the old one
            // and creates/reactivates a replacement) — resolve the current id fresh rather than
            // reusing model.getId(), or the stock below would land on the now-deprecated row.
            int targetModelId = equipmentService.getModelsForBrandAndType(brandId, typeId).stream()
                .filter(m -> m.getName().equalsIgnoreCase(newName))
                .mapToInt(EquipmentModel::getId)
                .findFirst()
                .orElse(model.getId());
            int stock = tfStock.getText().isBlank() ? 0 : Integer.parseInt(tfStock.getText().trim());
            equipmentService.setModelStock(targetModelId, brandId, typeId, currentStockSedeId, stock);
            refreshModelsForBrandType(brandId, typeId);
            refreshStockRollupsOnly(typeId);
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(380);
        root.getChildren().addAll(lblTitle,
            new VBox(2, buildFieldHeaderRow(lblN, lblErrorName), tfName),
            new VBox(2, lblS, tfStock), buttons);

        buildAndShow(stage, root, tfName);
    }

    // Stock-only dialog — a faster path than openEditModelDialog() above when the name isn't
    // changing, reached via the Models list's own "Stock" button.
    private void openModifyStockDialog(EquipmentModel model, int brandId, int typeId) {
        if (!requireConcreteStockSede()) return;
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("Modificar stock");
        lblTitle.getStyleClass().add("section-label");

        Label lblPath = new Label(model.getName());
        lblPath.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        Label lblS = new Label("STOCK"); lblS.getStyleClass().add("input-label-small");
        TextField tfStock = new TextField(
            String.valueOf(equipmentService.getModelStock(model.getId(), brandId, typeId, currentStockSedeId)));
        tfStock.getStyleClass().add("form-input-main");
        tfStock.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().matches("\\d{0,9}") ? change : null));

        Button btnCancel = new Button("Cancelar");
        btnCancel.getStyleClass().add("button-secondary");
        btnCancel.setOnAction(e -> stage.close());

        Button btnSave = new Button("Guardar");
        btnSave.getStyleClass().add("button-primary");
        btnSave.setOnAction(e -> {
            int stock = tfStock.getText().isBlank() ? 0 : Integer.parseInt(tfStock.getText().trim());
            equipmentService.setModelStock(model.getId(), brandId, typeId, currentStockSedeId, stock);
            refreshModelsForBrandType(brandId, typeId);
            refreshStockRollupsOnly(typeId);
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(340);
        root.getChildren().addAll(lblTitle, lblPath, new VBox(2, lblS, tfStock), buttons);

        buildAndShow(stage, root, tfStock);
    }

    private void openRenameDialog(String currentName, java.util.function.Consumer<String> onSave) {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

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

    // The shared-password fallback below always resolves to ADMIN-level permissions, never
    // SUPERADMIN, regardless of who's holding the password — so a permission granted only to
    // SUPERADMIN (none of this controller's today, but a future one might be) stays unreachable
    // through this path even with the correct password.
    private void requirePermission(Permission permission, Runnable action) {
        if (AdminSession.getInstance().hasPermission(permission)) {
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
        if (!ServiceLocator.getInstance().getUserRoleService()
                .getPermissionsForRole(IUserRoleService.ROLE_ADMIN).contains(permission)) {
            showErrorDialog("Acceso denegado", "Esta acción requiere permisos de superadministrador.");
            return;
        }
        action.run();
    }

    // Sede-scoped variant, for MANAGE_STOCK — mirrors AdminSession.hasPermission(Permission,
    // Integer)'s own scoping rule (SUPERADMIN bypasses it; a real ADMIN login must have their own
    // assigned Sede match sedeId) for an already-active session. The shared-password fallback
    // below always resolves to ADMIN-level (never SUPERADMIN, see AdminSession.activate()), so it
    // must enforce the same Sede match explicitly here too — activate() itself has no sedeId to
    // check against, so this is the one place that rule is actually applied for that path.
    private void requirePermission(Permission permission, Integer sedeId, Runnable action) {
        if (AdminSession.getInstance().hasPermission(permission, sedeId)) {
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
        if (!ServiceLocator.getInstance().getUserRoleService()
                .getPermissionsForRole(IUserRoleService.ROLE_ADMIN).contains(permission)) {
            showErrorDialog("Acceso denegado", "Esta acción requiere permisos de superadministrador.");
            return;
        }
        Integer mySedeId = TechnicianSessionService.getInstance().getSedeId();
        if (sedeId == null || mySedeId == null || !mySedeId.equals(sedeId)) {
            showErrorDialog("Acceso denegado", "Esta acción requiere permisos sobre la sede seleccionada.");
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
