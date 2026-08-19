package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import com.bunshock.note_app_for_it_frontend.controllers.envio.RemitoHistoryController;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.catalog.MockEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.core.ServiceLocator;
import com.bunshock.note_app_for_it_frontend.services.auth.TechnicianSessionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// RemitoHistoryController.initialize() reaches ServiceLocator directly (same "impractical to
// exercise in isolation" gap already accepted for PrestamoHistoryController/HistoryController
// elsewhere in this suite), but approvalStatusColor()/approvalStatusLabel() are pure functions of
// a NoteReport — same reflection-on-a-bare-instance approach those files already use.
class RemitoHistoryControllerTest {

    // initSedeMenuOptionsComeFromEquipmentServiceCatalog() below constructs a real MenuButton —
    // needs the FX toolkit initialized first, or it can permanently poison javafx.scene.control.
    // Labeled's static init for every later test class in this same JVM run.
    @BeforeAll
    static void initFxToolkit() {
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already running from a previous test class in this JVM
        }
    }

    private final RemitoHistoryController controller = new RemitoHistoryController();

    private String approvalStatusColor(NoteReport r) throws Exception {
        Method m = RemitoHistoryController.class.getDeclaredMethod("approvalStatusColor", NoteReport.class);
        m.setAccessible(true);
        return (String) m.invoke(controller, r);
    }

    private String approvalStatusLabel(NoteReport r) throws Exception {
        Method m = RemitoHistoryController.class.getDeclaredMethod("approvalStatusLabel", NoteReport.class);
        m.setAccessible(true);
        return (String) m.invoke(controller, r);
    }

    private NoteReport reportWithStatus(String status) {
        NoteReport r = new NoteReport();
        r.setApprovalStatus(status);
        return r;
    }

    @Test
    void approvalStatusColorPendingIsOrange() throws Exception {
        assertEquals("#f97316", approvalStatusColor(reportWithStatus("PENDING")));
    }

    @Test
    void approvalStatusColorRechazadoIsRed() throws Exception {
        assertEquals("#ef4444", approvalStatusColor(reportWithStatus("REJECTED")));
    }

    @Test
    void approvalStatusColorApprovedIsGreen() throws Exception {
        assertEquals("#22c55e", approvalStatusColor(reportWithStatus("APPROVED")));
    }

    @Test
    void approvalStatusLabelMapsKnownValues() throws Exception {
        assertEquals("Pendiente", approvalStatusLabel(reportWithStatus("PENDING")));
        assertEquals("Aprobada", approvalStatusLabel(reportWithStatus("APPROVED")));
        assertEquals("Rechazada", approvalStatusLabel(reportWithStatus("REJECTED")));
    }

    // ── Sede filter defaults to the technician's own assigned Sede ───────────────

    @SuppressWarnings("unchecked")
    private Set<String> selSedesField(RemitoHistoryController c) throws Exception {
        Field f = RemitoHistoryController.class.getDeclaredField("selSedes");
        f.setAccessible(true);
        return (Set<String>) f.get(c);
    }

    private void invokeResetSedeFilterToDefault(RemitoHistoryController c) throws Exception {
        Method m = RemitoHistoryController.class.getDeclaredMethod("resetSedeFilterToDefault");
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
            RemitoHistoryController c = new RemitoHistoryController();
            invokeResetSedeFilterToDefault(c);
            assertEquals(Set.of("Campus Norte"), selSedesField(c));
        } finally {
            setTechnicianSede(null);
        }
    }

    @Test
    void resetSedeFilterToDefaultLeavesEmptyWhenNoSedeAssigned() throws Exception {
        setTechnicianSede(null);
        RemitoHistoryController c = new RemitoHistoryController();
        invokeResetSedeFilterToDefault(c);
        assertTrue(selSedesField(c).isEmpty());
    }

    @Test
    void initSedeMenuOptionsComeFromEquipmentServiceCatalog() throws Exception {
        var mockEquipment = new MockEquipmentService();
        mockEquipment.addSede("Campus Norte");
        mockEquipment.addSede("Campus Sur");
        ServiceLocator.getInstance().setEquipmentService(mockEquipment);

        RemitoHistoryController c = new RemitoHistoryController();
        Field mnuSedeField = RemitoHistoryController.class.getDeclaredField("mnuSede");
        mnuSedeField.setAccessible(true);
        mnuSedeField.set(c, new javafx.scene.control.MenuButton());

        Method m = RemitoHistoryController.class.getDeclaredMethod("initSedeMenu");
        m.setAccessible(true);
        m.invoke(c);

        javafx.scene.control.MenuButton mnuSede = (javafx.scene.control.MenuButton) mnuSedeField.get(c);
        List<String> itemLabels = mnuSede.getItems().stream()
            .filter(it -> it instanceof javafx.scene.control.CustomMenuItem)
            .map(it -> ((javafx.scene.control.CustomMenuItem) it).getContent())
            .filter(n -> n instanceof javafx.scene.control.CheckBox)
            .map(n -> ((javafx.scene.control.CheckBox) n).getText())
            .toList();

        assertTrue(itemLabels.contains("Campus Norte"));
        assertTrue(itemLabels.contains("Campus Sur"));
    }

    @Test
    void approvalStatusToRawMapsLabelsBackToStoredValues() throws Exception {
        Method m = RemitoHistoryController.class.getDeclaredMethod("approvalStatusToRaw", String.class);
        m.setAccessible(true);
        assertEquals("PENDING", m.invoke(controller, "Pendiente"));
        assertEquals("APPROVED", m.invoke(controller, "Aprobada"));
        assertEquals("REJECTED", m.invoke(controller, "Rechazada"));
    }

    // Unified with History/PrestamoHistoryController: APROBACIÓN now defaults to Pendiente+Aprobada
    // selected (not "Todas"), so a RECHAZADO Remito is hidden from the default view here too.
    @Test
    void resetApprovalStatusFilterToDefaultSelectsPendingAndAprobada() throws Exception {
        RemitoHistoryController c = new RemitoHistoryController();
        Method m = RemitoHistoryController.class.getDeclaredMethod("resetApprovalStatusFilterToDefault");
        m.setAccessible(true);
        m.invoke(c);

        Field f = RemitoHistoryController.class.getDeclaredField("selApprovalStatuses");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> selApprovalStatuses = (Set<String>) f.get(c);

        assertEquals(Set.of("Pendiente", "Aprobada"), selApprovalStatuses);
    }

    // ── updatePendingApprovalLabel (filter-row pill — Envío has no other pending dimension) ────

    private javafx.scene.control.Label invokeUpdatePendingApprovalLabel(List<NoteReport> reports) throws Exception {
        RemitoHistoryController c = new RemitoHistoryController();
        javafx.scene.control.Label lbl = new javafx.scene.control.Label();
        Field f = RemitoHistoryController.class.getDeclaredField("lblPendingApproval");
        f.setAccessible(true);
        f.set(c, lbl);

        Method m = RemitoHistoryController.class.getDeclaredMethod("updatePendingApprovalLabel", List.class);
        m.setAccessible(true);
        m.invoke(c, reports);
        return lbl;
    }

    @Test
    void updatePendingApprovalLabelHiddenWhenNothingIsPending() throws Exception {
        javafx.scene.control.Label lbl =
            invokeUpdatePendingApprovalLabel(List.of(reportWithStatus("APPROVED"), reportWithStatus("REJECTED")));
        assertFalse(lbl.isVisible());
        assertFalse(lbl.isManaged());
    }

    @Test
    void updatePendingApprovalLabelShowsCountOfPendingNotes() throws Exception {
        javafx.scene.control.Label lbl = invokeUpdatePendingApprovalLabel(
            List.of(reportWithStatus("PENDING"), reportWithStatus("PENDING"), reportWithStatus("APPROVED")));
        assertTrue(lbl.isVisible());
        assertTrue(lbl.isManaged());
        assertTrue(lbl.getText().contains("2"), "expected count of 2 in: " + lbl.getText());
    }

}
