package com.bunshock.note_app_for_it_frontend.controllers.note;
import com.bunshock.note_app_for_it_frontend.controllers.core.ItemDialogController;
import com.bunshock.note_app_for_it_frontend.controllers.core.ItemDialogHost;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.controlsfx.control.PopOver;

import com.bunshock.note_app_for_it_frontend.models.catalog.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.catalog.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.core.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.note.NoteGenerationService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
import com.bunshock.note_app_for_it_frontend.utils.core.DialogChrome;
import com.bunshock.note_app_for_it_frontend.utils.core.ViewFactory;
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
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
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

public class NoteGeneratorController implements ItemDialogHost {

    @FXML private VBox rootContainer;
    @FXML private ComboBox<NoteTypeOption> cmbNoteType;
    @FXML private StackPane dynamicContentArea;

    // Flat list of every note type Generar Nota can produce. Raw values match exactly what
    // UserNoteController.getSelectedNoteType() (and NOTE_REPORT.profile_type) expects, so
    // History/filtering/toDisplayName() need no changes. Display labels match
    // NoteGenerationService.toDisplayName()'s mapping.
    private record NoteTypeOption(String rawType, String displayLabel, boolean isProvider) {
        @Override public String toString() { return displayLabel; }
    }

    private static final List<NoteTypeOption> NOTE_TYPE_OPTIONS = List.of(
        new NoteTypeOption("ENTREGA", "Entrega", false),
        new NoteTypeOption("DEVOLUCIÓN", "Devolución", false),
        new NoteTypeOption("ENTREGA PERMANENTE", "Entrega Permanente", false),
        new NoteTypeOption("PRÉSTAMO", "Préstamo", false),
        new NoteTypeOption(null, "Proveedor (Entrega)", true)
    );
    @FXML private VBox vboxObservationsFooter;

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

    @FXML private TextField txtObservations;
    @FXML private Button btnAddItem;
    @FXML private Label lblTableStatus;
    @FXML private Label lblGenerationStatus;
    @FXML private HBox stockWarningBox;
    @FXML private Label lblStockWarningSummary;
    @FXML private Hyperlink lnkStockWarningToggle;
    private final PopOver stockWarningPopOver = new PopOver();

    private final ObservableList<AssetItem> assetList = FXCollections.observableArrayList();
    private final ObservableList<CountableItem> countableList = FXCollections.observableArrayList();

    private ViewFactory viewFactory;
    private Tooltip limitTooltip;

    public void setViewFactory(ViewFactory viewFactory) {
        this.viewFactory = viewFactory;
        // Fires the cmbNoteType listener below, landing on the first option ("Entrega").
        cmbNoteType.getSelectionModel().selectFirst();
    }

    private static final int OBSERVATIONS_MAX_LENGTH = 300;

    // Same thin-top-border divider ItemDialogController.applyGenericCellFactory() draws above
    // its own trailing "Genérico / Otro" fallback entry — duplicated per this codebase's
    // no-shared-abstraction convention, applied here above "Proveedor (Entrega)" since it's
    // conceptually a different kind of option from the 4 Usuario sub-types above it.
    private static final String DIVIDER_STYLE =
        "-fx-border-color: #e2e8f0 transparent transparent transparent; -fx-border-width: 1 0 0 0;";

    private void applyNoteTypeCellFactory() {
        cmbNoteType.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(NoteTypeOption item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item.toString());
                    setStyle(item.isProvider() ? DIVIDER_STYLE : "");
                }
            }
        });
    }

    public void initialize() {
        cmbNoteType.setItems(FXCollections.observableArrayList(NOTE_TYPE_OPTIONS));
        applyNoteTypeCellFactory();
        cmbNoteType.valueProperty().addListener((obs, old, selected) -> {
            if (selected == null) return;
            if (selected.isProvider()) {
                showProviderNoteView();
            } else {
                showUserNoteView();
                if (viewFactory != null) {
                    viewFactory.getUserNoteController().setNoteType(selected.rawType());
                }
            }
            refreshStockWarning();
        });

        txtObservations.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= OBSERVATIONS_MAX_LENGTH ? change : null));

        setupAssetTable();
        setupCountableTable();

        colAssetActions.setCellFactory(createActionCellFactory(
            this::handleEditAsset, asset -> { assetList.remove(asset); updateAddButtonState(); refreshStockWarning(); }));

        colCountActions.setCellFactory(createActionCellFactory(
            this::handleEditCountable, countable -> { countableList.remove(countable); updateAddButtonState(); refreshStockWarning(); }));

        setupStockWarningHover();
        updateAddButtonState();
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

    private void showUserNoteView() {
        if (viewFactory == null) return;
        dynamicContentArea.getChildren().setAll(viewFactory.getUserNoteView());
        setObservationsFooterVisible(true);
    }

    private void showProviderNoteView() {
        if (viewFactory == null) return;
        dynamicContentArea.getChildren().setAll(viewFactory.getProviderNoteView());
        // ViewFactory caches this view for the session — without this, a provider an admin adds
        // via Base de Datos mid-session wouldn't appear here until the app restarts.
        viewFactory.getProviderNoteController().refreshProviders();
        setObservationsFooterVisible(true);
    }

    private void setObservationsFooterVisible(boolean visible) {
        vboxObservationsFooter.setVisible(visible);
        vboxObservationsFooter.setManaged(visible);
    }

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

    private void handleEditAsset(AssetItem asset) {
        openItemDialog(ctrl -> ctrl.prefillAsset(asset), true);
    }

    private void handleEditCountable(CountableItem countable) {
        openItemDialog(ctrl -> ctrl.prefillCountable(countable), true);
    }

    @FXML
    private void handleAddItem() {
        if (isAtItemLimit()) return;
        openItemDialog(ctrl -> {}, false);
    }

    private void openItemDialog(Consumer<ItemDialogController> setup, boolean editMode) {
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
            dialogScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
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
        int limit = ConfigService.getInstance().getConfig().noteItemLimit;
        return (assetList.size() + countableList.size()) >= limit;
    }

    private void updateAddButtonState() {
        boolean atLimit = isAtItemLimit();
        if (atLimit) {
            int limit = ConfigService.getInstance().getConfig().noteItemLimit;
            btnAddItem.getStyleClass().removeAll("button-primary");
            if (!btnAddItem.getStyleClass().contains("button-limit-reached")) {
                btnAddItem.getStyleClass().add("button-limit-reached");
            }
            if (limitTooltip == null) {
                limitTooltip = new Tooltip("Límite de " + limit + " ítems por nota alcanzado");
            }
            btnAddItem.setTooltip(limitTooltip);
        } else {
            btnAddItem.getStyleClass().removeAll("button-limit-reached");
            if (!btnAddItem.getStyleClass().contains("button-primary")) {
                btnAddItem.getStyleClass().add("button-primary");
            }
            btnAddItem.setTooltip(null);
        }
    }

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

    public void addAsset(AssetItem item) { assetList.add(item); refreshStockWarning(); }
    public void addCountable(CountableItem item) { countableList.add(item); refreshStockWarning(); }

    public ObservableList<AssetItem> getAssetList() { return assetList; }
    public ObservableList<CountableItem> getCountableList() { return countableList; }
    public String getObservations() { return txtObservations.getText().trim(); }

    public boolean isUserNote() {
        NoteTypeOption selected = cmbNoteType.getValue();
        return selected != null && !selected.isProvider();
    }

    @FXML
    private void handleClearForm() {
        txtObservations.clear();
        assetList.clear();
        countableList.clear();
        updateAddButtonState();

        if (viewFactory == null) return;
        if (isUserNote() && viewFactory.getUserNoteController() != null) {
            viewFactory.getUserNoteController().clearAllFields();
        } else if (!isUserNote() && viewFactory.getProviderNoteController() != null) {
            viewFactory.getProviderNoteController().clearAllFields();
        }
    }

    // Matches UserNoteController's FEEDBACK_HOLD/FEEDBACK_FADE so the equipment-table error
    // fades away at the same speed as the Motivo/recipient-field errors shown alongside it
    // when Generar Nota is clicked — duplicated per this codebase's no-shared-abstraction
    // convention rather than referencing UserNoteController's constants directly.
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

    @FXML
    private void handleGenerateNote() {
        try {
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

            boolean canGenerate = true;
            if (assetList.isEmpty() && countableList.isEmpty()) {
                showTableError("Agregue al menos un equipo");
                canGenerate = false;
            }
            if (isUserNote() && !viewFactory.getUserNoteController().validateAndShowErrors()) {
                canGenerate = false;
            }
            if (!isUserNote() && !viewFactory.getProviderNoteController().validateAndShowErrors()) {
                canGenerate = false;
            }
            // Evaluated unconditionally alongside every other check, not gated behind an early
            // return, so the stock pill's flash fires together with any other validation error on
            // the same click.
            if (!refreshStockWarning()) {
                flashStockWarningPill();
                canGenerate = false;
            }
            if (!canGenerate) return;
            if (isUserNote() && !checkGlpiAssignments()) return;

            NoteGenerationService generator = new NoteGenerationService();
            String html;
            NoteReport report = new NoteReport();

            if (isUserNote()) {
                UserNoteController unc = viewFactory.getUserNoteController();
                String profileType = unc.getSelectedNoteType();
                boolean isPrestamo = "PRÉSTAMO".equals(profileType);
                boolean isDevolucion = "DEVOLUCIÓN".equals(profileType);
                String motimoOrFecha = isPrestamo ? unc.getFechaTentativa() : unc.getMotivo();
                String failureCause = isDevolucion ? unc.getFailureCause() : null;
                String failureDetails = isDevolucion ? unc.getFailureDetails() : null;
                String areaEvento = isPrestamo ? unc.getAreaEvento() : null;
                html = generator.generateUserNote(
                    profileType,
                    unc.getUserName(), unc.getUserDni(), unc.getUserEmail(),
                    motimoOrFecha,
                    technician.getName(), technician.getDni(), technician.getSede(),
                    failureCause, failureDetails, areaEvento,
                    assetList, countableList, getObservations()
                );
                report.setUserName(unc.getUserName());
                report.setUserDni(unc.getUserDni());
                report.setUserEmail(unc.getUserEmail());
                report.setMotivo(motimoOrFecha);
                report.setFailureCause(failureCause);
                report.setFailureDetails(failureDetails);
                report.setAreaEvento(areaEvento);
                report.setProfileType(profileType);
            } else {
                ProviderNoteController pnc = viewFactory.getProviderNoteController();
                html = generator.generateProviderNote(
                    pnc.getProviderName(), pnc.getCuit(),
                    pnc.getResponsibleName(), pnc.getResponsibleDni(),
                    pnc.getMotivo(),
                    technician.getName(), technician.getDni(), technician.getSede(),
                    assetList, countableList, getObservations()
                );
                report.setProviderName(pnc.getProviderName());
                report.setProviderId(pnc.getProviderId());
                report.setCuit(pnc.getCuit());
                report.setMotivo(pnc.getMotivo());
                report.setResponsibleName(pnc.getResponsibleName());
                report.setResponsibleDni(pnc.getResponsibleDni());
                report.setProfileType("ENTREGA - PROVEEDOR");
            }

            report.setAuthorName(technician.getName());
            report.setAuthorDni(technician.getDni());
            report.setSede(technician.getSede());
            report.setSedeId(technician.getSedeId());
            report.setCreatedAt(LocalDateTime.now());
            report.setObservations(getObservations());
            openPreview(html, report.getProfileType(), report);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showMissingTechnicianProfileError() {
        showWarningNotice("Perfil de técnico incompleto",
            "No se puede generar la nota: no se pudo obtener su nombre y DNI desde Active Directory. "
            + "Vaya a Mi Perfil y use \"Actualizar Perfil desde AD\", o pídale a un administrador que complete sus datos manualmente. "
            + "Toda nota debe quedar asociada al técnico que la generó.");
    }

    private void showMissingSedeError() {
        showWarningNotice("Sede no configurada",
            "No se puede generar la nota: no se ha configurado su Sede. "
            + "Vaya a Configuración y complete el campo \"Sede\" antes de continuar. "
            + "Toda nota debe quedar asociada a la sede desde la que se generó.");
    }

    private void showWarningNotice(String title, String message) {
        showDialogNotice(title, message, "#f59e0b", "⚠");
    }

    private void showDialogNotice(String title, String message, String accentColor, String icon) {
        Stage stage = DialogChrome.buildDialogStage();
        DialogChrome.centerOnContent(stage, rootContainer);

        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (icon != null) {
            Label lblIcon = new Label(icon);
            lblIcon.setStyle("-fx-font-size: 16px; -fx-text-fill: " + accentColor + ";");
            titleRow.getChildren().add(lblIcon);
        }
        Label lblT = new Label(title);
        lblT.getStyleClass().add("section-label");
        titleRow.getChildren().add(lblT);

        Label lblMsg = new Label(message);
        lblMsg.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px;");
        lblMsg.setWrapText(true);

        Button btnOk = new Button("Aceptar");
        btnOk.getStyleClass().add("button-primary");
        btnOk.setOnAction(e -> stage.close());

        HBox buttons = new HBox(btnOk);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = DialogChrome.buildDialogRoot(360, accentColor);
        root.getChildren().addAll(titleRow, lblMsg, buttons);

        Scene scene = DialogChrome.buildDialogScene(root);
        scene.setOnKeyPressed(ev -> { if (ev.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.showAndWait();
    }

    // Dialog helpers (buildDialogStage/buildDialogRoot/buildDialogScene/centerOnContent) live in
    // utils.core.DialogChrome, shared across every controller that opens a dialog.

    private boolean checkGlpiAssignments() {
        UserNoteController unc = viewFactory != null ? viewFactory.getUserNoteController() : null;
        if (unc == null) return true;
        if (!"DEVOLUCIÓN".equals(unc.getSelectedNoteType())) return true;

        String userAccount = unc.getUserAccount();
        List<String> conflicts = new ArrayList<>();

        for (AssetItem asset : assetList) {
            String sn = asset.getSerial().get();
            if (sn == null || sn.isEmpty()) continue;
            String assigned = ServiceLocator.getInstance().getGlpiService().getAssignedUserInGlpi(sn);
            if (assigned != null && !assigned.equalsIgnoreCase(userAccount)) {
                conflicts.add(asset.getType().get() + " S/N: " + sn + " (asignado a: " + assigned + ")");
            }
        }

        if (conflicts.isEmpty()) return true;

        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle("Conflicto de Asignación en GLPI");
        alert.setHeaderText("No se puede generar la nota de devolución");
        alert.setContentText("Los siguientes activos no están asignados al usuario seleccionado:\n\n"
            + String.join("\n", conflicts));
        alert.showAndWait();
        return false;
    }

    // Live heuristic only — does NOT move any stock itself (that happens on admin approval,
    // see SqliteHistoryService.applyNoteStockIfNeeded()). Recomputed on every item change; the
    // return value also gates Generar-click. Skipped for Devolución (ingress, nothing to block).
    public boolean refreshStockWarning() {
        NoteTypeOption selected = cmbNoteType.getValue();
        if (selected == null || "DEVOLUCIÓN".equals(selected.rawType())) {
            hideStockWarning();
            return true;
        }
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

    // Draws attention to the pill when Generar Nota is blocked by it — fades toward fully
    // transparent, not a "default" color, since the pill has no border at rest. Only
    // -fx-border-color is ever set (never -fx-border-width) — .stock-warning-inline reserves a
    // permanent transparent 2px border at rest, so this never shifts the pill's siblings.
    private Transition stockWarningBorderFade;

    private void flashStockWarningPill() {
        if (stockWarningBorderFade != null) stockWarningBorderFade.stop();

        Color flashColor = Color.web("#f59e0b");
        applyStockWarningPillBorder(toRgbaString(flashColor, 1.0));

        Transition fade = new Transition() {
            { setDelay(TABLE_ERROR_HOLD); setCycleDuration(TABLE_ERROR_FADE); }
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
        return ItemDialogHost.computeStockShortages(
            assetList, countableList, ServiceLocator.getInstance().getEquipmentService(), sedeId);
    }

    private void openPreview(String html, String profileType, NoteReport report) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource(
            "/com/bunshock/note_app_for_it_frontend/views/NotePreviewView.fxml"));
        Parent root = loader.load();
        NotePreviewController ctrl = loader.getController();
        ctrl.loadPreview(html, profileType, report, assetList, countableList);
        ctrl.setOnSuccess(() -> {
            showGenerationSuccess("Nota generada correctamente");
            // Per-technician preference (Configuración → Generación de notas), any role can
            // change it — defaults to on ("to prevent mistakes": reusing stale form data for a
            // different note). handleClearForm() already does exactly what's needed here.
            if (TechnicianSessionService.getInstance().isAutoClearFormAfterGeneration()) {
                handleClearForm();
            }
        });

        javafx.scene.layout.VBox rootVBox = (javafx.scene.layout.VBox) root;

        // Dark border frame: children paint over background tricks, so the border
        // must be a real container with padding around the content.
        javafx.scene.layout.StackPane borderFrame = new javafx.scene.layout.StackPane(rootVBox);
        borderFrame.setStyle("""
            -fx-background-color: #1a1a1a;
            -fx-background-radius: 12;
            -fx-padding: 2;
            """);

        // WebView prevents effects from rendering on ancestor nodes — shadow lives on
        // a separate backing layer stacked behind the border frame.
        javafx.scene.layout.Region shadowBacking = new javafx.scene.layout.Region();
        shadowBacking.prefWidthProperty().bind(borderFrame.widthProperty());
        shadowBacking.prefHeightProperty().bind(borderFrame.heightProperty());
        shadowBacking.setStyle("""
            -fx-background-color: white;
            -fx-background-radius: 12;
            -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 20, 0, 0, 5);
            """);

        javafx.scene.layout.StackPane wrapper = new javafx.scene.layout.StackPane(shadowBacking, borderFrame);
        wrapper.setStyle("-fx-background-color: transparent; -fx-padding: 32;");
        javafx.scene.Scene scene = new javafx.scene.Scene(wrapper);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
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
}
