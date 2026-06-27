package com.bunshock.note_app_for_it_frontend.controllers;

import java.time.format.DateTimeFormatter;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

public class HistoryController {

    @FXML private TableView<NoteReport> tblHistory;
    @FXML private TableColumn<NoteReport, String> colId;
    @FXML private TableColumn<NoteReport, String> colDate;
    @FXML private TableColumn<NoteReport, String> colProfile;
    @FXML private TableColumn<NoteReport, String> colRecipient;
    @FXML private TableColumn<NoteReport, String> colGlpi;

    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public void initialize() {
        colId.setCellValueFactory(d ->
            new SimpleStringProperty(String.valueOf(d.getValue().getId())));
        colDate.setCellValueFactory(d ->
            new SimpleStringProperty(d.getValue().getCreatedAt().format(FORMATTER)));
        colProfile.setCellValueFactory(d ->
            new SimpleStringProperty(d.getValue().getProfileType()));
        colRecipient.setCellValueFactory(d -> {
            NoteReport r = d.getValue();
            String name = r.getUserName() != null ? r.getUserName() : r.getProviderName();
            return new SimpleStringProperty(name != null ? name : "");
        });
        colGlpi.setCellValueFactory(d ->
            new SimpleStringProperty(d.getValue().isGlpiSynced() ? "Sí" : "No"));

        loadHistory();
    }

    @FXML
    private void handleRefresh() {
        loadHistory();
    }

    private void loadHistory() {
        List<NoteReport> reports = ServiceLocator.getInstance().getHistoryService().getAll();
        tblHistory.setItems(FXCollections.observableArrayList(reports));
    }
}
