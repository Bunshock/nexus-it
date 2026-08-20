package com.bunshock.note_app_for_it_frontend.controllers.prestamo;
import com.bunshock.note_app_for_it_frontend.controllers.core.ItemDialogController;
import com.bunshock.note_app_for_it_frontend.controllers.core.ItemDialogHost;
import com.bunshock.note_app_for_it_frontend.controllers.auth.AdSearchHost;
import com.bunshock.note_app_for_it_frontend.controllers.auth.ADUserSelectionController;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.controlsfx.control.PopOver;

import com.bunshock.note_app_for_it_frontend.models.auth.ADUser;
import com.bunshock.note_app_for_it_frontend.models.catalog.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.catalog.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.history.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReportItem;
import com.bunshock.note_app_for_it_frontend.services.history.PendingCountsService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
import com.bunshock.note_app_for_it_frontend.utils.core.DialogChrome;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.Transition;
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
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Callback;
import javafx.util.Duration;

import com.bunshock.note_app_for_it_frontend.services.core.ConfigService;
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
    @FXML private HBox stockWarningBox;
    @FXML private Label lblStockWarningSummary;
    @FXML private Hyperlink lnkStockWarningToggle;
    private final PopOver stockWarningPopOver = new PopOver();
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
    // txtUserAccount is never persisted (an AD-username lookup box only), so there's no DB column
    // bound to match; capped purely as a sanity guard against an accidental huge paste.
    private static final int USER_ACCOUNT_MAX_LENGTH = 100;

    public void initialize() {
        setupAssetTable();
        setupCountableTable();

        txtObservations.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= OBSERVATIONS_MAX_LENGTH ? change : null));
        txtUserName.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= USER_NAME_MAX_LENGTH ? change : null));
        txtAreaEvento.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= AREA_EVENTO_MAX_LENGTH ? change : null));
        txtUserAccount.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= USER_ACCOUNT_MAX_LENGTH ? change : null));

        colAssetActions.setCellFactory(createActionCellFactory(
            this::handleEditAsset, asset -> { assetList.remove(asset); updateAddButtonState(); refreshStockWarning(); }));
        colCountActions.setCellFactory(createActionCellFactory(
            this::handleEditCountable, countable -> { countableList.remove(countable); updateAddButtonState(); refreshStockWarning(); }));

        dtpFechaTentativa.setValue(nextWorkingDay());
        setupStockWarningHover();
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
    public void addAsset(AssetItem item) { assetList.add(item); refreshStockWarning(); }
    @Override
    public void addCountable(CountableItem item) { countableList.add(item); refreshStockWarning(); }

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
            Scene dialogScene = new Scene(root, 544, 620);
            dialogScene.setFill(Color.TRANSPARENT);
            dialogScene.getStylesheets().add(getClass().getResource(
                "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());
            stage.setScene(dialogScene);
            stage.setOnHidden(e -> { updateAddButtonState(); refreshStockWarning(); });
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
        int limit = com.bunshock.note_app_for_it_frontend.services.core.ConfigService.getInstance().getConfig().noteItemLimit;
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

    // Live heuristic only — does NOT move any stock itself (that happens on admin approval, see
    // SqliteHistoryService.applyNoteStockIfNeeded()). Recomputed on every item add/edit/delete;
    // also the actual gate at "Guardar" time (return value) — a Préstamo loan is always egress,
    // unlike Generar Nota's combobox there's no Devolución case to skip here.
    public boolean refreshStockWarning() {
        Integer sedeId = TechnicianSessionService.getInstance().getSedeId();
        if (sedeId == null) {
            hideStockWarning();
            return true;
        }
        List<String> shortages = computeStockShortages(sedeId);
        if (shortages.isEmpty()) {
            hideStockWarning();
            return true;
        }
        showStockWarning(shortages);
        return false;
    }

    // Detail shows on hover over "Ver detalle" instead of a click-to-expand panel — a long
    // shortage list would otherwise take over the layout below it. Same PopOver +
    // MainController.setupTitleBarStatusHover() precedent, but that alone still flickered here:
    // the popover opens directly beneath a small text link, close enough that the moment it
    // appears the cursor is already geometrically inside its screen bounds — the OS then routes
    // further mouse-move events to the (now topmost) popover window, JavaFX synthesizes a
    // MOUSE_EXITED on the link, and an immediate hide()-on-exit closes the very popover the
    // cursor is sitting on. Fixed with the standard "hoverable popover" bridge: hiding always
    // goes through a short delay that's cancelled if the cursor lands on the link OR the
    // popover's own content before it fires — so crossing the small gap between them (or the
    // instant of the popover appearing under the cursor) never closes it.
    private static final Duration STOCK_WARNING_SHOW_DELAY = Duration.millis(400);
    private static final Duration STOCK_WARNING_HIDE_DELAY = Duration.millis(200);
    private final PauseTransition stockWarningShowDelay = new PauseTransition(STOCK_WARNING_SHOW_DELAY);
    private final PauseTransition stockWarningHideDelay = new PauseTransition(STOCK_WARNING_HIDE_DELAY);

    private void setupStockWarningHover() {
        stockWarningPopOver.setDetachable(false);
        stockWarningPopOver.setArrowLocation(PopOver.ArrowLocation.TOP_RIGHT);
        stockWarningPopOver.setArrowSize(10);
        stockWarningPopOver.setCornerRadius(8);
        stockWarningPopOver.setAnimated(true);
        stockWarningPopOver.setAutoHide(false);

        stockWarningShowDelay.setOnFinished(e -> stockWarningPopOver.show(lnkStockWarningToggle));
        stockWarningHideDelay.setOnFinished(e -> stockWarningPopOver.hide());

        lnkStockWarningToggle.setOnMouseEntered(e -> {
            stockWarningHideDelay.stop();
            if (!stockWarningPopOver.isShowing()) stockWarningShowDelay.playFromStart();
        });
        lnkStockWarningToggle.setOnMouseExited(e -> {
            stockWarningShowDelay.stop();
            if (stockWarningPopOver.isShowing()) stockWarningHideDelay.playFromStart();
        });
    }

    // Updates the summary count and the hover popover's content in place — the popover itself
    // only actually appears while the mouse is over "Ver detalle" (see setupStockWarningHover()).
    private void showStockWarning(List<String> shortages) {
        lblStockWarningSummary.setText((shortages.size() == 1
            ? "⚠ 1 ítem supera"
            : "⚠ " + shortages.size() + " ítems superan") + " el stock disponible en su Sede");
        stockWarningPopOver.setContentNode(buildStockWarningPopoverContent(shortages));
        stockWarningBox.setVisible(true);
        stockWarningBox.setManaged(true);
    }

    // PopOver renders in its own popup Window, which doesn't inherit the app's stylesheet the
    // way an in-scene node would — inline-styled, same convention already established by
    // ADUserSelectionController's own hover PopOver in this codebase.
    private VBox buildStockWarningPopoverContent(List<String> shortages) {
        VBox box = new VBox(6);
        box.setStyle("-fx-padding: 14; -fx-background-color: #fffbeb; -fx-border-color: #f59e0b;"
            + " -fx-border-width: 1; -fx-border-radius: 8; -fx-background-radius: 8;");
        box.setMaxWidth(340);
        Label header = new Label("STOCK INSUFICIENTE EN SU SEDE");
        header.setStyle("-fx-text-fill: #b45309; -fx-font-weight: bold; -fx-font-size: 10px;");
        box.getChildren().add(header);
        for (String shortage : shortages) {
            Label line = new Label("• " + shortage);
            line.setWrapText(true);
            line.setMaxWidth(320);
            line.setStyle("-fx-text-fill: #92400e; -fx-font-size: 11px;");
            box.getChildren().add(line);
        }
        // Bridges the gap between the link and the popup below it — see setupStockWarningHover().
        box.setOnMouseEntered(e -> stockWarningHideDelay.stop());
        box.setOnMouseExited(e -> {
            if (stockWarningPopOver.isShowing()) stockWarningHideDelay.playFromStart();
        });
        return box;
    }

    private void hideStockWarning() {
        stockWarningBox.setVisible(false);
        stockWarningBox.setManaged(false);
        stockWarningShowDelay.stop();
        stockWarningHideDelay.stop();
        if (stockWarningPopOver.isShowing()) stockWarningPopOver.hide();
    }

    // Draws attention to the (already-visible) pill when a click on "Guardar Préstamo" is
    // actually blocked by it — same border-fade mechanism/timing as this controller's own
    // highlightFields() above (FEEDBACK_HOLD/FEEDBACK_FADE), just its own separate Transition
    // field/target so it can't stomp on the AD-field highlight animation running independently.
    // Fades toward fully transparent instead of toward DEFAULT_BORDER_COLOR — the pill has no
    // border at rest, unlike a text field.
    // Only -fx-border-color is ever set here, never -fx-border-width/-fx-border-radius —
    // .stock-warning-inline (styles.css) already reserves a permanent, transparent 2px border at
    // rest, so this animation only ever changes color, never the pill's actual size. Setting the
    // width here too (as a first attempt did) made the pill visibly grow/shift its siblings the
    // instant the flash started, since no border-width was reserved at rest — same class of bug
    // already fixed once for Historial's row accents.
    private Transition stockWarningBorderFade;

    private void flashStockWarningPill() {
        if (stockWarningBorderFade != null) stockWarningBorderFade.stop();

        Color flashColor = Color.web("#f59e0b");
        applyStockWarningPillBorder(toRgbaString(flashColor, 1.0));

        Transition fade = new Transition() {
            { setDelay(FEEDBACK_HOLD); setCycleDuration(FEEDBACK_FADE); }
            @Override
            protected void interpolate(double frac) {
                applyStockWarningPillBorder(toRgbaString(flashColor, 1.0 - frac));
            }
        };
        fade.setOnFinished(e -> stockWarningBox.setStyle(""));
        stockWarningBorderFade = fade;
        fade.play();
    }

    private void applyStockWarningPillBorder(String colorValue) {
        stockWarningBox.setStyle("-fx-border-color: " + colorValue + ";");
    }

    private static String toRgbaString(Color c, double alpha) {
        int r = (int) Math.round(c.getRed() * 255);
        int g = (int) Math.round(c.getGreen() * 255);
        int b = (int) Math.round(c.getBlue() * 255);
        return String.format("rgba(%d,%d,%d,%.3f)", r, g, b, alpha);
    }

    private List<String> computeStockShortages(int sedeId) {
        return ItemDialogHost.computeStockShortages(
            assetList, countableList, ServiceLocator.getInstance().getEquipmentService(), sedeId);
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

        // Both accumulated into one boolean, neither short-circuiting the other, so a field error
        // and an insufficient-stock pill both animate together on the same click instead of the
        // stock check only ever running once every other error is already fixed.
        boolean valid = validateAndShowErrors();
        if (!refreshStockWarning()) {
            flashStockWarningPill();
            valid = false;
        }
        if (!valid) return;

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
            i.setModifiesStock(a.isModifiesStock());
            i.setModifiesStockReason(a.getModifiesStockReason());
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
            i.setModifiesStock(c.isModifiesStock());
            i.setModifiesStockReason(c.getModifiesStockReason());
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
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

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

        VBox root = DialogChrome.buildDialogRoot(360, "#f59e0b");
        root.getChildren().addAll(titleRow, lblMsg, buttons);

        Scene scene = DialogChrome.buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();
    }

    // Dialog helpers (buildDialogStage/buildDialogRoot/buildDialogScene/centerOnContent) live in
    // utils.core.DialogChrome, shared across every controller that opens a dialog.
}
