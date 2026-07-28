package com.bunshock.note_app_for_it_frontend.controllers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.bunshock.note_app_for_it_frontend.models.ActionAuditEntry;
import com.bunshock.note_app_for_it_frontend.models.LoginAuditEntry;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;

/**
 * Read-only audit viewer — SUPERADMIN-only, see MainController's nav-visibility check. No
 * filters/export/sort UI by design (confirmed minimal v1 scope); nothing here can edit or
 * delete a row, matching IAuditService's append-only contract.
 */
public class AuditController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @FXML private ToggleGroup auditGroup;
    @FXML private ToggleButton btnLogins;
    @FXML private ToggleButton btnActions;
    @FXML private VBox rowLogins;
    @FXML private VBox rowActions;

    @FXML private TableView<LoginAuditEntry> tblLogins;
    @FXML private TableColumn<LoginAuditEntry, String> colLoginUser;
    @FXML private TableColumn<LoginAuditEntry, String> colLoginDate;
    @FXML private TableColumn<LoginAuditEntry, String> colLoginResult;

    @FXML private TableView<ActionAuditEntry> tblActions;
    @FXML private TableColumn<ActionAuditEntry, String> colActionUser;
    @FXML private TableColumn<ActionAuditEntry, String> colActionDate;
    @FXML private TableColumn<ActionAuditEntry, String> colActionType;
    @FXML private TableColumn<ActionAuditEntry, String> colActionDetail;

    public void initialize() {
        auditGroup.selectedToggleProperty().addListener((obs, old, next) -> {
            boolean showLogins = next == btnLogins;
            rowLogins.setVisible(showLogins);
            rowLogins.setManaged(showLogins);
            rowActions.setVisible(!showLogins);
            rowActions.setManaged(!showLogins);
        });

        colLoginUser.setSortable(false);
        colLoginDate.setSortable(false);
        colLoginResult.setSortable(false);
        colLoginUser.setCellValueFactory(d -> new SimpleStringProperty(orEmpty(d.getValue().getUsername())));
        colLoginDate.setCellValueFactory(d -> new SimpleStringProperty(formatTimestamp(d.getValue().getAttemptedAt())));
        colLoginResult.setCellValueFactory(d -> new SimpleStringProperty(loginResultLabel(d.getValue())));

        colActionUser.setSortable(false);
        colActionDate.setSortable(false);
        colActionType.setSortable(false);
        colActionDetail.setSortable(false);
        colActionUser.setCellValueFactory(d -> new SimpleStringProperty(orEmpty(d.getValue().getUsername())));
        colActionDate.setCellValueFactory(d -> new SimpleStringProperty(formatTimestamp(d.getValue().getOccurredAt())));
        colActionType.setCellValueFactory(d -> new SimpleStringProperty(eventTypeLabel(d.getValue().getEventType())));
        colActionDetail.setCellValueFactory(d -> new SimpleStringProperty(orEmpty(d.getValue().getDetails())));

        refresh();
    }

    /** Reloads both tables from the current data — called on every visit to this section, since
     *  ViewFactory caches the view for the session (same staleness fix as History's refresh()). */
    public void refresh() {
        tblLogins.setItems(FXCollections.observableArrayList(
            ServiceLocator.getInstance().getAuditService().getAllLogins()));
        tblActions.setItems(FXCollections.observableArrayList(
            ServiceLocator.getInstance().getAuditService().getAllActions()));
    }

    private String loginResultLabel(LoginAuditEntry entry) {
        if (entry.isSuccess()) return "✓ Exitoso";
        return "✗ " + failureReasonLabel(entry.getFailureReason());
    }

    private String failureReasonLabel(String reason) {
        if (reason == null) return "Desconocido";
        switch (reason) {
            case "INVALID_CREDENTIALS":    return "Credenciales inválidas";
            case "NOT_IN_ALLOWED_GROUP":   return "Sin permisos (grupo AD)";
            case "PROFILE_LOOKUP_FAILED":  return "Perfil no encontrado en AD";
            case "AD_UNREACHABLE":         return "AD no disponible";
            default:                      return reason;
        }
    }

    private String eventTypeLabel(String eventType) {
        if (eventType == null) return "—";
        switch (eventType) {
            case "GLPI_SYNC":             return "Sincronización GLPI";
            case "GLPI_REJECT":           return "Rechazo GLPI";
            case "PRESTAMO_RETURN":       return "Devolución Préstamo";
            case "PRESTAMO_LOST":         return "Préstamo perdido";
            case "DB_CONNECTION_CHANGED": return "Cambio de conexión DB";
            default:                      return eventType;
        }
    }

    private String formatTimestamp(String isoTimestamp) {
        if (isoTimestamp == null) return "";
        try { return LocalDateTime.parse(isoTimestamp).format(FMT); }
        catch (Exception e) { return isoTimestamp; }
    }

    private String orEmpty(String value) {
        return value != null ? value : "";
    }
}
