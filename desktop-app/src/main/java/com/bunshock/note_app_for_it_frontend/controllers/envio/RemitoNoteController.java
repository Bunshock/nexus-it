package com.bunshock.note_app_for_it_frontend.controllers.envio;
import com.bunshock.note_app_for_it_frontend.controllers.core.ItemDialogController;
import com.bunshock.note_app_for_it_frontend.controllers.core.ItemDialogHost;
import com.bunshock.note_app_for_it_frontend.controllers.note.NotePreviewController;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.controlsfx.control.PopOver;

import com.bunshock.note_app_for_it_frontend.models.catalog.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.catalog.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.catalog.Sede;
import com.bunshock.note_app_for_it_frontend.models.catalog.SedeShippingInfo;
import com.bunshock.note_app_for_it_frontend.services.catalog.IEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.note.NoteGenerationService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.Transition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Callback;
import javafx.util.Duration;

import com.bunshock.note_app_for_it_frontend.utils.core.DialogChrome;
// Inter-Sede stock transfer note. Ships from the technician's own assigned Sede to either a
// SEDE-catalog destination (auto-fills its saved SEDE_SHIPPING_INFO, still editable) or a custom
// one-off destination (e.g. a CAU not in the catalog). Unlike PrestamoNewLoanController's
// no-print entry point, this one does render/print — it routes through the same
// NotePreviewController popup every other note type uses. Stock only actually moves once an admin
// approves the resulting note (see SqliteHistoryService.applyRemitoStockIfNeeded()).
public class RemitoNoteController implements ItemDialogHost {

    @FXML private VBox rootContainer;

    @FXML private Label lblSourceSede;
    @FXML private ComboBox<Sede> cmbDestinationSede;
    @FXML private Label lblDestinationStatus;
    @FXML private CheckBox chkCustomDestination;
    @FXML private TextField txtDestinationLabel;
    @FXML private TextField txtAddress;
    @FXML private TextField txtRecipients;

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

    private IEquipmentService equipmentService;
    // Captured at Sede-selection time (fillDestinationFields()), not re-derived at save time —
    // see NoteReport.shippingInfoId's own Javadoc for why. Null whenever "Personalizar destino"
    // is in effect, or before any Sede has been picked yet.
    private Integer selectedShippingInfoId;

    private static final int OBSERVATIONS_MAX_LENGTH = 300;
    // Matches SEDE_SHIPPING_INFO/NOTE_REMITO's NVARCHAR(255)/(500) bounds on SQL Server.
    private static final int DESTINATION_LABEL_MAX_LENGTH = 255;
    private static final int ADDRESS_MAX_LENGTH = 500;
    private static final int RECIPIENTS_MAX_LENGTH = 500;

    public void initialize() {
        equipmentService = ServiceLocator.getInstance().getEquipmentService();
        setupAssetTable();
        setupCountableTable();

        txtObservations.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= OBSERVATIONS_MAX_LENGTH ? change : null));
        txtDestinationLabel.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= DESTINATION_LABEL_MAX_LENGTH ? change : null));
        txtAddress.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= ADDRESS_MAX_LENGTH ? change : null));
        txtRecipients.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= RECIPIENTS_MAX_LENGTH ? change : null));

        colAssetActions.setCellFactory(createActionCellFactory(
            this::handleEditAsset, asset -> { assetList.remove(asset); updateAddButtonState(); refreshStockWarning(); }));
        colCountActions.setCellFactory(createActionCellFactory(
            this::handleEditCountable, countable -> { countableList.remove(countable); updateAddButtonState(); refreshStockWarning(); }));

        setupStockWarningHover();

        String sede = TechnicianSessionService.getInstance().getSede();
        lblSourceSede.setText(sede != null && !sede.isBlank() ? sede : "Sede no asignada");

        cmbDestinationSede.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Sede s) { return s == null ? "" : s.getName(); }
            @Override public Sede fromString(String s) { return null; }
        });
        populateDestinationSedeCombo();
        cmbDestinationSede.valueProperty().addListener((obs, old, sel) -> fillDestinationFields(sel));
        // Disabled by default (catalog mode, unchecked "Personalizar destino") — the 3 fields
        // only ever reflect the picked Sede's saved data until the technician opts into typing
        // their own via the checkbox.
        txtDestinationLabel.setDisable(true);
        txtAddress.setDisable(true);
        txtRecipients.setDisable(true);

        updateAddButtonState();
    }

    // A Remito can't target the technician's own Sede — nothing to ship "between." Also excludes
    // any Sede with no active SEDE_SHIPPING_INFO configured — NOTE_REMITO_SEDE.shipping_info_id
    // is a mandatory FK now (see CLAUDE.md's Remito schema notes), so a Sede with nothing to
    // reference simply isn't offered as a catalog destination at all; "Personalizar destino"
    // remains the escape hatch for shipping somewhere not yet configured (or not in the SEDE
    // catalog to begin with, e.g. a CAU).
    private void populateDestinationSedeCombo() {
        Integer mySedeId = TechnicianSessionService.getInstance().getSedeId();
        java.util.Set<Integer> sedesWithShippingInfo = equipmentService.getSedeIdsWithShippingInfo();
        List<Sede> options = equipmentService.getAllSedes().stream()
            .filter(s -> mySedeId == null || s.getId() != mySedeId)
            .filter(s -> sedesWithShippingInfo.contains(s.getId()))
            .toList();
        cmbDestinationSede.setItems(FXCollections.observableArrayList(options));
    }

    // Pre-fills from the Sede's saved shipping info and captures its id for save time (see
    // selectedShippingInfoId's own Javadoc) — the 3 fields are disabled while a catalog Sede is
    // selected (see initialize()/handleToggleCustomDestination()), so they can never diverge from
    // this snapshot. Every Sede reaching this point is guaranteed to have shipping info
    // configured (populateDestinationSedeCombo() already filtered the combo to only those), so
    // the Optional is only ever empty defensively.
    private void fillDestinationFields(Sede sede) {
        if (sede == null) { selectedShippingInfoId = null; return; }
        Optional<SedeShippingInfo> info = equipmentService.getSedeShippingInfo(sede.getId());
        txtDestinationLabel.setText(info.map(SedeShippingInfo::getDestinationLabel).orElse(sede.getName()));
        txtAddress.setText(info.map(SedeShippingInfo::getAddress).orElse(""));
        txtRecipients.setText(info.map(SedeShippingInfo::getRecipients).orElse(""));
        selectedShippingInfoId = info.map(SedeShippingInfo::getId).orElse(null);
    }

    @FXML
    private void handleToggleCustomDestination() {
        boolean custom = chkCustomDestination.isSelected();
        cmbDestinationSede.setDisable(custom);
        txtDestinationLabel.setDisable(!custom);
        txtAddress.setDisable(!custom);
        txtRecipients.setDisable(!custom);
        cmbDestinationSede.setValue(null);
        txtDestinationLabel.clear();
        txtAddress.clear();
        txtRecipients.clear();
        selectedShippingInfoId = null;
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

    @FXML
    private void handleClearForm() {
        cmbDestinationSede.setValue(null);
        chkCustomDestination.setSelected(false);
        cmbDestinationSede.setDisable(false);
        txtDestinationLabel.setDisable(true);
        txtAddress.setDisable(true);
        txtRecipients.setDisable(true);
        txtDestinationLabel.clear();
        txtAddress.clear();
        txtRecipients.clear();
        selectedShippingInfoId = null;
        txtObservations.clear();
        assetList.clear();
        countableList.clear();
        updateAddButtonState();
    }

    // ── Validation + generation ──────────────────────────────────────────────────

    private static final Duration FEEDBACK_HOLD = Duration.millis(2000);
    private static final Duration FEEDBACK_FADE = Duration.millis(650);

    private void showTableError(String message) {
        lblTableStatus.setText(message);
        lblTableStatus.setOpacity(1.0);
        FadeTransition fade = new FadeTransition(FEEDBACK_FADE, lblTableStatus);
        fade.setDelay(FEEDBACK_HOLD);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> lblTableStatus.setText(""));
        fade.play();
    }

    private void showGenerationSuccess(String message) {
        lblGenerationStatus.setText(message);
        lblGenerationStatus.setOpacity(1.0);
        FadeTransition fade = new FadeTransition(FEEDBACK_FADE, lblGenerationStatus);
        fade.setDelay(FEEDBACK_HOLD);
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

    private boolean isDestinationValid() {
        if (chkCustomDestination.isSelected()) {
            return !txtDestinationLabel.getText().trim().isEmpty()
                && !txtAddress.getText().trim().isEmpty()
                && !txtRecipients.getText().trim().isEmpty();
        }
        return cmbDestinationSede.getValue() != null;
    }

    private boolean validateAndShowErrors() {
        boolean valid = true;
        if (!isDestinationValid()) {
            String msg = chkCustomDestination.isSelected()
                ? "Complete destino, dirección y destinatarios"
                : "Seleccione una sede destino";
            triggerLabelFeedback(lblDestinationStatus, msg, "#ef4444");
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
    // also the actual gate at "Generar Remito" time (return value). Always egress from the
    // SOURCE Sede (technician.getSedeId()) regardless of catalog vs. custom destination — the
    // destination doesn't affect what's available to ship out.
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

    // The popover opens directly beneath the link, close enough that JavaFX synthesizes a
    // MOUSE_EXITED on the link the instant it appears, which would immediately hide it again.
    // Fixed with a "hoverable popover" bridge: hiding goes through a short delay cancelled if
    // the cursor lands on the link OR the popover's own content first.
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

    // Draws attention to the pill when Generar Remito is blocked by it — fades toward fully
    // transparent, not a "default" color, since the pill has no border at rest. Only
    // -fx-border-color is ever set (never -fx-border-width) — .stock-warning-inline reserves a
    // permanent transparent 2px border at rest, so this never shifts the pill's siblings.
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

    // Aggregates assetList/countableList by (typeId, brandId, modelId) — the same model can
    // appear on multiple rows — and compares each requested total against current stock at
    // sedeId. Returns one human-readable line per short model, or an empty list if everything
    // requested fits.
    private List<String> computeStockShortages(int sedeId) {
        return ItemDialogHost.computeStockShortages(assetList, countableList, equipmentService, sedeId);
    }

    @FXML
    private void handleGenerarRemito() {
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

        Sede destSede = chkCustomDestination.isSelected() ? null : cmbDestinationSede.getValue();
        String destinationLabel = txtDestinationLabel.getText().trim();
        String address = txtAddress.getText().trim();
        String recipients = txtRecipients.getText().trim();
        String observations = txtObservations.getText().trim();

        try {
            NoteGenerationService generator = new NoteGenerationService();
            String html = generator.generateRemitoNote(
                technician.getSede(), destinationLabel, address, recipients,
                technician.getName(), technician.getDni(),
                assetList, countableList, observations);

            NoteReport report = new NoteReport();
            report.setProfileType("REMITO DE ENVÍO");
            report.setDestinationSedeId(destSede != null ? destSede.getId() : null);
            report.setShippingInfoId(destSede != null ? selectedShippingInfoId : null);
            report.setDestinationLabel(destinationLabel);
            report.setAddress(address);
            report.setRecipients(recipients);
            report.setObservations(observations);
            report.setAuthorName(technician.getName());
            report.setAuthorDni(technician.getDni());
            report.setSede(technician.getSede());
            report.setSedeId(technician.getSedeId());
            report.setCreatedAt(LocalDateTime.now());

            openPreview(html, report);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void showMissingTechnicianProfileError() {
        showWarningNotice("Perfil de técnico incompleto",
            "No se puede generar el remito: no se pudo obtener su nombre y DNI desde Active Directory. "
            + "Vaya a Mi Perfil y use \"Actualizar Perfil desde AD\", o pídale a un administrador que complete sus datos manualmente. "
            + "Todo remito debe quedar asociado al técnico que lo generó.");
    }

    private void showMissingSedeError() {
        showWarningNotice("Sede no configurada",
            "No se puede generar el remito: no se ha asignado una Sede a su cuenta. "
            + "Pídale a un superadministrador que le asigne una antes de continuar.");
    }

    // ── Preview popup (duplicated from NoteGeneratorController per this codebase's
    // no-shared-abstraction convention) ─────────────────────────────────────────

    private void openPreview(String html, NoteReport report) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource(
            "/com/bunshock/note_app_for_it_frontend/views/NotePreviewView.fxml"));
        Parent root = loader.load();
        NotePreviewController ctrl = loader.getController();
        ctrl.loadPreview(html, report.getProfileType(), report, assetList, countableList);
        ctrl.setOnSuccess(() -> {
            showGenerationSuccess("Remito generado correctamente");
            if (TechnicianSessionService.getInstance().isAutoClearFormAfterGeneration()) {
                handleClearForm();
            }
        });

        VBox rootVBox = (VBox) root;

        StackPane borderFrame = new StackPane(rootVBox);
        borderFrame.setStyle("""
            -fx-background-color: #1a1a1a;
            -fx-background-radius: 12;
            -fx-padding: 2;
            """);

        Region shadowBacking = new Region();
        shadowBacking.prefWidthProperty().bind(borderFrame.widthProperty());
        shadowBacking.prefHeightProperty().bind(borderFrame.heightProperty());
        shadowBacking.setStyle("""
            -fx-background-color: white;
            -fx-background-radius: 12;
            -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 20, 0, 0, 5);
            """);

        StackPane wrapper = new StackPane(shadowBacking, borderFrame);
        wrapper.setStyle("-fx-background-color: transparent; -fx-padding: 32;");

        Scene scene = new Scene(wrapper);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource(
            "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());

        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setScene(scene);

        stage.setOpacity(0);
        stage.setOnShown(e -> {
            javafx.geometry.Bounds b = rootContainer.localToScreen(rootContainer.getBoundsInLocal());
            if (b != null) {
                stage.setX(b.getMinX() + (b.getWidth()  - stage.getWidth())  / 2);
                stage.setY(b.getMinY() + (b.getHeight() - stage.getHeight()) / 2);
            }
            stage.setOpacity(1);
        });

        stage.show();
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
