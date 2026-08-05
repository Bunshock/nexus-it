package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import com.bunshock.note_app_for_it_frontend.controllers.PrestamoHistoryController;
import com.bunshock.note_app_for_it_frontend.models.HistoryFilter;
import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.MockEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.TechnicianSessionService;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// PrestamoHistoryController.initialize() reaches ServiceLocator directly (same "impractical to
// exercise in isolation" gap already accepted for MainController/HistoryController elsewhere in
// this suite), but computeRowStyle()/approvalStatusColor()/isOverdue() are pure functions of a
// NoteReport with no field dependencies — same reflection-on-a-bare-instance approach
// HistoryControllerTest already uses.
class PrestamoHistoryControllerTest {

    private static final DateTimeFormatter MOTIVO_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PrestamoHistoryController controller = new PrestamoHistoryController();

    private String computeRowStyle(NoteReport r) throws Exception {
        Method m = PrestamoHistoryController.class.getDeclaredMethod("computeRowStyle", NoteReport.class);
        m.setAccessible(true);
        return (String) m.invoke(controller, r);
    }

    private String approvalStatusColor(NoteReport r) throws Exception {
        Method m = PrestamoHistoryController.class.getDeclaredMethod("approvalStatusColor", NoteReport.class);
        m.setAccessible(true);
        return (String) m.invoke(controller, r);
    }

    private NoteReport reportWith(int pending, int returned, int lost) {
        NoteReport r = new NoteReport();
        r.setReturnPendingItemCount(pending);
        r.setReturnedItemCount(returned);
        r.setLostItemCount(lost);
        // APPROVED so plain return-status/overdue tests aren't coupled to approval status —
        // NoteReport defaults to PENDING otherwise.
        r.setApprovalStatus("APPROVED");
        return r;
    }

    // ── approvalStatusColor (dedicated status column, colPApproval) ──────────
    //
    // Used to be an always-on left border composed into computeRowStyle()'s return value —
    // replaced, same reasoning as HistoryController's colGApproval: any left/right
    // border shifts row content relative to the column headers, which a real, narrow TableColumn
    // doesn't, since its header reserves the exact same width as its cells automatically.

    @Test
    void approvalStatusColorPendingIsOrange() throws Exception {
        NoteReport r = reportWith(1, 0, 0);
        r.setApprovalStatus("PENDING");
        assertEquals("#f97316", approvalStatusColor(r));
    }

    @Test
    void approvalStatusColorRechazadoIsRed() throws Exception {
        NoteReport r = reportWith(1, 0, 0);
        r.setApprovalStatus("RECHAZADO");
        assertEquals("#ef4444", approvalStatusColor(r));
    }

    @Test
    void approvalStatusColorApprovedIsGreen() throws Exception {
        assertEquals("#22c55e", approvalStatusColor(reportWith(0, 0, 0)));
    }

    // ── computeRowStyle (background + overdue bottom-border only, no approval border) ──────────

    @Test
    void computeRowStyleApprovedNotOverdueAddsNoBorder() throws Exception {
        String style = computeRowStyle(reportWith(0, 0, 0));
        assertFalse(style.contains("-fx-border"));
    }

    @Test
    void computeRowStyleOverdueAddsBottomBorderAlone() throws Exception {
        NoteReport r = reportWith(1, 0, 0);
        r.setMotivo(LocalDate.now().minusDays(5).format(MOTIVO_DATE_FMT));

        String style = computeRowStyle(r);

        assertTrue(style.contains("-fx-border-color: #ef4444;"));
        assertTrue(style.contains("-fx-border-width: 0 0 2 0;"));
    }

    @Test
    void computeRowStyleNotOverdueAddsNoBorderRegardlessOfApprovalStatus() throws Exception {
        NoteReport r = reportWith(1, 0, 0);
        r.setApprovalStatus("PENDING");
        assertFalse(computeRowStyle(r).contains("-fx-border"));
    }

    // ── Sede filter defaults to the technician's own assigned Sede ───────────────
    // Mirrors HistoryControllerTest's own coverage of this same behavior.

    @SuppressWarnings("unchecked")
    private Set<String> selSedesField(PrestamoHistoryController c) throws Exception {
        Field f = PrestamoHistoryController.class.getDeclaredField("selSedes");
        f.setAccessible(true);
        return (Set<String>) f.get(c);
    }

    private void invokeResetSedeFilterToDefault(PrestamoHistoryController c) throws Exception {
        Method m = PrestamoHistoryController.class.getDeclaredMethod("resetSedeFilterToDefault");
        m.setAccessible(true);
        m.invoke(c);
    }

    private void setTechnicianSede(String sedeName) throws Exception {
        Field f = TechnicianSessionService.class.getDeclaredField("sedeName");
        f.setAccessible(true);
        f.set(TechnicianSessionService.getInstance(), sedeName);
    }

    @Test
    void resetSedeFilterToDefaultAddsTechniciansOwnSedeWhenAssigned() throws Exception {
        setTechnicianSede("Campus Norte");
        try {
            PrestamoHistoryController c = new PrestamoHistoryController();
            invokeResetSedeFilterToDefault(c);
            assertEquals(Set.of("Campus Norte"), selSedesField(c));
        } finally {
            setTechnicianSede(null);
        }
    }

    @Test
    void resetSedeFilterToDefaultLeavesEmptyWhenNoSedeAssigned() throws Exception {
        setTechnicianSede(null);
        PrestamoHistoryController c = new PrestamoHistoryController();
        invokeResetSedeFilterToDefault(c);
        assertTrue(selSedesField(c).isEmpty());
    }

    @Test
    void initSedeMenuOptionsComeFromEquipmentServiceCatalogNotHistory() throws Exception {
        var mockEquipment = new MockEquipmentService();
        mockEquipment.addSede("Campus Norte");
        mockEquipment.addSede("Campus Sur");
        ServiceLocator.getInstance().setEquipmentService(mockEquipment);

        PrestamoHistoryController c = new PrestamoHistoryController();
        Field mnuSedeField = PrestamoHistoryController.class.getDeclaredField("mnuSede");
        mnuSedeField.setAccessible(true);
        mnuSedeField.set(c, new javafx.scene.control.MenuButton());

        Method m = PrestamoHistoryController.class.getDeclaredMethod("initSedeMenu");
        m.setAccessible(true);
        m.invoke(c);

        javafx.scene.control.MenuButton mnuSede = (javafx.scene.control.MenuButton) mnuSedeField.get(c);
        java.util.List<String> itemLabels = mnuSede.getItems().stream()
            .filter(it -> it instanceof javafx.scene.control.CustomMenuItem)
            .map(it -> ((javafx.scene.control.CustomMenuItem) it).getContent())
            .filter(n -> n instanceof javafx.scene.control.CheckBox)
            .map(n -> ((javafx.scene.control.CheckBox) n).getText())
            .toList();

        assertTrue(itemLabels.contains("Campus Norte"));
        assertTrue(itemLabels.contains("Campus Sur"));
    }

    // ── populateMenu "Todas" checkbox (duplicated from HistoryController's own version) ──────

    @Test
    void populateMenuPreventsUncheckingTodasWhenNothingElseSelected() throws Exception {
        javafx.scene.control.MenuButton btn = new javafx.scene.control.MenuButton();
        Set<String> selected = new java.util.LinkedHashSet<>();
        Method m = PrestamoHistoryController.class.getDeclaredMethod("populateMenu",
            javafx.scene.control.MenuButton.class, java.util.List.class, Set.class, Runnable.class);
        m.setAccessible(true);
        m.invoke(controller, btn, java.util.List.of("A", "B"), selected, (Runnable) () -> {});

        javafx.scene.control.CheckBox todasChk = (javafx.scene.control.CheckBox)
            ((javafx.scene.control.CustomMenuItem) btn.getItems().get(0)).getContent();
        assertTrue(todasChk.isSelected(), "Todas starts checked when nothing is selected");

        todasChk.setSelected(false);

        assertTrue(todasChk.isSelected(),
            "unchecking Todas with nothing else selected must snap back — direct user report that "
                + "it could otherwise be left unchecked while the filter still silently matched everything");
        assertTrue(selected.isEmpty(), "the underlying filter set is untouched by the snap-back");
    }

    @Test
    void populateMenuStillTurnsTodasOffWhenAnItemIsSelected() throws Exception {
        javafx.scene.control.MenuButton btn = new javafx.scene.control.MenuButton();
        Set<String> selected = new java.util.LinkedHashSet<>();
        Method m = PrestamoHistoryController.class.getDeclaredMethod("populateMenu",
            javafx.scene.control.MenuButton.class, java.util.List.class, Set.class, Runnable.class);
        m.setAccessible(true);
        m.invoke(controller, btn, java.util.List.of("A", "B"), selected, (Runnable) () -> {});

        javafx.scene.control.CheckBox todasChk = (javafx.scene.control.CheckBox)
            ((javafx.scene.control.CustomMenuItem) btn.getItems().get(0)).getContent();
        javafx.scene.control.CheckBox itemA = (javafx.scene.control.CheckBox)
            ((javafx.scene.control.CustomMenuItem) btn.getItems().get(2)).getContent();

        itemA.setSelected(true);

        assertFalse(todasChk.isSelected(), "selecting a real item still turns Todas off normally");
        assertEquals(Set.of("A"), selected);
    }

    // ── updatePendingReturnsLabel (filter-row badge — counts *notes* with pending returns
    // among whatever's currently loaded, not the sidebar's global lblPrestamosBadge count) ──────

    private javafx.scene.control.Label invokeUpdatePendingReturnsLabel(List<NoteReport> reports) throws Exception {
        PrestamoHistoryController c = new PrestamoHistoryController();
        javafx.scene.control.Label lbl = new javafx.scene.control.Label();
        Field f = PrestamoHistoryController.class.getDeclaredField("lblPendingReturns");
        f.setAccessible(true);
        f.set(c, lbl);

        Method m = PrestamoHistoryController.class.getDeclaredMethod("updatePendingReturnsLabel", List.class);
        m.setAccessible(true);
        m.invoke(c, reports);
        return lbl;
    }

    @Test
    void updatePendingReturnsLabelHiddenWhenNoNotesHavePendingReturns() throws Exception {
        NoteReport allReturned = reportWith(0, 2, 0);
        javafx.scene.control.Label lbl = invokeUpdatePendingReturnsLabel(List.of(allReturned));
        assertFalse(lbl.isVisible());
        assertFalse(lbl.isManaged());
    }

    @Test
    void updatePendingReturnsLabelShowsCountOfNotesWithAtLeastOnePendingItem() throws Exception {
        NoteReport onePending = reportWith(1, 0, 0);
        NoteReport mixedStillPending = reportWith(1, 1, 0);
        NoteReport noneReturned = reportWith(0, 3, 0);

        javafx.scene.control.Label lbl =
            invokeUpdatePendingReturnsLabel(List.of(onePending, mixedStillPending, noneReturned));

        assertTrue(lbl.isVisible());
        assertTrue(lbl.isManaged());
        assertTrue(lbl.getText().contains("2"), "counts notes, not raw pending items: " + lbl.getText());
    }

    // A note still awaiting approval (or rejected) isn't a real loan yet — its pending return
    // must not count until an admin approves it. Direct user requirement.
    @Test
    void updatePendingReturnsLabelExcludesNotesNotYetApproved() throws Exception {
        NoteReport approvedPending = reportWith(1, 0, 0);
        NoteReport stillAwaitingApproval = reportWith(1, 0, 0);
        stillAwaitingApproval.setApprovalStatus("PENDING");
        NoteReport rejected = reportWith(1, 0, 0);
        rejected.setApprovalStatus("RECHAZADO");

        javafx.scene.control.Label lbl = invokeUpdatePendingReturnsLabel(
            List.of(approvedPending, stillAwaitingApproval, rejected));

        assertTrue(lbl.getText().contains("1"), "only the APPROVED note should count: " + lbl.getText());
    }

    // ── updatePendingApprovalLabel (second, red pill — approval-pending, distinct concern) ─────

    private javafx.scene.control.Label invokeUpdatePendingApprovalLabel(List<NoteReport> reports) throws Exception {
        PrestamoHistoryController c = new PrestamoHistoryController();
        javafx.scene.control.Label lbl = new javafx.scene.control.Label();
        Field f = PrestamoHistoryController.class.getDeclaredField("lblPendingApproval");
        f.setAccessible(true);
        f.set(c, lbl);

        Method m = PrestamoHistoryController.class.getDeclaredMethod("updatePendingApprovalLabel", List.class);
        m.setAccessible(true);
        m.invoke(c, reports);
        return lbl;
    }

    @Test
    void updatePendingApprovalLabelHiddenWhenNothingIsPending() throws Exception {
        NoteReport approved = reportWith(1, 0, 0);
        NoteReport rejected = reportWith(0, 1, 0);
        rejected.setApprovalStatus("RECHAZADO");

        javafx.scene.control.Label lbl = invokeUpdatePendingApprovalLabel(List.of(approved, rejected));
        assertFalse(lbl.isVisible());
        assertFalse(lbl.isManaged());
    }

    @Test
    void updatePendingApprovalLabelShowsCountOfNotesAwaitingApproval() throws Exception {
        NoteReport pending1 = reportWith(0, 0, 0);
        pending1.setApprovalStatus("PENDING");
        NoteReport pending2 = reportWith(1, 0, 0);
        pending2.setApprovalStatus("PENDING");
        NoteReport approved = reportWith(0, 1, 0);

        javafx.scene.control.Label lbl = invokeUpdatePendingApprovalLabel(List.of(pending1, pending2, approved));

        assertTrue(lbl.isVisible());
        assertTrue(lbl.isManaged());
        assertTrue(lbl.getText().contains("2"), "expected count of 2 in: " + lbl.getText());
    }

    // ── buildFilter (APROBACIÓN menu default — mirrors HistoryController's own version) ────────

    private HistoryFilter buildFilter(PrestamoHistoryController c) throws Exception {
        Method m = PrestamoHistoryController.class.getDeclaredMethod("buildFilter");
        m.setAccessible(true);
        return (HistoryFilter) m.invoke(c);
    }

    private void setField(PrestamoHistoryController c, String name, Object value) throws Exception {
        Field f = PrestamoHistoryController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(c, value);
    }

    @SuppressWarnings("unchecked")
    private Set<String> selApprovalStatusesField(PrestamoHistoryController c) throws Exception {
        Field f = PrestamoHistoryController.class.getDeclaredField("selApprovalStatuses");
        f.setAccessible(true);
        return (Set<String>) f.get(c);
    }

    private void invokeResetApprovalStatusFilterToDefault(PrestamoHistoryController c) throws Exception {
        Method m = PrestamoHistoryController.class.getDeclaredMethod("resetApprovalStatusFilterToDefault");
        m.setAccessible(true);
        m.invoke(c);
    }

    // Seeds every field buildFilter() reads — a bare `new PrestamoHistoryController()` never runs
    // initialize(), so these @FXML fields are otherwise null.
    private PrestamoHistoryController controllerWithFilterFieldsSeeded() throws Exception {
        PrestamoHistoryController c = new PrestamoHistoryController();
        setField(c, "dpFrom", new javafx.scene.control.DatePicker());
        setField(c, "dpTo", new javafx.scene.control.DatePicker());
        setField(c, "txtRecipientSearch", new javafx.scene.control.TextField());
        setField(c, "txtAuthorSearch", new javafx.scene.control.TextField());
        return c;
    }

    @Test
    void resetApprovalStatusFilterToDefaultSelectsPendingAndAprobada() throws Exception {
        PrestamoHistoryController c = controllerWithFilterFieldsSeeded();
        invokeResetApprovalStatusFilterToDefault(c);
        assertEquals(Set.of("Pendiente", "Aprobada"), selApprovalStatusesField(c));
    }

    @Test
    void buildFilterDefaultsToPendingAndApprovedWhenApprovalMenuAtItsDefault() throws Exception {
        PrestamoHistoryController c = controllerWithFilterFieldsSeeded();
        invokeResetApprovalStatusFilterToDefault(c);
        HistoryFilter f = buildFilter(c);
        assertEquals(List.of("PENDING", "APPROVED"), f.getApprovalStatuses());
    }

    // An empty selApprovalStatuses set is "Todas" (unchecked down from the default) — no filter
    // at all, RECHAZADO included. This is what makes "show only pending" (below) possible too.
    @Test
    void buildFilterAppliesNoApprovalFilterWhenApprovalMenuIsTodas() throws Exception {
        HistoryFilter f = buildFilter(controllerWithFilterFieldsSeeded());
        assertNull(f.getApprovalStatuses());
    }

    @Test
    void buildFilterAppliesExplicitApprovalSelectionShowingOnlyPending() throws Exception {
        PrestamoHistoryController c = controllerWithFilterFieldsSeeded();
        selApprovalStatusesField(c).add("Pendiente");
        HistoryFilter f = buildFilter(c);
        assertEquals(List.of("PENDING"), f.getApprovalStatuses());
    }

}
