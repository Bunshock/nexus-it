package com.bunshock.note_app_for_it_frontend.controllers;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.bunshock.note_app_for_it_frontend.models.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.AdminSession;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.DatePicker;
import javafx.scene.control.MenuButton;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

// "Historial de Préstamos" — a Préstamo-scoped sibling of HistoryController, without any GLPI
// sync affordance (Préstamos track return status instead — see PrestamoDetailController). Row
// coloring/menu-filter/detail-popup wiring intentionally duplicates HistoryController's shape,
// keyed on the new return-status counts instead of GLPI ones — see CLAUDE.md's "Préstamos section".
public class PrestamoHistoryController {

    @FXML private DatePicker dpFrom;
    @FXML private DatePicker dpTo;
    @FXML private MenuButton mnuReturnStatus;
    @FXML private TextField  txtRecipientSearch;
    @FXML private TextField  txtAuthorSearch;

    @FXML private TableView<NoteReport>           tblPrestamos;
    @FXML private TableColumn<NoteReport, String> colPDate;
    @FXML private TableColumn<NoteReport, String> colPAuthor;
    @FXML private TableColumn<NoteReport, String> colPRecipient;
    @FXML private TableColumn<NoteReport, String> colPItems;
    @FXML private TableColumn<NoteReport, String> colPStatus;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter MOTIVO_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Same case/accent variants SqliteHistoryService.isPrestamo() tolerates.
    private static final List<String> PRESTAMO_PROFILE_TYPES = List.of("PRÉSTAMO", "PRESTAMO", "Préstamo");

    private static final List<String> RETURN_STATUS_OPTIONS =
        List.of("Pendiente", "Devuelto", "Perdido", "Mixto", "Vencido");

    private final Set<String> selReturnStatuses = new LinkedHashSet<>();
    private boolean suppressCallbacks = false;

    public void initialize() {
        setupTable();
        populateMenu(mnuReturnStatus, RETURN_STATUS_OPTIONS, selReturnStatuses, this::autoSearch);
        loadPrestamos(buildFilter());
    }

    /** Reloads with whatever filters are currently set — called by PrestamosController every time
     * the Préstamos section's Historial tab is shown, same staleness fix as HistoryController.refresh(). */
    public void refresh() {
        loadPrestamos(buildFilter());
    }

    @FXML
    private void handleSearch() {
        loadPrestamos(buildFilter());
    }

    @FXML
    private void handleClearFilters() {
        suppressCallbacks = true;
        dpFrom.setValue(null);
        dpTo.setValue(null);
        txtRecipientSearch.clear();
        txtAuthorSearch.clear();
        selReturnStatuses.clear();
        suppressCallbacks = false;
        populateMenu(mnuReturnStatus, RETURN_STATUS_OPTIONS, selReturnStatuses, this::autoSearch);
        loadPrestamos(buildFilter());
    }

    private void autoSearch() {
        loadPrestamos(buildFilter());
    }

    private HistoryFilter buildFilter() {
        HistoryFilter f = new HistoryFilter();
        f.setFromDate(dpFrom.getValue());
        f.setToDate(dpTo.getValue());
        f.setProfileTypes(PRESTAMO_PROFILE_TYPES);
        String recipient = txtRecipientSearch.getText();
        if (recipient != null && !recipient.isBlank()) f.setRecipientSearch(recipient);
        String author = txtAuthorSearch.getText();
        if (author != null && !author.isBlank()) f.setAuthorSearch(author);
        return f;
    }

    // ── Filter menu (duplicated from HistoryController.populateMenu/updateMenuLabel) ──────────

    private void populateMenu(MenuButton btn, List<String> options, Set<String> selected, Runnable onChange) {
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
                if (lock[0] || suppressCallbacks) return;
                lock[0] = true;
                if (newVal) {
                    selected.add(opt);
                    todasChk.setSelected(false);
                } else {
                    selected.remove(opt);
                    if (selected.isEmpty()) todasChk.setSelected(true);
                }
                lock[0] = false;
                updateMenuLabel(btn, selected);
                if (!suppressCallbacks) onChange.run();
            });
        }

        todasChk.selectedProperty().addListener((obs, old, newVal) -> {
            if (lock[0] || suppressCallbacks || !newVal) return;
            lock[0] = true;
            selected.clear();
            btn.getItems().stream()
                .filter(it -> it instanceof CustomMenuItem)
                .map(it -> ((CustomMenuItem) it).getContent())
                .filter(n -> n instanceof CheckBox && n != todasChk)
                .forEach(n -> ((CheckBox) n).setSelected(false));
            lock[0] = false;
            updateMenuLabel(btn, selected);
            if (!suppressCallbacks) onChange.run();
        });

        updateMenuLabel(btn, selected);
    }

    private void updateMenuLabel(MenuButton btn, Set<String> selected) {
        if (selected.isEmpty()) {
            btn.setText("Todas");
        } else if (selected.size() == 1) {
            btn.setText(selected.iterator().next());
        } else {
            btn.setText(selected.size() + " seleccionados");
        }
    }

    // ── Table setup ───────────────────────────────────────────────────────────

    private void setupTable() {
        colPDate.setSortable(false);
        colPAuthor.setSortable(false);
        colPRecipient.setSortable(false);
        colPItems.setSortable(false);
        colPStatus.setSortable(false);

        colPDate.setCellValueFactory(d ->
            new SimpleStringProperty(d.getValue().getCreatedAt().format(FMT)));
        colPAuthor.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getAuthorName())));
        colPRecipient.setCellValueFactory(d ->
            new SimpleStringProperty(orEmpty(d.getValue().getRecipientDisplay())));
        colPItems.setCellValueFactory(d -> {
            NoteReport r = d.getValue();
            int assets = r.getAssetItemCount();
            int countables = r.getCountableItemCount();
            String label = assets > 0
                ? assets + "A" + (countables > 0 ? " / " + countables + "C" : "")
                : (countables > 0 ? countables + "C" : "—");
            return new SimpleStringProperty(label);
        });
        colPStatus.setCellValueFactory(d -> new SimpleStringProperty(returnStatusLabel(d.getValue())));

        tblPrestamos.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(NoteReport report, boolean empty) {
                super.updateItem(report, empty);
                setStyle(empty || report == null ? "" : computeRowStyle(report));
            }
        });

        tblPrestamos.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                NoteReport selected = tblPrestamos.getSelectionModel().getSelectedItem();
                if (selected != null) openDetail(selected.getId());
            }
        });
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadPrestamos(HistoryFilter filter) {
        List<NoteReport> reports = ServiceLocator.getInstance().getHistoryService().getFiltered(filter);
        if (!selReturnStatuses.isEmpty()) {
            reports = reports.stream()
                .filter(r -> selReturnStatuses.stream().anyMatch(s -> matchesReturnStatusLabel(r, s)))
                .toList();
        }
        tblPrestamos.setItems(FXCollections.observableArrayList(reports));
    }

    // ── Row color + overdue ───────────────────────────────────────────────────

    private String computeRowStyle(NoteReport r) {
        int total = r.getReturnPendingItemCount() + r.getReturnedItemCount() + r.getLostItemCount();
        String background;
        if (total == 0) {
            background = "-fx-background-color: #f1f5f9;";
        } else {
            int pending  = r.getReturnPendingItemCount();
            int returned = r.getReturnedItemCount();
            int lost     = r.getLostItemCount();

            if (pending  == total) background = "-fx-background-color: rgba(251,146,60,0.18);";
            else if (returned == total) background = "-fx-background-color: rgba(34,197,94,0.18);";
            else if (lost == total) background = "-fx-background-color: rgba(239,68,68,0.18);";
            else {
                StringBuilder g = new StringBuilder("-fx-background-color: linear-gradient(to right");
                double pos = 0;
                pos = appendSlice(g, lost, total, pos, "239,68,68");
                pos = appendSlice(g, pending, total, pos, "251,146,60");
                appendSlice(g, returned, total, pos, "34,197,94");
                g.append(");");
                background = g.toString();
            }
        }

        if (isOverdue(r)) {
            background += " -fx-border-color: #ef4444; -fx-border-width: 0 0 2 0;";
        }
        return background;
    }

    private double appendSlice(StringBuilder g, int count, int total, double pos, String rgb) {
        if (count <= 0) return pos;
        double end = pos + (double) count / total * 100;
        g.append(String.format(Locale.ROOT, ", rgba(%s,0.25) %.2f%%", rgb, pos));
        g.append(String.format(Locale.ROOT, ", rgba(%s,0.25) %.2f%%", rgb, end));
        return end;
    }

    /** Overdue = still has pending items and the tentative return date (stored in Motivo,
     * dd/MM/yyyy — see CLAUDE.md's "Note types and profiles") has already passed. */
    private boolean isOverdue(NoteReport r) {
        if (r.getReturnPendingItemCount() <= 0) return false;
        LocalDate tentative = parseMotivoDate(r.getMotivo());
        return tentative != null && tentative.isBefore(LocalDate.now());
    }

    private LocalDate parseMotivoDate(String motivo) {
        if (motivo == null || motivo.isBlank()) return null;
        try {
            return LocalDate.parse(motivo, MOTIVO_DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String returnStatusLabel(NoteReport r) {
        int total = r.getReturnPendingItemCount() + r.getReturnedItemCount() + r.getLostItemCount();
        if (total == 0) return "—";
        int pending  = r.getReturnPendingItemCount();
        int returned = r.getReturnedItemCount();
        int lost     = r.getLostItemCount();
        String base;
        if (pending == total) base = "Pendiente";
        else if (returned == total) base = "Devuelto";
        else if (lost == total) base = "Perdido";
        else base = "Mixto";
        return isOverdue(r) ? base + " (Vencido)" : base;
    }

    private boolean matchesReturnStatusLabel(NoteReport r, String label) {
        int total = r.getReturnPendingItemCount() + r.getReturnedItemCount() + r.getLostItemCount();
        int pending  = r.getReturnPendingItemCount();
        int returned = r.getReturnedItemCount();
        int lost     = r.getLostItemCount();
        return switch (label) {
            case "Pendiente" -> pending > 0;
            case "Devuelto"  -> returned > 0;
            case "Perdido"   -> lost > 0;
            case "Mixto"     -> total > 0 && pending != total && returned != total && lost != total;
            case "Vencido"   -> isOverdue(r);
            default          -> true;
        };
    }

    // ── Detail popup ──────────────────────────────────────────────────────────

    private void openDetail(int reportId) {
        NoteReport full = ServiceLocator.getInstance().getHistoryService().getById(reportId);
        if (full == null) return;
        try {
            PrestamoDetailController.open(full, AdminSession.getInstance().isActive(),
                tblPrestamos.getScene().getWindow(),
                () -> loadPrestamos(buildFilter()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }
}
