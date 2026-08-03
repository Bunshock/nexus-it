package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.bunshock.note_app_for_it_frontend.models.ADUser;
import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.services.PendingCountsService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Callback;
import javafx.util.Duration;

// "Cargar Nuevo Préstamo" — a lightweight, non-printing sibling of NoteGeneratorController's
// Préstamo flow (see CLAUDE.md's "Préstamos section"). It logs a loan straight into history,
// with no HTML render/print/email step at all — that's the entire difference from the printed
// flow, which is left completely untouched.
public class PrestamoNewLoanController implements ItemDialogHost, AdSearchHost {

    private static final Pattern NAME_PATTERN = Pattern.compile("^\\p{L}+( \\p{L}+)*$");
    private static final Pattern DNI_PATTERN = Pattern.compile("^\\d{7,8}$");

    @FXML private VBox rootContainer;

    @FXML private Label lblADStatus;
    @FXML private Button btnBuscarAD;
    @FXML private ProgressIndicator spinnerADSearch;
    @FXML private TextField txtUserDni;
    @FXML private TextField txtUserName;
    @FXML private TextField txtUserAccount;
    @FXML private Label lblUserEmail;

    @FXML private TextField txtAreaEvento;
    @FXML private DatePicker dtpFechaTentativa;
    @FXML private Label lblFechaTentativaStatus;

    @FXML private TableView<AssetItem> tblAssets;
    @FXML private TableColumn<AssetItem, String> colAssetType;
    @FXML private TableColumn<AssetItem, String> colAssetBrand;
    @FXML private TableColumn<AssetItem, String> colAssetModel;
    @FXML private TableColumn<AssetItem, String> colAssetSerial;
    @FXML private TableColumn<AssetItem, String> colAssetAF;
    @FXML private TableColumn<AssetItem, String> colAssetObs;
    @FXML private TableColumn<AssetItem, Void> colAssetActions;

    @FXML private TableView<CountableItem> tblCountables;
    @FXML private TableColumn<CountableItem, String> colCountType;
    @FXML private TableColumn<CountableItem, String> colCountBrand;
    @FXML private TableColumn<CountableItem, String> colCountModel;
    @FXML private TableColumn<CountableItem, Integer> colCountQty;
    @FXML private TableColumn<CountableItem, String> colCountObs;
    @FXML private TableColumn<CountableItem, Void> colCountActions;

    @FXML private Button btnAddItem;
    @FXML private Label lblTableStatus;
    @FXML private Label lblGenerationStatus;
    @FXML private TextField txtObservations;

    private final ObservableList<AssetItem> assetList = FXCollections.observableArrayList();
    private final ObservableList<CountableItem> countableList = FXCollections.observableArrayList();

    private String adSearchOriginalText;

    private static final int OBSERVATIONS_MAX_LENGTH = 300;
    private static final int AREA_EVENTO_MAX_LENGTH = 200;
    // Matches NOTE_ENTREGA_DEVOLUCION.user_name's NVARCHAR(255) bound on SQL Server — this
    // field had no cap of any kind before (it doubles as an AD
    // search-by-name box, so only the length is restricted here, not the character set, unlike
    // UserNoteController's own copy of this field).
    private static final int USER_NAME_MAX_LENGTH = 255;

    public void initialize() {
        setupAssetTable();
        setupCountableTable();

        txtObservations.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= OBSERVATIONS_MAX_LENGTH ? change : null));
        txtUserName.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= USER_NAME_MAX_LENGTH ? change : null));
        txtAreaEvento.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= AREA_EVENTO_MAX_LENGTH ? change : null));

        colAssetActions.setCellFactory(createActionCellFactory(
            this::handleEditAsset, asset -> { assetList.remove(asset); updateAddButtonState(); }));
        colCountActions.setCellFactory(createActionCellFactory(
            this::handleEditCountable, countable -> { countableList.remove(countable); updateAddButtonState(); }));

        dtpFechaTentativa.setValue(nextWorkingDay());
        updateAddButtonState();
    }

    private LocalDate nextWorkingDay() {
        LocalDate next = LocalDate.now().plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next;
    }

    // ── Item tables (ItemDialogHost) ────────────────────────────────────────────

    private void setupAssetTable() {
        colAssetType.setCellValueFactory(d -> d.getValue().getType());
        colAssetBrand.setCellValueFactory(d -> d.getValue().getBrand());
        colAssetModel.setCellValueFactory(d -> d.getValue().getModel());
        colAssetSerial.setCellValueFactory(d -> d.getValue().getSerial());
        colAssetAF.setCellValueFactory(d -> d.getValue().getAf());
        colAssetObs.setCellValueFactory(d -> d.getValue().getObservations());
        tblAssets.setItems(assetList);
    }

    private void setupCountableTable() {
        colCountType.setCellValueFactory(d -> d.getValue().getType());
        colCountBrand.setCellValueFactory(d -> d.getValue().getBrand());
        colCountModel.setCellValueFactory(d -> d.getValue().getModel());
        colCountQty.setCellValueFactory(d -> d.getValue().getQuantity().asObject());
        colCountObs.setCellValueFactory(d -> d.getValue().getObservations());
        tblCountables.setItems(countableList);
    }

    @Override
    public void addAsset(AssetItem item) { assetList.add(item); }
    @Override
    public void addCountable(CountableItem item) { countableList.add(item); }

    private <T> Callback<TableColumn<T, Void>, TableCell<T, Void>> createActionCellFactory(
            Consumer<T> editAction, Consumer<T> deleteAction) {
        return param -> new TableCell<>() {
            private final Button btnEdit = new Button("✏");
            private final Button btnDelete = new Button("🗑");
            private final HBox container = new HBox(14, btnEdit, btnDelete);

            {
                container.setAlignment(Pos.CENTER);
                btnEdit.getStyleClass().add("button-icon-edit");
                btnDelete.getStyleClass().add("button-icon-delete");
                btnEdit.setOnAction(e -> {
                    T item = getTableView().getItems().get(getIndex());
                    if (item != null) editAction.accept(item);
                });
                btnDelete.setOnAction(e -> {
                    T item = getTableView().getItems().get(getIndex());
                    if (item != null) deleteAction.accept(item);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setAlignment(Pos.CENTER);
                setGraphic(empty ? null : container);
            }
        };
    }

    private void handleEditAsset(AssetItem asset) { openItemDialog(ctrl -> ctrl.prefillAsset(asset)); }
    private void handleEditCountable(CountableItem countable) { openItemDialog(ctrl -> ctrl.prefillCountable(countable)); }

    @FXML
    private void handleAddItem() {
        openItemDialog(ctrl -> {});
    }

    private void openItemDialog(Consumer<ItemDialogController> setup) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                "/com/bunshock/note_app_for_it_frontend/views/ItemDialogView.fxml"));
            Parent root = loader.load();
            ItemDialogController ctrl = loader.getController();
            ctrl.setParentController(this);
            setup.accept(ctrl);

            Stage stage = new Stage();
            stage.initStyle(StageStyle.TRANSPARENT);
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene dialogScene = new Scene(root, 544, 580);
            dialogScene.setFill(Color.TRANSPARENT);
            dialogScene.getStylesheets().add(getClass().getResource(
                "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());
            stage.setScene(dialogScene);
            stage.setOnHidden(e -> updateAddButtonState());
            stage.show();

            javafx.geometry.Bounds btn = btnAddItem.localToScreen(btnAddItem.getBoundsInLocal());
            javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
            double x = Math.min(btn.getMaxX() - stage.getWidth(), screen.getMaxX() - stage.getWidth());
            double y = Math.min(btn.getMaxY() + 6, screen.getMaxY() - stage.getHeight());
            stage.setX(Math.max(screen.getMinX(), x));
            stage.setY(Math.max(screen.getMinY(), y));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isAtItemLimit() {
        int limit = com.bunshock.note_app_for_it_frontend.services.ConfigService.getInstance().getConfig().noteItemLimit;
        return (assetList.size() + countableList.size()) >= limit;
    }

    private void updateAddButtonState() {
        btnAddItem.setDisable(isAtItemLimit());
    }

    // ── AD search (AdSearchHost) — duplicated from UserNoteController per this codebase's
    // no-shared-abstraction convention; only the popup's callback type is shared, not the logic ──

    @FXML
    private void handleADSearch() {
        String dni = txtUserDni.getText().trim();
        String name = txtUserName.getText().trim();
        String username = txtUserAccount.getText().trim();

        if (dni.isEmpty() && name.isEmpty() && username.isEmpty()) {
            highlightFields("#f59e0b");
            triggerFeedback("Ingrese criterios de búsqueda", "#f59e0b");
            return;
        }

        btnBuscarAD.setDisable(true);
        adSearchOriginalText = btnBuscarAD.getText();
        showSearchingState();

        Thread t = new Thread(() -> {
            List<ADUser> results;
            boolean failed;
            try {
                results = ServiceLocator.getInstance().getAdService().search(dni, name, username);
                failed = false;
            } catch (Exception e) {
                results = List.of();
                failed = true;
            }
            List<ADUser> finalResults = results;
            boolean searchFailed = failed;
            Platform.runLater(() -> {
                if (searchFailed) {
                    btnBuscarAD.setDisable(false);
                    hideSearchingState(adSearchOriginalText);
                    highlightFields("#ef4444");
                    triggerFeedback("No se pudo conectar con AD", "#ef4444");
                } else if (finalResults.isEmpty()) {
                    btnBuscarAD.setDisable(false);
                    hideSearchingState(adSearchOriginalText);
                    highlightFields("#ef4444");
                    triggerFeedback("Usuario no encontrado", "#ef4444");
                } else if (finalResults.size() == 1) {
                    btnBuscarAD.setDisable(false);
                    hideSearchingState(adSearchOriginalText);
                    fillUserData(finalResults.get(0));
                    highlightFields("#0c8570");
                    triggerFeedback("Usuario cargado", "#0c8570");
                } else {
                    showUserSelectionDialog(finalResults);
                }
            });
        }, "prestamo-ad-search");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onAdSelectionDialogClosed() {
        btnBuscarAD.setDisable(false);
        hideSearchingState(adSearchOriginalText);
    }

    private void showSearchingState() {
        if (btnBuscarAD.getWidth() > btnBuscarAD.getMinWidth()) {
            btnBuscarAD.setMinWidth(btnBuscarAD.getWidth());
        }
        btnBuscarAD.setText("Buscando");
        spinnerADSearch.setVisible(true);
        spinnerADSearch.setManaged(true);
    }

    private void hideSearchingState(String originalText) {
        btnBuscarAD.setText(originalText);
        spinnerADSearch.setVisible(false);
        spinnerADSearch.setManaged(false);
    }

    @Override
    public void fillUserData(ADUser user) {
        txtUserDni.setText(user.getDni());
        txtUserName.setText(user.getFullName());
        txtUserAccount.setText(user.getUsername());
        lblUserEmail.setText("email: " + user.getEmail());
    }

    private void showUserSelectionDialog(List<ADUser> results) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                "/com/bunshock/note_app_for_it_frontend/views/ADUserSelectionView.fxml"));
            Parent root = loader.load();
            ADUserSelectionController ctrl = loader.getController();
            ctrl.setParentController(this);
            ctrl.setResults(results);

            Stage stage = new Stage();
            stage.initStyle(StageStyle.TRANSPARENT);
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene dialogScene = new Scene(root);
            dialogScene.setFill(Color.TRANSPARENT);
            dialogScene.getStylesheets().add(getClass().getResource(
                "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());
            stage.setScene(dialogScene);

            stage.setOpacity(0);
            stage.setOnShown(e -> {
                javafx.geometry.Bounds btn = btnBuscarAD.localToScreen(btnBuscarAD.getBoundsInLocal());
                javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
                double x = Math.min(btn.getMaxX() + 6, screen.getMaxX() - stage.getWidth());
                double y = Math.min(btn.getMinY(), screen.getMaxY() - stage.getHeight());
                stage.setX(Math.max(screen.getMinX(), x));
                stage.setY(Math.max(screen.getMinY(), y));
                stage.setOpacity(1);
            });
            stage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static final Duration FEEDBACK_HOLD = Duration.millis(2000);
    private static final Duration FEEDBACK_FADE = Duration.millis(650);
    private static final Color DEFAULT_BORDER_COLOR = Color.web("#cbd5e1");

    private javafx.animation.Transition borderFade;

    @Override
    public void triggerFeedback(String message, String hexColor) {
        lblADStatus.setText(message);
        lblADStatus.setStyle("-fx-text-fill: " + hexColor + "; -fx-font-size: 10px; -fx-font-weight: bold;");
        lblADStatus.setOpacity(1.0);

        FadeTransition fade = new FadeTransition(FEEDBACK_FADE, lblADStatus);
        fade.setDelay(FEEDBACK_HOLD);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            lblADStatus.setText("");
            lblADStatus.setStyle("");
            lblADStatus.setOpacity(1.0);
        });
        fade.play();
    }

    @Override
    public void highlightFields(String hexColor) {
        if (borderFade != null) borderFade.stop();

        applyBorderStyle(hexColor);
        Color from = Color.web(hexColor);

        javafx.animation.Transition fade = new javafx.animation.Transition() {
            { setDelay(FEEDBACK_HOLD); setCycleDuration(FEEDBACK_FADE); }
            @Override
            protected void interpolate(double frac) {
                applyBorderStyle(toRgbString(from.interpolate(DEFAULT_BORDER_COLOR, frac)));
            }
        };
        fade.setOnFinished(e -> resetFieldStyles());
        borderFade = fade;
        fade.play();
    }

    private static String toRgbString(Color c) {
        int r = (int) Math.round(c.getRed() * 255);
        int g = (int) Math.round(c.getGreen() * 255);
        int b = (int) Math.round(c.getBlue() * 255);
        return String.format("rgb(%d,%d,%d)", r, g, b);
    }

    private void applyBorderStyle(String colorValue) {
        String style = "-fx-border-color: " + colorValue
            + "; -fx-border-width: 1.5; -fx-border-radius: 4; -fx-background-radius: 4;";
        txtUserDni.setStyle(style);
        txtUserName.setStyle(style);
        txtUserAccount.setStyle(style);
    }

    private void resetFieldStyles() {
        txtUserDni.setStyle("");
        txtUserName.setStyle("");
        txtUserAccount.setStyle("");
    }

    @FXML
    private void handleClearUserFields() {
        txtUserDni.clear();
        txtUserName.clear();
        txtUserAccount.clear();
        lblUserEmail.setText("email: ");
        resetFieldStyles();
        lblADStatus.setText("");
    }

    @FXML
    private void handleClearForm() {
        handleClearUserFields();
        txtAreaEvento.clear();
        txtObservations.clear();
        dtpFechaTentativa.setValue(nextWorkingDay());
        assetList.clear();
        countableList.clear();
        updateAddButtonState();
    }

    // ── Validation + save ────────────────────────────────────────────────────────

    private static final Duration TABLE_ERROR_HOLD = Duration.millis(2000);
    private static final Duration TABLE_ERROR_FADE = Duration.millis(650);

    private void showTableError(String message) {
        lblTableStatus.setText(message);
        lblTableStatus.setOpacity(1.0);
        FadeTransition fade = new FadeTransition(TABLE_ERROR_FADE, lblTableStatus);
        fade.setDelay(TABLE_ERROR_HOLD);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> lblTableStatus.setText(""));
        fade.play();
    }

    private static final Duration GENERATION_SUCCESS_HOLD = Duration.millis(2000);
    private static final Duration GENERATION_SUCCESS_FADE = Duration.millis(650);

    private void showGenerationSuccess(String message) {
        lblGenerationStatus.setText(message);
        lblGenerationStatus.setOpacity(1.0);
        FadeTransition fade = new FadeTransition(GENERATION_SUCCESS_FADE, lblGenerationStatus);
        fade.setDelay(GENERATION_SUCCESS_HOLD);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> lblGenerationStatus.setText(""));
        fade.play();
    }

    private void triggerLabelFeedback(Label label, String message, String hexColor) {
        label.setText(message);
        label.setStyle("-fx-text-fill: " + hexColor + "; -fx-font-weight: bold;");
        label.setOpacity(1.0);
        FadeTransition fade = new FadeTransition(FEEDBACK_FADE, label);
        fade.setDelay(FEEDBACK_HOLD);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            label.setText("");
            label.setStyle("");
            label.setOpacity(1.0);
        });
        fade.play();
    }

    private boolean validateAndShowErrors() {
        boolean valid = true;
        String name = txtUserName.getText().trim();
        String dni = txtUserDni.getText().trim();
        if (name.isEmpty() || dni.isEmpty()) {
            triggerFeedback("Nombre y DNI son obligatorios", "#ef4444");
            valid = false;
        } else if (!NAME_PATTERN.matcher(name).matches()) {
            triggerFeedback("El nombre solo puede contener letras y espacios simples", "#ef4444");
            valid = false;
        } else if (!DNI_PATTERN.matcher(dni).matches()) {
            triggerFeedback("El DNI debe tener 7 u 8 dígitos, sin puntos", "#ef4444");
            valid = false;
        }

        LocalDate date = dtpFechaTentativa.getValue();
        if (date == null) {
            triggerLabelFeedback(lblFechaTentativaStatus, "Ingrese fecha tentativa", "#ef4444");
            valid = false;
        } else if (date.isBefore(LocalDate.now())) {
            triggerLabelFeedback(lblFechaTentativaStatus, "La fecha no puede ser pasada", "#ef4444");
            valid = false;
        }

        if (assetList.isEmpty() && countableList.isEmpty()) {
            showTableError("Agregue al menos un equipo");
            valid = false;
        }

        return valid;
    }

    @FXML
    private void handleGuardarPrestamo() {
        TechnicianSessionService technician = TechnicianSessionService.getInstance();
        if (technician.getName() == null || technician.getName().isBlank()
                || technician.getDni() == null || technician.getDni().isBlank()) {
            showMissingTechnicianProfileError();
            return;
        }
        if (technician.getSedeId() == null || technician.getSede() == null || technician.getSede().isBlank()) {
            showMissingSedeError();
            return;
        }

        if (!validateAndShowErrors()) return;

        NoteReport report = new NoteReport();
        report.setProfileType("PRÉSTAMO");
        report.setUserName(txtUserName.getText().trim());
        report.setUserDni(txtUserDni.getText().trim());
        report.setUserEmail(lblUserEmail.getText().startsWith("email: ")
            ? lblUserEmail.getText().substring(7).trim() : "");
        report.setMotivo(dtpFechaTentativa.getValue().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        report.setAreaEvento(txtAreaEvento.getText().trim());
        report.setObservations(txtObservations.getText().trim());
        report.setAuthorName(technician.getName());
        report.setAuthorDni(technician.getDni());
        report.setSede(technician.getSede());
        report.setSedeId(technician.getSedeId());
        report.setCreatedAt(LocalDateTime.now());

        List<NoteReportItem> items = new java.util.ArrayList<>();
        for (AssetItem a : assetList) {
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
            // N_A, not PENDING — Préstamo assets are deliberately excluded from GLPI sync (see
            // NotePreviewController.buildReportWithItems()'s matching comment for the full
            // rationale: GLPI sync here is one-way/no-revert, and nothing un-syncs an item when
            // a loan is returned, so syncing a temporary loan would leave GLPI permanently
            // believing the asset is still assigned to the borrower).
            i.setGlpiStatus(GlpiStatus.N_A);
            items.add(i);
        }
        for (CountableItem c : countableList) {
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
            i.setGlpiStatus(GlpiStatus.N_A);
            items.add(i);
        }
        report.setItems(items);

        ServiceLocator.getInstance().getHistoryService().save(report);
        PendingCountsService.getInstance().notifyChanged();

        showGenerationSuccess("Préstamo registrado correctamente");
        handleClearForm();
    }

    private void showMissingTechnicianProfileError() {
        showWarningNotice("Perfil de técnico incompleto",
            "No se puede registrar el préstamo: no se pudo obtener su nombre y DNI desde Active Directory. "
            + "Vaya a Mi Perfil y use \"Actualizar Perfil desde AD\", o pídale a un administrador que complete sus datos manualmente. "
            + "Todo préstamo debe quedar asociado al técnico que lo registró.");
    }

    private void showMissingSedeError() {
        showWarningNotice("Sede no configurada",
            "No se puede registrar el préstamo: no se ha configurado su Sede. "
            + "Vaya a Configuración y complete el campo \"Sede\" antes de continuar. "
            + "Todo préstamo debe quedar asociado a la sede desde la que se registró.");
    }

    // ── Dialog helpers (per admin dialog pattern — no shared base class) ──────

    private void showWarningNotice(String title, String message) {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label lblIcon = new Label("⚠");
        lblIcon.setStyle("-fx-font-size: 16px; -fx-text-fill: #f59e0b;");
        Label lblT = new Label(title);
        lblT.getStyleClass().add("section-label");
        titleRow.getChildren().addAll(lblIcon, lblT);

        Label lblMsg = new Label(message);
        lblMsg.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");
        lblMsg.setWrapText(true);

        Button btnOk = new Button("Aceptar");
        btnOk.getStyleClass().add("button-primary");
        btnOk.setOnAction(e -> stage.close());

        HBox buttons = new HBox(btnOk);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = buildDialogRoot(360, "#f59e0b");
        root.getChildren().addAll(titleRow, lblMsg, buttons);

        Scene scene = buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();
    }

    private Stage buildDialogStage() {
        Stage s = new Stage();
        s.initStyle(StageStyle.TRANSPARENT);
        s.initModality(Modality.APPLICATION_MODAL);
        return s;
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

    private VBox buildDialogRoot(double prefWidth, String accentColor) {
        VBox root = new VBox(14);
        root.setPrefWidth(prefWidth);
        root.setStyle("""
            -fx-background-color: %s, white;
            -fx-background-radius: 12, 10;
            -fx-background-insets: 0, 2;
            -fx-padding: 24;
            -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 20, 0, 0, 5);
            """.formatted(accentColor));
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
