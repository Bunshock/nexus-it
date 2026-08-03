package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.bunshock.note_app_for_it_frontend.models.ADUser;
import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.animation.FadeTransition;
import javafx.animation.Transition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class UserNoteController implements AdSearchHost {

    private static final Pattern NAME_PATTERN =
        Pattern.compile("^\\p{L}+( \\p{L}+)*$");
    private static final Pattern DNI_PATTERN =
        Pattern.compile("^\\d{7,8}$");
    private static final int AREA_EVENTO_MAX_LENGTH = 200;
    // Matches NOTE_ENTREGA_DEVOLUCION.user_name's NVARCHAR(255) bound on SQL Server —
    // this field only restricted character set before, with no length limit.
    private static final int USER_NAME_MAX_LENGTH = 255;

    @FXML private ToggleGroup userNoteTypeGroup;
    @FXML private ToggleButton btnTypeEntrega;
    @FXML private ToggleButton btnTypeDevolucion;
    @FXML private ToggleButton btnTypeFinContrato;
    @FXML private ToggleButton btnTypePrestamo;

    @FXML private VBox vboxMotivo;
    @FXML private ComboBox<String> cmbMotivo;
    @FXML private Label lblMotivoStatus;
    @FXML private Label lblFailureSummary;

    @FXML private VBox vboxFechaTentativa;
    @FXML private DatePicker dtpFechaTentativa;
    @FXML private Label lblFechaTentativaStatus;

    @FXML private VBox vboxAreaEvento;
    @FXML private TextField txtAreaEvento;

    @FXML private Label lblADStatus;
    @FXML private Button btnBuscarAD;
    @FXML private ProgressIndicator spinnerADSearch;
    @FXML private TextField txtUserDni;
    @FXML private TextField txtUserName;
    @FXML private TextField txtUserAccount;
    @FXML private Label lblUserEmail;

    private String adSearchOriginalText;

    private String failureCause;
    private String failureDetails;

    // Explicit confirmation flag, tracked alongside failureCause/failureDetails rather than
    // inferred from failureCause's nullness — set true only by setFailureDetails() (a real
    // Guardar in FailureDetailView), false only by clearFailureDetails(). Keeps "has Falla been
    // confirmed for this session" unambiguous regardless of what cause/details actually contain.
    private boolean failureConfirmed;

    // "Full Motivo memory per type" — each note type remembers its own last-selected Motivo
    // across type switches, instead of resetting to unselected every time (the old behavior).
    // Keyed by the type ToggleButton itself, not by the motivoOptions map key.
    private final Map<ToggleButton, String> lastMotivoByType = new HashMap<>();

    // True only for the duration of loadMotivoOptions()'s programmatic cmbMotivo.setValue(...)
    // restore call. The cmbMotivo listener uses this — not just failureConfirmed — to recognize
    // "this value change is a restore, not a genuine user pick" and skip the popup-open/
    // clearFailureDetails side effects entirely, regardless of any other state.
    private boolean restoringMotivo;

    // Kept as a field (not a local in the cell factory lambda) so setFailureDetails()/
    // clearFailureDetails() can force an immediate re-render — ComboBox never re-invokes the
    // button cell on its own just because failureCause changed externally; only a genuine
    // value change does that. updateItem() is protected on Cell, so the refresh trigger has
    // to be a method on this class itself, not called on the field from outside.
    private class MotivoCell extends ListCell<String> {
        private final boolean isButtonCell;
        MotivoCell(boolean isButtonCell) {
            this.isButtonCell = isButtonCell;
        }
        @Override
        protected void updateItem(String motivo, boolean empty) {
            super.updateItem(motivo, empty);
            if (empty || motivo == null) {
                // A custom button cell takes over promptText rendering entirely — JavaFX only
                // auto-shows it for the default internal cell. Only the button cell (not the
                // popup list's own empty/padding rows) should ever show it here.
                setText(isButtonCell ? cmbMotivo.getPromptText() : null);
            } else {
                setText(motivoDisplayText(motivo));
            }
        }
        void refresh() {
            updateItem(getItem(), getItem() == null);
        }
    }

    private MotivoCell motivoButtonCell;

    public void initialize() {
        lblFailureSummary.setTooltip(new Tooltip("Editar detalles de Falla"));

        cmbMotivo.setCellFactory(lv -> new MotivoCell(false));
        motivoButtonCell = new MotivoCell(true);
        cmbMotivo.setButtonCell(motivoButtonCell);

        userNoteTypeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                oldToggle.setSelected(true);
                return;
            }
            updateMotivoVisibility((ToggleButton) newToggle);
        });

        cmbMotivo.valueProperty().addListener((obs, old, motivo) -> {
            // A programmatic reload (loadMotivoOptions(), which sets restoringMotivo for its
            // *entire* body — not just the final setValue()) must be completely invisible to
            // this listener, including the lastMotivoByType bookkeeping below: setItems() alone
            // can fire this listener with an intermediate null on a fully-skinned ComboBox (a
            // real Stage/Scene is required to reproduce this — a bare FXMLLoader.load() in a
            // test never installs a Skin, which is exactly why this slipped through testing
            // once already). Without this early return, that intermediate null would overwrite
            // the "Falla" entry in lastMotivoByType before loadMotivoOptions() ever reads it
            // back, silently losing the remembered selection.
            if (restoringMotivo) return;

            ToggleButton currentType = (ToggleButton) userNoteTypeGroup.getSelectedToggle();
            if (currentType != null) lastMotivoByType.put(currentType, motivo);

            // Falla-detail handling only ever applies to Devolución's own Motivo — restoring a
            // different type's remembered Motivo (e.g. switching to Entrega) must not touch
            // failureCause/failureDetails, since those now persist across type switches too.
            if (!btnTypeDevolucion.isSelected()) return;

            if (isFailureTriggerMotivo(motivo)) {
                if (!failureConfirmed) {
                    openFailureDetailDialog();
                }
            } else {
                clearFailureDetails();
            }
            refreshFailureIndicators();
        });

        txtUserName.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.length() > USER_NAME_MAX_LENGTH) return null;
            return newText.isEmpty() || newText.matches("[\\p{L} ]*") ? change : null;
        }));
        txtUserDni.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            return newText.length() <= 8 && newText.matches("\\d*") ? change : null;
        }));
        txtAreaEvento.setTextFormatter(new TextFormatter<>(change ->
            change.getControlNewText().length() <= AREA_EVENTO_MAX_LENGTH ? change : null));

        updateMotivoVisibility(btnTypeEntrega);
        btnTypeEntrega.setSelected(true);
    }

    private boolean isFailureTriggerMotivo(String motivo) {
        return ConfigService.getInstance().getConfig().failureTriggerMotivo.equals(motivo);
    }

    private String motivoDisplayText(String motivo) {
        if (btnTypeDevolucion.isSelected() && isFailureTriggerMotivo(motivo) && failureConfirmed) {
            return motivo + " - " + failureCause;
        }
        return motivo;
    }

    private void updateMotivoVisibility(ToggleButton selected) {
        boolean showMotivo = selected == btnTypeEntrega
                          || selected == btnTypeDevolucion
                          || selected == btnTypeFinContrato;
        boolean showFechaTentativa = selected == btnTypePrestamo;

        vboxMotivo.setVisible(showMotivo);
        vboxMotivo.setManaged(showMotivo);
        vboxFechaTentativa.setVisible(showFechaTentativa);
        vboxFechaTentativa.setManaged(showFechaTentativa);
        vboxAreaEvento.setVisible(showFechaTentativa);
        vboxAreaEvento.setManaged(showFechaTentativa);

        if (showMotivo) {
            String key = selected == btnTypeDevolucion ? "devolucion"
                       : selected == btnTypeFinContrato ? "finDeContrato"
                       : "entrega";
            loadMotivoOptions(key, selected);
        }
        if (showFechaTentativa && dtpFechaTentativa.getValue() == null) {
            dtpFechaTentativa.setValue(nextWorkingDay());
        }
        refreshFailureIndicators();
    }

    private LocalDate nextWorkingDay() {
        LocalDate next = LocalDate.now().plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next;
    }

    private void loadMotivoOptions(String key, ToggleButton forType) {
        List<String> options = ConfigService.getInstance().getConfig().motivoOptions.getOrDefault(key, List.of());
        // Captured before touching cmbMotivo at all, and restoringMotivo guards the whole
        // reload (setItems included) — see the long comment on the valueProperty listener for why.
        String remembered = lastMotivoByType.get(forType);
        restoringMotivo = true;
        try {
            cmbMotivo.setItems(FXCollections.observableArrayList(options));
            if (remembered != null && options.contains(remembered)) {
                cmbMotivo.setValue(remembered);
            } else {
                cmbMotivo.getSelectionModel().clearSelection();
            }
        } finally {
            restoringMotivo = false;
        }
    }

    // Updates both the "⚙ Editar" indicator's visibility and the Motivo combobox's own
    // displayed text (button cell) — the ComboBox doesn't automatically refresh its button
    // cell just because failureCause/failureConfirmed changed externally, so this forces it.
    private void refreshFailureIndicators() {
        boolean show = btnTypeDevolucion.isSelected()
            && failureConfirmed
            && isFailureTriggerMotivo(cmbMotivo.getValue());
        lblFailureSummary.setManaged(show);
        lblFailureSummary.setVisible(show);
        motivoButtonCell.refresh();
    }

    @FXML
    private void handleEditFailureDetails() {
        openFailureDetailDialog();
    }

    public String getSelectedNoteType() {
        if (userNoteTypeGroup.getSelectedToggle() == null) return "ENTREGA";
        return ((ToggleButton) userNoteTypeGroup.getSelectedToggle()).getText();
    }

    public String getMotivo() {
        return cmbMotivo.getValue();
    }

    public String getFechaTentativa() {
        LocalDate date = dtpFechaTentativa.getValue();
        return date != null ? date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "";
    }

    public String getAreaEvento() { return txtAreaEvento.getText().trim(); }

    public boolean validateAndShowErrors() {
        boolean valid = true;
        if (getUserName().isEmpty() || getUserDni().isEmpty()) {
            triggerFeedback("Nombre y DNI son obligatorios", "#ef4444");
            valid = false;
        } else if (!NAME_PATTERN.matcher(getUserName()).matches()) {
            triggerFeedback("El nombre solo puede contener letras y espacios simples", "#ef4444");
            valid = false;
        } else if (!DNI_PATTERN.matcher(getUserDni()).matches()) {
            triggerFeedback("El DNI debe tener 7 u 8 dígitos, sin puntos", "#ef4444");
            valid = false;
        }
        if (!btnTypePrestamo.isSelected() && (getMotivo() == null || getMotivo().isEmpty())) {
            triggerLabelFeedback(lblMotivoStatus, "El motivo es obligatorio", "#ef4444");
            valid = false;
        }
        if (btnTypeDevolucion.isSelected() && isFailureTriggerMotivo(getMotivo())
                && (failureCause == null || failureCause.isBlank())) {
            triggerLabelFeedback(lblMotivoStatus, "Debe completar los detalles de la falla", "#ef4444");
            valid = false;
        }
        if (btnTypePrestamo.isSelected()) {
            LocalDate date = dtpFechaTentativa.getValue();
            if (date == null) {
                triggerLabelFeedback(lblFechaTentativaStatus, "Ingrese fecha tentativa", "#ef4444");
                valid = false;
            } else if (date.isBefore(LocalDate.now())) {
                triggerLabelFeedback(lblFechaTentativaStatus, "La fecha no puede ser pasada", "#ef4444");
                valid = false;
            }
        }
        return valid;
    }

    private void triggerLabelFeedback(Label label, String message, String hexColor) {
        label.setText(message);
        label.setStyle("-fx-text-fill: " + hexColor + "; -fx-font-weight: bold;");
        label.setOpacity(1.0);
        // Same timing as triggerFeedback()'s FEEDBACK_HOLD/FEEDBACK_FADE below, so the Motivo/
        // Fecha error and the recipient-field error (both shown when Generar Nota is clicked)
        // fade away together instead of at visibly different speeds.
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

    public String getFailureCause() { return failureCause; }
    public String getFailureDetails() { return failureDetails; }

    public void setFailureDetails(String cause, String details) {
        this.failureCause = cause;
        this.failureDetails = details;
        this.failureConfirmed = true;
        refreshFailureIndicators();
    }

    private void clearFailureDetails() {
        failureCause = null;
        failureDetails = null;
        failureConfirmed = false;
        refreshFailureIndicators();
    }

    public void onFailureDialogCancelled() {
        // Only revert Motivo when cancelling a brand-new Falla selection that was never
        // confirmed. Cancelling out of an edit — reopened via the "Editar" link on an
        // already-saved Falla — must leave the existing data untouched.
        if (!failureConfirmed) {
            cmbMotivo.getSelectionModel().clearSelection();
        }
    }

    private void openFailureDetailDialog() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                "/com/bunshock/note_app_for_it_frontend/views/FailureDetailView.fxml"));
            Parent root = loader.load();
            FailureDetailController ctrl = loader.getController();
            ctrl.setParentController(this);
            ctrl.prefill(failureCause, failureDetails);

            Stage stage = new Stage();
            stage.initStyle(StageStyle.TRANSPARENT);
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene dialogScene = new Scene(root);
            dialogScene.setFill(Color.TRANSPARENT);
            dialogScene.getStylesheets().add(getClass().getResource(
                "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());
            stage.setScene(dialogScene);

            // Same setOnShown-based positioning as showUserSelectionDialog() (no fixed Scene
            // size here either) — anchored to cmbMotivo, to its right, instead of btnBuscarAD.
            stage.setOpacity(0);
            stage.setOnShown(e -> {
                javafx.geometry.Bounds combo = cmbMotivo.localToScreen(cmbMotivo.getBoundsInLocal());
                javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
                double x = Math.min(combo.getMaxX() + 12, screen.getMaxX() - stage.getWidth());
                double y = Math.min(combo.getMinY(), screen.getMaxY() - stage.getHeight());
                stage.setX(Math.max(screen.getMinX(), x));
                stage.setY(Math.max(screen.getMinY(), y));
                stage.setOpacity(1);
            });
            stage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getUserDni() { return txtUserDni.getText().trim(); }
    public String getUserName() { return txtUserName.getText().trim(); }
    public String getUserAccount() { return txtUserAccount.getText().trim(); }
    public String getUserEmail() {
        String raw = lblUserEmail.getText();
        return raw.startsWith("email: ") ? raw.substring(7).trim() : raw.trim();
    }

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
                    // Keep the button disabled and the spinner running — the multi-result
                    // popup is itself the continuation of this search, not a new idle state.
                    // onAdSelectionDialogClosed() (called by ADUserSelectionController on
                    // either selection or cancel) is what restores normal button state.
                    showUserSelectionDialog(finalResults);
                }
            });
        }, "ad-search");
        t.setDaemon(true);
        t.start();
    }

    /** Called by ADUserSelectionController when the multi-result popup closes, however it closed. */
    public void onAdSelectionDialogClosed() {
        btnBuscarAD.setDisable(false);
        hideSearchingState(adSearchOriginalText);
    }

    /**
     * Locks btnBuscarAD's width to its current (pre-search) rendered size before shrinking
     * the text, so swapping to the shorter "Buscando" never resizes/re-centers the button.
     * The spinner lives beside the button (spinnerADSearch, FXML), not as the button's own
     * graphic — putting it inside the button alongside changing text caused the content
     * group's centered midpoint to shift each time the text changed.
     */
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
            dialogScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            dialogScene.getStylesheets().add(getClass().getResource(
                "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());
            stage.setScene(dialogScene);

            // Scene has no fixed size (unlike ItemDialogView), so wait for the layout pass to
            // finish (onShown) before reading stage width/height, instead of racing it post-show().
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

    // Shared by triggerFeedback()'s label fade and highlightFields()'s border fade so the
    // two always stay in sync — they used to drift because the border was cleared with a
    // flat setStyle("") instead of an animated fade, making it look like an on/off snap
    // next to the label's smooth opacity fade.
    private static final Duration FEEDBACK_HOLD = Duration.millis(2000);
    private static final Duration FEEDBACK_FADE = Duration.millis(650);

    // The .form-input-main CSS class's default (non-focused) border color — the border
    // fade interpolates toward this exact color, not toward transparent, so the handoff to
    // resetFieldStyles()'s empty style (which falls back to this same CSS default) is
    // invisible instead of popping from "fully transparent" to "solid grey".
    private static final Color DEFAULT_BORDER_COLOR = Color.web("#cbd5e1");

    private Transition borderFade;

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

    public void highlightFields(String hexColor) {
        if (borderFade != null) {
            borderFade.stop();
        }

        applyBorderStyle(hexColor);

        Color from = Color.web(hexColor);

        Transition fade = new Transition() {
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

    public void clearAllFields() {
        handleClearUserFields();
        lastMotivoByType.clear();
        cmbMotivo.getSelectionModel().clearSelection();
        dtpFechaTentativa.setValue(nextWorkingDay());
        txtAreaEvento.clear();
        clearFailureDetails();
    }
}
