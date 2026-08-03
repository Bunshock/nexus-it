package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import com.bunshock.note_app_for_it_frontend.controllers.PrestamoHistoryController;
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
}
