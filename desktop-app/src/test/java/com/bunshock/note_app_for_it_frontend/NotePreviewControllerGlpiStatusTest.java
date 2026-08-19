package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import com.bunshock.note_app_for_it_frontend.controllers.NotePreviewController;
import com.bunshock.note_app_for_it_frontend.models.catalog.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.catalog.CountableItem;
import com.bunshock.note_app_for_it_frontend.models.history.GlpiStatus;
import com.bunshock.note_app_for_it_frontend.models.history.NoteReport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// buildReportWithItems() only touches the noteReport/profileType/assets/countables fields —
// none of NotePreviewController's @FXML nodes — so this exercises the real private method via
// reflection with no FXML/Scene/JavaFX-toolkit setup needed at all, same precedent as
// UserNoteFallaPersistenceTest's reflection-seeded testing of private controller state.
class NotePreviewControllerGlpiStatusTest {

    private NoteReport buildReport(String profileType) throws Exception {
        NotePreviewController ctrl = new NotePreviewController();

        setField(ctrl, "profileType", profileType);
        setField(ctrl, "noteReport", new NoteReport());
        setField(ctrl, "assets", List.of(
            new AssetItem("NOTEBOOK", "DELL", "LATITUDE", "Sin detalles", "SN123", "AF-001", 1, 1, 1)));
        setField(ctrl, "countables", List.<CountableItem>of());

        Method buildReportWithItems = NotePreviewController.class.getDeclaredMethod("buildReportWithItems");
        buildReportWithItems.setAccessible(true);
        return (NoteReport) buildReportWithItems.invoke(ctrl);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = NotePreviewController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void prestamoAssetsAreExcludedFromGlpiSync() throws Exception {
        NoteReport report = buildReport("PRÉSTAMO");

        assertEquals(1, report.getItems().size());
        assertEquals(GlpiStatus.N_A, report.getItems().get(0).getGlpiStatus(),
            "Préstamo assets must not be marked PENDING for GLPI sync — nothing un-syncs an "
            + "item when a loan is returned, so syncing a temporary loan would leave GLPI "
            + "permanently believing the asset is still assigned to the borrower");
    }

    @Test
    void entregaAssetsStillGetPendingGlpiSync() throws Exception {
        NoteReport report = buildReport("ENTREGA");

        assertEquals(1, report.getItems().size());
        assertEquals(GlpiStatus.PENDING, report.getItems().get(0).getGlpiStatus(),
            "Non-Préstamo note types must keep the existing unconditional PENDING behavior");
    }
}
