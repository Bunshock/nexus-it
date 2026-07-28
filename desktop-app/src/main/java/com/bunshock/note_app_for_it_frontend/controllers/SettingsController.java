package com.bunshock.note_app_for_it_frontend.controllers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.bunshock.note_app_for_it_frontend.models.AppConfig;
import com.bunshock.note_app_for_it_frontend.models.Sede;
import com.bunshock.note_app_for_it_frontend.models.SnValidationRow;
import com.bunshock.note_app_for_it_frontend.services.AdApiService;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.AppKeyEncryptionService;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.DatabaseService;
import com.bunshock.note_app_for_it_frontend.services.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.stage.StageStyle;

public class SettingsController {

    // ── Settings panel fields ─────────────────────────────────────────
    @FXML private VBox panelSettings;
    @FXML private VBox panelSnValidation;

    // Not admin-gated — any technician sets their own Sede (see updateFieldEditability()).
    // Catalog-backed (added 2026-07-24) — strict selection from the admin-curated SEDE list,
    // same non-editable pattern as ProviderNoteController.cmbProviderSearch. Saved through the
    // shared "Guardar Configuración" button/handleSave() below, not a dedicated button anymore.
    @FXML private ComboBox<Sede> cmbSede;

    @FXML private TextField txtAfPrefix;
    @FXML private TextField txtAfSeparator;
    @FXML private TextField txtAfLength;
    @FXML private TextField txtAfFiller;
    @FXML private Label     lblAfPreview;

    @FXML private TextField   txtSmtpSender;
    @FXML private PasswordField pfSmtpPassword;

    @FXML private TextField     txtGlpiUrl;
    @FXML private PasswordField pfGlpiApiKey;

    @FXML private TextField     txtAdUrl;
    @FXML private PasswordField pfAdApiToken;

    @FXML private Button btnSave;
    @FXML private Label  lblSaveStatus;

    // ── S/N validation panel fields ───────────────────────────────────
    @FXML private MenuButton                             mnuSnType;
    @FXML private MenuButton                             mnuSnBrand;
    @FXML private MenuButton                             mnuSnModel;
    @FXML private MenuButton                             mnuSnActive;
    @FXML private TableView<SnValidationRow>             tblSnValidation;
    @FXML private TableColumn<SnValidationRow, String>   colSnType;
    @FXML private TableColumn<SnValidationRow, String>   colSnBrand;
    @FXML private TableColumn<SnValidationRow, String>   colSnModel;
    @FXML private TableColumn<SnValidationRow, String>   colSnRegex;
    @FXML private TableColumn<SnValidationRow, Boolean>  colSnActive;
    @FXML private TableColumn<SnValidationRow, Void>     colSnEdit;

    private static final List<String> SN_ACTIVE_OPTIONS = List.of("Sí", "No");
    private static final int SN_REGEX_MAX_LENGTH = 500;

    private final Set<String> selSnTypes  = new LinkedHashSet<>();
    private final Set<String> selSnBrands = new LinkedHashSet<>();
    private final Set<String> selSnModels = new LinkedHashSet<>();
    private final Set<String> selSnActive = new LinkedHashSet<>();
    private boolean suppressSnCallbacks = false;

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
        txtAdUrl.setText(config.adApi.baseUrl);

        txtAfPrefix.textProperty().addListener((o, a, b) -> updateAfPreview());
        txtAfSeparator.textProperty().addListener((o, a, b) -> updateAfPreview());
        txtAfLength.textProperty().addListener((o, a, b) -> updateAfPreview());
        txtAfFiller.textProperty().addListener((o, a, b) -> updateAfPreview());
        updateAfPreview();

        setupSnTable();

        refreshSedeCombo();
        TechnicianSessionService.getInstance().addOnSedeChangeListener(this::preselectCurrentSede);

        AdminSession.getInstance().addOnActivateListener(this::onAdminStateChanged);
        AdminSession.getInstance().addOnDeactivateListener(this::onAdminStateChanged);
        onAdminStateChanged();
    }

    private void onAdminStateChanged() {
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
        txtAdUrl.setDisable(!adminActive);
        pfAdApiToken.setDisable(!adminActive);
        // btnSave itself is never disabled by admin state — Sede (not admin-gated) is saved
        // through the same button, see handleSave().
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

    // ── Sede (per-technician, saved by the shared "Guardar Configuración" button below) ─────

    // Repopulates the combo from the catalog and reselects whatever the technician currently
    // has saved — called at init, whenever Base de Datos' Sede list changes underneath this
    // (session-cached view, same "stale catalog" family of bug already fixed elsewhere for
    // ProviderNoteController.refreshProviders()), and every time the Settings section is shown
    // again (MainController.handleShowSettings(), mirroring HistoryController.refresh()'s
    // precedent) — so switching away after picking a different Sede but not saving, then
    // switching back, shows the last *saved* value again rather than the abandoned selection.
    public void refreshSedeCombo() {
        cmbSede.setItems(FXCollections.observableArrayList(equipmentService.getAllSedes()));
        preselectCurrentSede();
    }

    private void preselectCurrentSede() {
        Integer sedeId = TechnicianSessionService.getInstance().getSedeId();
        if (sedeId == null) {
            cmbSede.setValue(null);
            return;
        }
        cmbSede.getItems().stream()
            .filter(s -> s.getId() == sedeId)
            .findFirst()
            .ifPresentOrElse(cmbSede::setValue, () -> cmbSede.setValue(null));
    }

    // ── Save ──────────────────────────────────────────────────────────

    @FXML
    private void handleSave() {
        // Sede isn't admin-gated (any technician sets their own), so it's always saved here,
        // regardless of admin state — previously had its own dedicated "Guardar" button next to
        // the combo, merged into this one so a non-admin technician has a way to save it too.
        Sede selectedSede = cmbSede.getValue();
        TechnicianSessionService.getInstance().setSedePreference(
            selectedSede != null ? selectedSede.getId() : null,
            selectedSede != null ? selectedSede.getName() : null);

        if (!AdminSession.getInstance().isActive()) {
            triggerSaveStatus("Configuración guardada", "#0c8570");
            return;
        }

        AppConfig config = ConfigService.getInstance().getConfig();

        config.afFormat.prefix    = txtAfPrefix.getText().trim();
        config.afFormat.separator = txtAfSeparator.getText();
        config.afFormat.filler    = txtAfFiller.getText().isEmpty() ? "0" : txtAfFiller.getText().substring(0, 1);
        config.smtp.senderAddress = txtSmtpSender.getText().trim();
        config.glpiApi.baseUrl    = txtGlpiUrl.getText().trim();
        String adUrl   = txtAdUrl.getText().trim();
        String adToken = pfAdApiToken.getText();

        try {
            config.afFormat.length = Integer.parseInt(txtAfLength.getText().trim());
        } catch (NumberFormatException e) {
            triggerSaveStatus("La longitud de A/F debe ser un número", "#ef4444");
            return;
        }

        Runnable persist = () -> {
            config.adApi.baseUrl = adUrl;

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

            if (!adToken.isBlank()) {
                saveEncryptedSetting("ad_api_token", adToken);
                pfAdApiToken.clear();
            }

            String effectiveToken = !adToken.isBlank() ? adToken : decryptSetting("ad_api_token");
            AdApiService.getInstance().configure(adUrl.isBlank() ? null : adUrl, effectiveToken);

            try {
                ConfigService.getInstance().save();
                triggerSaveStatus("Configuración guardada", "#0c8570");
            } catch (Exception e) {
                triggerSaveStatus("Error al guardar la configuración", "#ef4444");
            }
        };

        if (adUrl.isEmpty()) {
            persist.run();
            return;
        }

        String tokenForTest = !adToken.isBlank() ? adToken : decryptSetting("ad_api_token");
        if (tokenForTest == null || tokenForTest.isBlank()) {
            persist.run();
            return;
        }

        btnSave.setDisable(true);
        String originalText = btnSave.getText();
        btnSave.setText("Probando...");

        Thread t = new Thread(() -> {
            String technicianUsername = TechnicianSessionService.getInstance().getUsername();
            boolean ok = AdApiService.getInstance().testConnection(adUrl, tokenForTest, technicianUsername);
            Platform.runLater(() -> {
                btnSave.setDisable(false);
                btnSave.setText(originalText);
                if (ok || confirmSaveDespiteFailedTest()) persist.run();
            });
        }, "ad-connection-test");
        t.setDaemon(true);
        t.start();
    }

    private boolean confirmSaveDespiteFailedTest() {
        boolean[] confirmed = {false};
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        Label lblTitle = new Label("No se pudo conectar");
        lblTitle.getStyleClass().add("section-label");

        Label lblMsg = new Label(
            "No se pudo establecer conexión con la API de Active Directory usando estos datos. "
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
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == javafx.scene.input.KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();

        return confirmed[0];
    }

    private String decryptSetting(String key) {
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT value FROM APP_SETTINGS WHERE key = ?")) {
            ps.setString(1, key);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String enc = rs.getString("value");
                return (enc == null || enc.isBlank()) ? null : AppKeyEncryptionService.getInstance().decrypt(enc);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void saveEncryptedSetting(String key, String plainValue) {
        String encrypted = AppKeyEncryptionService.getInstance().encrypt(plainValue);
        try (Connection c = DatabaseService.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO APP_SETTINGS (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, encrypted);
            ps.executeUpdate();
        } catch (Exception e) {
            triggerSaveStatus("Error al guardar la configuración", "#ef4444");
        }
    }

    private static final Duration SAVE_STATUS_HOLD = Duration.millis(2000);
    private static final Duration SAVE_STATUS_FADE = Duration.millis(650);

    // Mirrors DatabaseSectionController.triggerFieldError's hold-then-fade timing — previously
    // lblSaveStatus just set text/style directly with no animation at all, so the confirmation
    // (or error) sat on screen indefinitely instead of fading like every other status message.
    private void triggerSaveStatus(String message, String hexColor) {
        if (lblSaveStatus.getUserData() instanceof FadeTransition previous) previous.stop();

        lblSaveStatus.setText(message);
        lblSaveStatus.setStyle("-fx-text-fill: " + hexColor + ";");
        lblSaveStatus.setOpacity(1.0);

        FadeTransition fade = new FadeTransition(SAVE_STATUS_FADE, lblSaveStatus);
        fade.setDelay(SAVE_STATUS_HOLD);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            lblSaveStatus.setText("");
            lblSaveStatus.setStyle("");
            lblSaveStatus.setOpacity(1.0);
            lblSaveStatus.setUserData(null);
        });
        lblSaveStatus.setUserData(fade);
        fade.play();
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

    }

    private void loadSnValidationData() {
        List<SnValidationRow> rows = equipmentService.getAllSnValidationRows();
        allSnRows = FXCollections.observableArrayList(rows);
        filteredSnRows = new FilteredList<>(allSnRows, r -> true);

        Comparator<SnValidationRow> order = Comparator
            .<SnValidationRow, Boolean>comparing(r -> !r.isActive())
            .thenComparing(SnValidationRow::getTypeName)
            .thenComparing(SnValidationRow::getBrandName)
            .thenComparing(SnValidationRow::getModelName);

        SortedList<SnValidationRow> sorted = new SortedList<>(filteredSnRows, order);
        tblSnValidation.setItems(sorted);

        refreshSnFilterMenus();
    }

    // ── S/N validation filter menus ───────────────────────────────────

    @FXML
    private void handleClearSnFilters() {
        suppressSnCallbacks = true;
        selSnTypes.clear();
        selSnBrands.clear();
        selSnModels.clear();
        selSnActive.clear();
        suppressSnCallbacks = false;
        refreshSnFilterMenus();
    }

    private void onSnTypeChanged() {
        selSnBrands.clear();
        selSnModels.clear();
        refreshSnFilterMenus();
    }

    private void onSnBrandChanged() {
        selSnModels.clear();
        refreshSnFilterMenus();
    }

    // Rebuilds all four menus from the current allSnRows + selection sets — used both for a
    // fresh load/edit-save reload (selections untouched, so filters survive editing a row) and
    // after a cascade reset (caller clears the downstream sets first). Brand options are scoped
    // to the selected Type(s), Model options to the selected Type(s) and Brand(s), mirroring
    // HistoryController's item-type/brand/model cascade.
    private void refreshSnFilterMenus() {
        List<String> types = distinctSnValues(r -> true, SnValidationRow::getTypeName);
        populateSnMenu(mnuSnType, types, selSnTypes, this::onSnTypeChanged);

        List<String> brands = distinctSnValues(
            r -> selSnTypes.isEmpty() || selSnTypes.contains(r.getTypeName()),
            SnValidationRow::getBrandName);
        populateSnMenu(mnuSnBrand, brands, selSnBrands, this::onSnBrandChanged);

        List<String> models = distinctSnValues(
            r -> (selSnTypes.isEmpty() || selSnTypes.contains(r.getTypeName()))
                && (selSnBrands.isEmpty() || selSnBrands.contains(r.getBrandName())),
            SnValidationRow::getModelName);
        populateSnMenu(mnuSnModel, models, selSnModels, this::applySnFilter);

        populateSnMenu(mnuSnActive, SN_ACTIVE_OPTIONS, selSnActive, this::applySnFilter);

        applySnFilter();
    }

    private List<String> distinctSnValues(Predicate<SnValidationRow> include,
            java.util.function.Function<SnValidationRow, String> nameFn) {
        return allSnRows.stream()
            .filter(include)
            .map(nameFn)
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private void applySnFilter() {
        if (filteredSnRows == null) return;
        filteredSnRows.setPredicate(r ->
            (selSnTypes.isEmpty()  || selSnTypes.contains(r.getTypeName()))
            && (selSnBrands.isEmpty() || selSnBrands.contains(r.getBrandName()))
            && (selSnModels.isEmpty() || selSnModels.contains(r.getModelName()))
            && (selSnActive.isEmpty() || selSnActive.contains(r.isActive() ? "Sí" : "No")));
    }

    // Duplicated from HistoryController's populateMenu/updateMenuLabel (multi-select checkbox
    // MenuButton with a "Todas" select-all item) per this codebase's no-shared-abstraction rule.
    private void populateSnMenu(MenuButton btn, List<String> options, Set<String> selected, Runnable onChange) {
        btn.getItems().clear();

        CheckBox todasChk = new CheckBox("Todas");
        todasChk.setSelected(selected.isEmpty());
        todasChk.getStyleClass().add("menu-filter-checkbox");
        btn.getItems().add(new CustomMenuItem(todasChk, false));
        btn.getItems().add(new SeparatorMenuItem());

        boolean[] lock = {false};

        for (String opt : options) {
            CheckBox chk = new CheckBox(opt);
            chk.setSelected(selected.contains(opt));
            chk.getStyleClass().add("menu-filter-checkbox");
            btn.getItems().add(new CustomMenuItem(chk, false));

            chk.selectedProperty().addListener((obs, old, newVal) -> {
                if (lock[0] || suppressSnCallbacks) return;
                lock[0] = true;
                if (newVal) {
                    selected.add(opt);
                    todasChk.setSelected(false);
                } else {
                    selected.remove(opt);
                    if (selected.isEmpty()) todasChk.setSelected(true);
                }
                lock[0] = false;
                updateSnMenuLabel(btn, selected);
                if (!suppressSnCallbacks) onChange.run();
            });
        }

        todasChk.selectedProperty().addListener((obs, old, newVal) -> {
            if (lock[0] || suppressSnCallbacks || !newVal) return;
            lock[0] = true;
            selected.clear();
            btn.getItems().stream()
                .filter(it -> it instanceof CustomMenuItem)
                .map(it -> ((CustomMenuItem) it).getContent())
                .filter(n -> n instanceof CheckBox && n != todasChk)
                .forEach(n -> ((CheckBox) n).setSelected(false));
            lock[0] = false;
            updateSnMenuLabel(btn, selected);
            if (!suppressSnCallbacks) onChange.run();
        });

        updateSnMenuLabel(btn, selected);
    }

    private void updateSnMenuLabel(MenuButton btn, Set<String> selected) {
        if (selected.isEmpty()) {
            btn.setText("Todas");
        } else if (selected.size() == 1) {
            btn.setText(selected.iterator().next());
        } else {
            btn.setText(selected.size() + " seleccionados");
        }
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
        tfRegex.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= SN_REGEX_MAX_LENGTH ? change : null));

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
