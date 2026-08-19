package com.bunshock.note_app_for_it_frontend.controllers.admin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.bunshock.note_app_for_it_frontend.models.core.AppConfig;
import com.bunshock.note_app_for_it_frontend.models.admin.Permission;
import com.bunshock.note_app_for_it_frontend.models.catalog.SnValidationRow;
import com.bunshock.note_app_for_it_frontend.services.auth.AdApiService;
import com.bunshock.note_app_for_it_frontend.services.auth.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.core.AppKeyEncryptionService;
import com.bunshock.note_app_for_it_frontend.services.core.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.core.DatabaseService;
import com.bunshock.note_app_for_it_frontend.services.catalog.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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

    @FXML private TextField txtAfPrefix;
    @FXML private TextField txtAfSeparator;
    @FXML private Label     lblAfPreview;

    @FXML private TextField   txtSmtpSender;
    @FXML private PasswordField pfSmtpPassword;

    @FXML private TextField     txtGlpiUrl;
    @FXML private PasswordField pfGlpiApiKey;

    @FXML private TextField     txtAdUrl;
    @FXML private PasswordField pfAdApiToken;

    @FXML private Button btnSave;
    @FXML private Label  lblSaveStatus;

    @FXML private org.controlsfx.control.ToggleSwitch toggleAutoClearForm;

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

    // None of these five are backed by a SQL Server column (app-config.json's afFormat.prefix/
    // separator, smtp.senderAddress, glpiApi.baseUrl, adApi.baseUrl; the two PasswordFields are
    // encrypted APP_SETTINGS values, local-only) — capped purely as a sanity guard against an
    // accidental huge paste, same reasoning already applied to SN_REGEX_MAX_LENGTH above.
    private static final int AF_FORMAT_MAX_LENGTH = 20;
    private static final int URL_MAX_LENGTH = 255;
    private static final int SMTP_SENDER_MAX_LENGTH = 255;
    private static final int API_SECRET_MAX_LENGTH = 500;

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
        txtSmtpSender.setText(config.smtp.senderAddress);
        txtGlpiUrl.setText(config.glpiApi.baseUrl);
        txtAdUrl.setText(config.adApi.baseUrl);

        txtAfPrefix.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= AF_FORMAT_MAX_LENGTH ? change : null));
        txtAfSeparator.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= AF_FORMAT_MAX_LENGTH ? change : null));
        txtSmtpSender.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= SMTP_SENDER_MAX_LENGTH ? change : null));
        txtGlpiUrl.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= URL_MAX_LENGTH ? change : null));
        txtAdUrl.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= URL_MAX_LENGTH ? change : null));
        pfSmtpPassword.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= API_SECRET_MAX_LENGTH ? change : null));
        pfGlpiApiKey.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= API_SECRET_MAX_LENGTH ? change : null));
        pfAdApiToken.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= API_SECRET_MAX_LENGTH ? change : null));

        txtAfPrefix.textProperty().addListener((o, a, b) -> updateAfPreview());
        txtAfSeparator.textProperty().addListener((o, a, b) -> updateAfPreview());
        updateAfPreview();

        setupSnTable();

        // Personal preference, not admin-gated — any role can change it, applies immediately
        // (no separate Save button), same as "Nombre para mostrar" in Mi Perfil.
        toggleAutoClearForm.setSelected(TechnicianSessionService.getInstance().isAutoClearFormAfterGeneration());
        toggleAutoClearForm.selectedProperty().addListener((obs, was, isNow) ->
            TechnicianSessionService.getInstance().setAutoClearFormAfterGeneration(isNow));

        AdminSession.getInstance().addOnActivateListener(this::onAdminStateChanged);
        AdminSession.getInstance().addOnDeactivateListener(this::onAdminStateChanged);
        onAdminStateChanged();
    }

    private void onAdminStateChanged() {
        updateFieldEditability();
        // Forces colSnEdit's cell factory to re-run updateItem() immediately, so the "Editar"
        // button's disabled state reflects the new permission state right away instead of only
        // on the next scroll/data reload.
        tblSnValidation.refresh();
    }

    private void updateFieldEditability() {
        boolean canAf   = AdminSession.getInstance().hasPermission(Permission.EDIT_AF_FORMAT_CONFIG);
        boolean canSmtp = AdminSession.getInstance().hasPermission(Permission.EDIT_SMTP_CONFIG);
        boolean canGlpi = AdminSession.getInstance().hasPermission(Permission.EDIT_GLPI_CONFIG);
        boolean canAd   = AdminSession.getInstance().hasPermission(Permission.EDIT_AD_CONFIG);
        txtAfPrefix.setDisable(!canAf);
        txtAfSeparator.setDisable(!canAf);
        txtSmtpSender.setDisable(!canSmtp);
        pfSmtpPassword.setDisable(!canSmtp);
        txtGlpiUrl.setDisable(!canGlpi);
        pfGlpiApiKey.setDisable(!canGlpi);
        txtAdUrl.setDisable(!canAd);
        pfAdApiToken.setDisable(!canAd);
        // btnSave itself is never disabled — a session with none of these permissions granted
        // simply has every field disabled, so clicking Save is a harmless no-op (see handleSave).
    }

    // ── A/F preview ───────────────────────────────────────────────────

    private void updateAfPreview() {
        String prefix = txtAfPrefix.getText();
        String sep    = txtAfSeparator.getText();
        lblAfPreview.setText("Vista previa: " + prefix + sep + "AB12345678");
    }

    // ── Save ──────────────────────────────────────────────────────────

    // Each field group is persisted only if its own permission is currently granted — a session
    // with none of these fields disabled couldn't have typed into them anyway, but this is the
    // actual boundary check (not just the disabled widgets), matching this app's "validate at
    // every system boundary" convention. EDIT_SMTP_CONFIG is superadmin-only; the other three
    // stay admin-level.
    @FXML
    private void handleSave() {
        AppConfig config = ConfigService.getInstance().getConfig();
        boolean canAf   = AdminSession.getInstance().hasPermission(Permission.EDIT_AF_FORMAT_CONFIG);
        boolean canSmtp = AdminSession.getInstance().hasPermission(Permission.EDIT_SMTP_CONFIG);
        boolean canGlpi = AdminSession.getInstance().hasPermission(Permission.EDIT_GLPI_CONFIG);
        boolean canAd   = AdminSession.getInstance().hasPermission(Permission.EDIT_AD_CONFIG);

        if (!canAf && !canSmtp && !canGlpi && !canAd) {
            triggerSaveStatus("No tiene permisos para modificar esta configuración", "#ef4444");
            return;
        }

        // Captured before any mutation below, purely for AUDIT_ADMIN_ACTION's old_value —
        // never used for anything that affects the actual save.
        String oldAfPrefix    = config.afFormat.prefix;
        String oldAfSeparator = config.afFormat.separator;
        String oldSmtpSender  = config.smtp.senderAddress;
        String oldGlpiUrl     = config.glpiApi.baseUrl;
        String oldAdUrl       = config.adApi.baseUrl;

        if (canAf) {
            config.afFormat.prefix    = txtAfPrefix.getText().trim();
            config.afFormat.separator = txtAfSeparator.getText();
        }
        if (canSmtp) {
            config.smtp.senderAddress = txtSmtpSender.getText().trim();
        }
        if (canGlpi) {
            config.glpiApi.baseUrl = txtGlpiUrl.getText().trim();
        }
        String adUrl   = canAd ? txtAdUrl.getText().trim() : config.adApi.baseUrl;
        String adToken = canAd ? pfAdApiToken.getText() : "";

        Runnable persist = () -> {
            if (canAd) config.adApi.baseUrl = adUrl;

            boolean smtpPasswordChanged = false;
            if (canSmtp) {
                String smtpPassword = pfSmtpPassword.getText();
                if (!smtpPassword.isBlank()) {
                    saveEncryptedSetting("smtp_password", smtpPassword);
                    pfSmtpPassword.clear();
                    smtpPasswordChanged = true;
                }
            }

            boolean glpiKeyChanged = false;
            if (canGlpi) {
                String glpiApiKey = pfGlpiApiKey.getText();
                if (!glpiApiKey.isBlank()) {
                    saveEncryptedSetting("glpi_api_key", glpiApiKey);
                    pfGlpiApiKey.clear();
                    glpiKeyChanged = true;
                }
            }

            boolean adTokenChanged = false;
            if (canAd) {
                if (!adToken.isBlank()) {
                    saveEncryptedSetting("ad_api_token", adToken);
                    pfAdApiToken.clear();
                    adTokenChanged = true;
                }
                String effectiveToken = !adToken.isBlank() ? adToken : decryptSetting("ad_api_token");
                AdApiService.getInstance().configure(adUrl.isBlank() ? null : adUrl, effectiveToken);
            }

            try {
                ConfigService.getInstance().save();
                triggerSaveStatus("Configuración guardada", "#0c8570");
                String username = TechnicianSessionService.getInstance().getUsername();
                // Secret values (SMTP password, GLPI key, AD token) are NEVER written to
                // old_value/new_value here — only that a change happened, via the reason field.
                if (canAf && (!oldAfPrefix.equals(config.afFormat.prefix) || !oldAfSeparator.equals(config.afFormat.separator))) {
                    ServiceLocator.getInstance().getAuditService().recordAdminAction(username,
                        "EDIT_AF_FORMAT_CONFIG", "APP_CONFIG", "afFormat",
                        oldAfPrefix + oldAfSeparator, config.afFormat.prefix + config.afFormat.separator, null);
                }
                if (canSmtp && (!oldSmtpSender.equals(config.smtp.senderAddress) || smtpPasswordChanged)) {
                    ServiceLocator.getInstance().getAuditService().recordAdminAction(username,
                        "EDIT_SMTP_CONFIG", "APP_SETTINGS", "smtp",
                        oldSmtpSender, config.smtp.senderAddress, smtpPasswordChanged ? "Contraseña actualizada" : null);
                }
                if (canGlpi && (!oldGlpiUrl.equals(config.glpiApi.baseUrl) || glpiKeyChanged)) {
                    ServiceLocator.getInstance().getAuditService().recordAdminAction(username,
                        "EDIT_GLPI_CONFIG", "APP_SETTINGS", "glpi_api_key",
                        oldGlpiUrl, config.glpiApi.baseUrl, glpiKeyChanged ? "Clave API actualizada" : null);
                }
                if (canAd && (!oldAdUrl.equals(config.adApi.baseUrl) || adTokenChanged)) {
                    ServiceLocator.getInstance().getAuditService().recordAdminAction(username,
                        "EDIT_AD_CONFIG", "APP_SETTINGS", "ad_api_token",
                        oldAdUrl, config.adApi.baseUrl, adTokenChanged ? "Token actualizado" : null);
                }
            } catch (Exception e) {
                triggerSaveStatus("Error al guardar la configuración", "#ef4444");
            }
        };

        if (!canAd || adUrl.isEmpty()) {
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
                // Disabled, not hidden, for a technician without the permission — visible but
                // unusable, so it's clear the action exists rather than looking like the row has
                // no edit action at all. handleEditRow()'s own permission check is what actually
                // enforces this; disabling the button is cosmetic, same "check is the real gate,
                // button state is cosmetic" precedent as DatabaseSectionController's CRUD buttons.
                if (!empty) {
                    btn.setDisable(!AdminSession.getInstance().hasPermission(Permission.EDIT_SN_VALIDATION));
                }
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

    // colSnEdit's button (see its cell factory above) is disabled for anyone without
    // EDIT_SN_VALIDATION and is this method's only caller, so there's no path here without the
    // permission already held — no redundant re-check, unlike DatabaseSectionController's
    // requirePermission(), which stays necessary there because it's a shared gate with a
    // password-fallback path reused across many call sites; this method has neither.
    private void handleEditRow(SnValidationRow row) {
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

        // This field previously had no validation at all beyond a
        // length cap — a syntactically invalid regex (unbalanced parens, a bad quantifier, etc.)
        // saved fine here and then threw PatternSyntaxException every time a technician typed an
        // S/N against this model (see ItemDialogController.validateSnLength(), which now also
        // fails safe against an already-saved bad pattern, but this is the actual point where a
        // bad pattern should never be accepted in the first place).
        Label lblRegexError = new Label();
        lblRegexError.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 10px;");
        lblRegexError.setWrapText(true);
        lblRegexError.setManaged(false);
        lblRegexError.setVisible(false);

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
            if (!newRegex.isEmpty()) {
                try {
                    java.util.regex.Pattern.compile(newRegex);
                } catch (java.util.regex.PatternSyntaxException invalidRegex) {
                    lblRegexError.setText("Expresión regular inválida: " + invalidRegex.getDescription());
                    lblRegexError.setManaged(true);
                    lblRegexError.setVisible(true);
                    return;
                }
            }
            equipmentService.upsertSnValidation(row.getModelId(),
                newRegex.isEmpty() ? null : newRegex, chkActive.isSelected());
            ServiceLocator.getInstance().getAuditService().recordAdminAction(
                TechnicianSessionService.getInstance().getUsername(), "EDIT_SN_VALIDATION", "SN_VALIDATION",
                String.valueOf(row.getModelId()),
                "regex=" + row.getRegex() + ", activa=" + row.isActive(),
                "regex=" + newRegex + ", activa=" + chkActive.isSelected(), null);
            saved[0] = true;
            stage.close();
        });

        HBox buttons = new HBox(8, btnCancel, btnSave);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(420);
        root.getChildren().addAll(path, new Separator(), lblRegex, tfRegex, lblRegexError, chkActive, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        javafx.application.Platform.runLater(tfRegex::requestFocus);
        stage.showAndWait();

        if (saved[0]) loadSnValidationData();
    }

    // ── Dialog helpers ────────────────────────────────────────────────

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
