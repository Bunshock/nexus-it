package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.NoteGenerationService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;
import com.bunshock.note_app_for_it_frontend.utils.ViewFactory;

import javafx.animation.FadeTransition;
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
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
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

public class NoteGeneratorController {

    @FXML private VBox rootContainer;
    @FXML private ToggleButton btnUserNote;
    @FXML private ToggleButton btnProviderNote;
    @FXML private ToggleGroup typeGroup;
    @FXML private StackPane dynamicContentArea;

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

    private final ObservableList<AssetItem> assetList = FXCollections.observableArrayList();
    private final ObservableList<CountableItem> countableList = FXCollections.observableArrayList();

    private ViewFactory viewFactory;
    private Tooltip limitTooltip;

    public void setViewFactory(ViewFactory viewFactory) {
        this.viewFactory = viewFactory;
        showUserNoteView();
    }

    public void initialize() {
        btnUserNote.setOnAction(e -> showUserNoteView());
        btnProviderNote.setOnAction(e -> showProviderNoteView());

        typeGroup.selectedToggleProperty().addListener((obs, old, next) -> {
            if (next == null) old.setSelected(true);
        });

        setupAssetTable();
        setupCountableTable();

        colAssetActions.setCellFactory(createActionCellFactory(
            this::handleEditAsset, asset -> { assetList.remove(asset); updateAddButtonState(); }));

        colCountActions.setCellFactory(createActionCellFactory(
            this::handleEditCountable, countable -> { countableList.remove(countable); updateAddButtonState(); }));

        updateAddButtonState();
    }

    private void showUserNoteView() {
        if (viewFactory != null) dynamicContentArea.getChildren().setAll(viewFactory.getUserNoteView());
    }

    private void showProviderNoteView() {
        if (viewFactory != null) dynamicContentArea.getChildren().setAll(viewFactory.getProviderNoteView());
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
            Scene dialogScene = new Scene(root, 544, 580);
            dialogScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
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

    public void addAsset(AssetItem item) { assetList.add(item); }
    public void addCountable(CountableItem item) { countableList.add(item); }

    public ObservableList<AssetItem> getAssetList() { return assetList; }
    public ObservableList<CountableItem> getCountableList() { return countableList; }
    public String getObservations() { return txtObservations.getText().trim(); }

    public boolean isUserNote() {
        return typeGroup.getSelectedToggle() == btnUserNote;
    }

    @FXML
    private void handleClearForm() {
        txtObservations.clear();
        assetList.clear();
        countableList.clear();
        updateAddButtonState();

        if (isUserNote() && viewFactory != null && viewFactory.getUserNoteController() != null) {
            viewFactory.getUserNoteController().clearAllFields();
        } else if (!isUserNote() && viewFactory != null && viewFactory.getProviderNoteController() != null) {
            viewFactory.getProviderNoteController().clearAllFields();
        }
    }

    private void showTableError(String message) {
        lblTableStatus.setText(message);
        lblTableStatus.setOpacity(1.0);
        FadeTransition fade = new FadeTransition(Duration.millis(400), lblTableStatus);
        fade.setDelay(Duration.millis(2000));
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> lblTableStatus.setText(""));
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
                html = generator.generateUserNote(
                    profileType,
                    unc.getUserName(), unc.getUserDni(), unc.getUserEmail(),
                    motimoOrFecha,
                    technician.getName(), technician.getDni(),
                    failureCause, failureDetails,
                    assetList, countableList, getObservations()
                );
                report.setUserName(unc.getUserName());
                report.setUserDni(unc.getUserDni());
                report.setUserEmail(unc.getUserEmail());
                report.setMotivo(motimoOrFecha);
                report.setFailureCause(failureCause);
                report.setFailureDetails(failureDetails);
                report.setProfileType(profileType);
            } else {
                ProviderNoteController pnc = viewFactory.getProviderNoteController();
                html = generator.generateProviderNote(
                    pnc.getProviderName(), pnc.getCuit(),
                    pnc.getResponsibleName(), pnc.getResponsibleDni(),
                    pnc.getMotivo(),
                    technician.getName(), technician.getDni(),
                    assetList, countableList, getObservations()
                );
                report.setProviderName(pnc.getProviderName());
                report.setCuit(pnc.getCuit());
                report.setMotivo(pnc.getMotivo());
                report.setResponsibleName(pnc.getResponsibleName());
                report.setResponsibleDni(pnc.getResponsibleDni());
                report.setProfileType("Entrega - Proveedor");
            }

            report.setAuthorName(technician.getName());
            report.setAuthorDni(technician.getDni());
            report.setCreatedAt(LocalDateTime.now());
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

    private void showWarningNotice(String title, String message) {
        showDialogNotice(title, message, "#f59e0b", "⚠");
    }

    private void showDialogNotice(String title, String message, String accentColor, String icon) {
        Stage stage = buildDialogStage();
        centerOnContent(stage);

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

        VBox root = buildDialogRoot(360, accentColor);
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

    private void openPreview(String html, String profileType, NoteReport report) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource(
            "/com/bunshock/note_app_for_it_frontend/views/NotePreviewView.fxml"));
        Parent root = loader.load();
        NotePreviewController ctrl = loader.getController();
        ctrl.loadPreview(html, profileType, report, assetList, countableList);

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
