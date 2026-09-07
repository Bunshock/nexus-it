package com.bunshock.note_app_for_it.notes;

import com.bunshock.note_app_for_it.audit.AuditRepository;
import com.bunshock.note_app_for_it.catalog.CatalogRepository;
import com.bunshock.note_app_for_it.common.security.EncryptionService;
import com.bunshock.note_app_for_it.common.web.ApiException;
import com.bunshock.note_app_for_it.config.ConfigRepository;
import com.bunshock.note_app_for_it.notes.dto.CreateNoteRequest;
import com.bunshock.note_app_for_it.notes.dto.NoteDetailResponse;
import com.bunshock.note_app_for_it.notes.dto.NoteItemRequest;
import com.bunshock.note_app_for_it.notes.dto.NoteItemResponse;
import com.bunshock.note_app_for_it.notes.dto.NoteSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real JDBC test against embedded H2 (notes-repository-test-schema.sql — H2-native stand-in, same
 * precedent as CatalogRepositoryTest). Covers the v1 scope: create, list/filter, get-by-id,
 * approve/reject with old-model stock movement, item sync (GLPI), whole-item and partial-countable
 * return/lost.
 */
// @DirtiesContext forces a fresh embedded H2 instance for this class — @JdbcTest slice
// contexts are cached/shared by Spring across test classes with identical configuration,
// and this class's own @Sql schema (via IF NOT EXISTS) would otherwise silently lose a
// table-shape race against whichever other *RepositoryTest class happened to run first
// in the same JVM and already created a same-named table with different columns.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@JdbcTest
@Sql("/notes-repository-test-schema.sql")
class NotesRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private NotesRepository notes;
    private CatalogRepository catalog;

    private int notebookTypeId;
    private int dellBrandId;
    private int laptopModelId;
    private int cableTypeId;
    private int cableBrandId;
    private int cableModelId;
    private int sedeId;

    @BeforeEach
    void setUp() {
        // A fresh all-zero test-only key — never used outside this test, not a real secret.
        AuditRepository auditRepository = new AuditRepository(jdbc);
        ConfigRepository configRepository = new ConfigRepository(jdbc,
                new EncryptionService(Base64.getEncoder().encodeToString(new byte[32])), auditRepository);
        catalog = new CatalogRepository(jdbc, configRepository, auditRepository);
        notes = new NotesRepository(jdbc, catalog, configRepository, auditRepository);

        notebookTypeId = insertType("NOTEBOOK", true);
        dellBrandId = insertBrand("DELL");
        int link = link(notebookTypeId, dellBrandId);
        laptopModelId = insertModel(link, "Latitude 5420");

        cableTypeId = insertType("CABLE", false);
        cableBrandId = insertBrand("GENERIC");
        int cableLink = link(cableTypeId, cableBrandId);
        cableModelId = insertModel(cableLink, "USB-C");

        sedeId = insertSede("Campus Central");
        catalog.setModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId, 5, "Ajuste de prueba", "tester");
        catalog.setModelStock(cableModelId, cableBrandId, cableTypeId, sedeId, 10, "Ajuste de prueba", "tester");
    }

    private CreateNoteRequest entregaRequest(String profileType) {
        NoteItemRequest asset = new NoteItemRequest("ASSET", notebookTypeId, dellBrandId, laptopModelId,
                "SN12345", "IT-SN12345", null, "obs", true, null);
        return new CreateNoteRequest(profileType, "Juan Perez", "12345678", "juan@example.com",
                null, null, null, null, null, null, null, null, "notas", null, null, null, null, List.of(asset));
    }

    @Test
    void createEntregaNoteStartsPendingAndPersistsRecipient() {
        int id = notes.createNote(entregaRequest("ENTREGA"), "tech1", "99999999", sedeId);

        NoteDetailResponse detail = notes.getById(id);
        assertEquals("PENDING", detail.approvalStatus());
        assertEquals("Juan Perez", detail.userName());
        assertEquals("tech1", detail.authorName());
        assertEquals(1, detail.items().size());
        NoteItemResponse item = detail.items().get(0);
        assertTrue(item.asset());
        assertEquals("SN12345", item.serialNumber());
        assertEquals("PENDING", item.glpiStatus(), "non-Préstamo asset items start GLPI-PENDING");
        assertEquals("N_A", item.returnStatus(), "ENTREGA items have no return-tracking dimension");
    }

    @Test
    void prestamoAssetItemHasNoGlpiTrackingButHasReturnTracking() {
        int id = notes.createNote(entregaRequest("PRÉSTAMO"), "tech1", null, sedeId);

        NoteItemResponse item = notes.getById(id).items().get(0);
        assertEquals("N_A", item.glpiStatus(), "Préstamo assets are excluded from GLPI sync");
        assertEquals("PENDING", item.returnStatus());
    }

    @Test
    void approvingEntregaDecrementsStockAtTheNotesSede() {
        int id = notes.createNote(entregaRequest("ENTREGA"), "tech1", null, sedeId);

        notes.updateApprovalStatus(id, "APPROVED", null, "boss1");

        assertEquals(4, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId));
        assertTrue(notes.getById(id).stockApplied());

        // setUp() already wrote 2 AUDIT_STOCK rows of its own (initial laptop/cable stock
        // seeding, both under username "tester") — filter to the row this test's own approval
        // actually wrote, rather than assuming AUDIT_STOCK has exactly one row.
        var row = jdbc.queryForMap("SELECT * FROM AUDIT_STOCK WHERE username = 'boss1'");
        assertEquals(5, ((Number) row.get("old_stock")).intValue());
        assertEquals(4, ((Number) row.get("new_stock")).intValue());
        assertEquals("boss1", row.get("username"));
        assertEquals("Aprobación de nota #" + id, row.get("reason"));
    }

    @Test
    void approvingEntregaTwiceOnlyMovesStockOnce_stockAppliedGuard() {
        int id = notes.createNote(entregaRequest("ENTREGA"), "tech1", null, sedeId);

        notes.updateApprovalStatus(id, "APPROVED", null, "tester");
        notes.updateApprovalStatus(id, "APPROVED", null, "tester");

        assertEquals(4, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId));
    }

    @Test
    void approvingEntregaWithInsufficientStockThrowsAndMovesNothing() {
        catalog.setModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId, 0, "Ajuste de prueba", "tester");
        int id = notes.createNote(entregaRequest("ENTREGA"), "tech1", null, sedeId);

        ApiException ex = assertThrows(ApiException.class, () -> notes.updateApprovalStatus(id, "APPROVED", null, "tester"));
        assertEquals("STOCK_WOULD_GO_NEGATIVE", ex.getCode());
        assertEquals(0, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId));
        assertFalse(notes.getById(id).stockApplied());
    }

    @Test
    void approvingDevolucionIncrementsStock() {
        int id = notes.createNote(entregaRequest("DEVOLUCIÓN"), "tech1", null, sedeId);

        notes.updateApprovalStatus(id, "APPROVED", null, "tester");

        assertEquals(6, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId));
    }

    @Test
    void rejectingANoteStoresAndClearsTheReason() {
        int id = notes.createNote(entregaRequest("ENTREGA"), "tech1", null, sedeId);

        notes.updateApprovalStatus(id, "REJECTED", "Tipo de nota incorrecto", "tester");
        assertEquals("Tipo de nota incorrecto", notes.getById(id).rejectionReason());

        // Approving afterward (a superadmin override scenario) clears the stale reason.
        notes.updateApprovalStatus(id, "APPROVED", null, "tester");
        assertNull(notes.getById(id).rejectionReason());
    }

    @Test
    void syncThenRejectSyncFlipTheGlpiStatusDirectly() {
        int id = notes.createNote(entregaRequest("ENTREGA"), "tech1", null, sedeId);
        int itemId = notes.getById(id).items().get(0).id();

        notes.updateItemGlpiStatus(itemId, "SYNCED", null, "tester");
        assertEquals("SYNCED", notes.getById(id).items().get(0).glpiStatus());

        notes.updateItemGlpiStatus(itemId, "REJECTED", "No corresponde", "tester");
        NoteItemResponse item = notes.getById(id).items().get(0);
        assertEquals("REJECTED", item.glpiStatus());
        assertEquals("No corresponde", item.glpiRejectionReason());

        // Both transitions get their own AUDIT_ITEM_STATUS row — the second's old_status must be
        // "SYNCED" (the status right before the reject-sync call), not "N_A"/"PENDING".
        List<Map<String, Object>> auditRows = jdbc.queryForList(
                "SELECT * FROM AUDIT_ITEM_STATUS ORDER BY id");
        assertEquals(2, auditRows.size());
        assertEquals("PENDING", auditRows.get(0).get("old_status"));
        assertEquals("SYNCED", auditRows.get(0).get("new_status"));
        assertEquals("SYNCED", auditRows.get(1).get("old_status"));
        assertEquals("REJECTED", auditRows.get(1).get("new_status"));
        assertEquals("No corresponde", auditRows.get(1).get("reason"));
    }

    @Test
    void wholeItemReturnCreditsStockBackOnlyOnce() {
        int id = notes.createNote(entregaRequest("PRÉSTAMO"), "tech1", null, sedeId);
        notes.updateApprovalStatus(id, "APPROVED", null, "tester"); // decrements stock (PRÉSTAMO is egress)
        int itemId = notes.getById(id).items().get(0).id();
        assertEquals(4, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId));

        notes.updateItemReturnStatus(itemId, "RETURNED", null, "tester");
        assertEquals(5, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId));

        // Re-calling with the same status must NOT credit stock a second time.
        notes.updateItemReturnStatus(itemId, "RETURNED", null, "tester");
        assertEquals(5, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId));
    }

    @Test
    void lostItemDoesNotCreditStockBack() {
        int id = notes.createNote(entregaRequest("PRÉSTAMO"), "tech1", null, sedeId);
        notes.updateApprovalStatus(id, "APPROVED", null, "tester");
        int itemId = notes.getById(id).items().get(0).id();

        notes.updateItemReturnStatus(itemId, "LOST", "Robado", "tester");

        assertEquals(4, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId));
        assertEquals("LOST", notes.getById(id).items().get(0).returnStatus());
    }

    @Test
    void partialCountableReturnCreditsOnlyTheAllocatedQuantity() {
        NoteItemRequest cables = new NoteItemRequest("COUNTABLE", cableTypeId, cableBrandId, cableModelId,
                null, null, 5, null, true, null);
        CreateNoteRequest req = new CreateNoteRequest("PRÉSTAMO", "Juan Perez", "12345678", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, List.of(cables));
        int id = notes.createNote(req, "tech1", null, sedeId);
        notes.updateApprovalStatus(id, "APPROVED", null, "tester"); // stock 10 -> 5
        int itemId = notes.getById(id).items().get(0).id();
        assertEquals(5, catalog.getModelStock(cableModelId, cableBrandId, cableTypeId, sedeId));

        notes.allocateCountableReturn(itemId, "RETURNED", 3, null, "tester");
        assertEquals(8, catalog.getModelStock(cableModelId, cableBrandId, cableTypeId, sedeId));

        NoteItemResponse item = notes.getById(id).items().get(0);
        assertEquals(3, item.returnedQuantity());
        assertEquals(0, item.lostQuantity());

        notes.allocateCountableReturn(itemId, "LOST", 1, "Extraviado", "tester");
        item = notes.getById(id).items().get(0);
        assertEquals(3, item.returnedQuantity());
        assertEquals(1, item.lostQuantity());
        assertEquals(8, catalog.getModelStock(cableModelId, cableBrandId, cableTypeId, sedeId), "Lost never credits stock");
    }

    @Test
    void allocatingMoreThanPendingQuantityThrows() {
        NoteItemRequest cables = new NoteItemRequest("COUNTABLE", cableTypeId, cableBrandId, cableModelId,
                null, null, 5, null, true, null);
        CreateNoteRequest req = new CreateNoteRequest("PRÉSTAMO", "Juan Perez", "12345678", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, List.of(cables));
        int id = notes.createNote(req, "tech1", null, sedeId);
        int itemId = notes.getById(id).items().get(0).id();

        ApiException ex = assertThrows(ApiException.class,
                () -> notes.allocateCountableReturn(itemId, "RETURNED", 6, null, "tester"));
        assertEquals("QUANTITY_EXCEEDS_PENDING", ex.getCode());
    }

    @Test
    void itemFlaggedNotModifiesStockIsExcludedFromApprovalStockMovement() {
        NoteItemRequest exempt = new NoteItemRequest("ASSET", notebookTypeId, dellBrandId, laptopModelId,
                "SN99999", "IT-SN99999", null, null, false, "Ya lo tenía físicamente");
        CreateNoteRequest req = new CreateNoteRequest("ENTREGA", "Juan Perez", "12345678", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, List.of(exempt));
        int id = notes.createNote(req, "tech1", null, sedeId);

        notes.updateApprovalStatus(id, "APPROVED", null, "tester");

        assertEquals(5, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId),
                "stock must be untouched for a modifiesStock=false item");
    }

    @Test
    void providerNoteWithoutFalseGlpiPendingPersistsProviderFields() {
        int providerId = insertProvider("ACME Corp");
        NoteItemRequest asset = new NoteItemRequest("ASSET", notebookTypeId, dellBrandId, laptopModelId,
                "SN1", "IT-SN1", null, null, true, null);
        CreateNoteRequest req = new CreateNoteRequest("ENTREGA - PROVEEDOR", null, null, null,
                "Garantía", null, null, null, providerId, "30-12345678-9", "Responsable X", "87654321",
                null, null, null, null, null, List.of(asset));
        int id = notes.createNote(req, "tech1", null, sedeId);

        NoteDetailResponse detail = notes.getById(id);
        assertEquals("ACME Corp", detail.providerName());
        assertEquals(providerId, detail.providerId());
        assertEquals("Responsable X", detail.responsibleName());
        assertEquals("PENDING", detail.items().get(0).returnStatus(), "Garantía is a returnable Motivo");
    }

    @Test
    void getFilteredByProfileTypeAndApprovalStatus() {
        int a = notes.createNote(entregaRequest("ENTREGA"), "tech1", null, sedeId);
        int b = notes.createNote(entregaRequest("DEVOLUCIÓN"), "tech1", null, sedeId);
        notes.updateApprovalStatus(a, "APPROVED", null, "tester");

        List<NoteSummaryResponse> onlyEntrega = notes.getFiltered(
                new NotesFilter(List.of("ENTREGA"), null, null, null, null, null, null, null, null, null, null, null));
        assertEquals(1, onlyEntrega.size());
        assertEquals(a, onlyEntrega.get(0).id());

        List<NoteSummaryResponse> onlyPending = notes.getFiltered(
                new NotesFilter(null, List.of("PENDING"), null, null, null, null, null, null, null, null, null, null));
        assertEquals(1, onlyPending.size());
        assertEquals(b, onlyPending.get(0).id());
    }

    @Test
    void getFilteredByItemType() {
        int notebookNote = notes.createNote(entregaRequest("ENTREGA"), "tech1", null, sedeId);
        NoteItemRequest cable = new NoteItemRequest("COUNTABLE", cableTypeId, cableBrandId, cableModelId,
                null, null, 3, null, true, null);
        int cableNote = notes.createNote(new CreateNoteRequest("ENTREGA", "Ana Diaz", "22222222", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, List.of(cable)), "tech1", null, sedeId);

        List<NoteSummaryResponse> onlyNotebooks = notes.getFiltered(new NotesFilter(
                null, null, null, null, null, null, null, List.of("NOTEBOOK"), null, null, null, null));

        assertEquals(List.of(notebookNote), onlyNotebooks.stream().map(NoteSummaryResponse::id).toList());
        assertTrue(notes.getFiltered(new NotesFilter(
                null, null, null, null, null, null, null, List.of("CABLE"), null, null, null, null))
                .stream().map(NoteSummaryResponse::id).toList().contains(cableNote));
    }

    @Test
    void getFilteredByItemTypeAndBrandRequiresOneItemToMatchBoth() {
        int hpBrandId = insertBrand("HP");
        int hpLink = link(notebookTypeId, hpBrandId);
        int hpModelId = insertModel(hpLink, "EliteBook 840");
        catalog.setModelStock(hpModelId, hpBrandId, notebookTypeId, sedeId, 5, "Ajuste de prueba", "tester");

        int dellNote = notes.createNote(entregaRequest("ENTREGA"), "tech1", null, sedeId); // NOTEBOOK/DELL
        NoteItemRequest hpAsset = new NoteItemRequest("ASSET", notebookTypeId, hpBrandId, hpModelId,
                "SNHP1", "IT-SNHP1", null, null, true, null);
        int hpNote = notes.createNote(new CreateNoteRequest("ENTREGA", "Ana Diaz", "22222222", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, List.of(hpAsset)), "tech1", null, sedeId);

        List<NoteSummaryResponse> notebookAndDell = notes.getFiltered(new NotesFilter(
                null, null, null, null, null, null, null, List.of("NOTEBOOK"), List.of("DELL"), null, null, null));

        assertEquals(List.of(dellNote), notebookAndDell.stream().map(NoteSummaryResponse::id).toList());
        assertFalse(notebookAndDell.stream().anyMatch(r -> r.id() == hpNote));
    }

    @Test
    void getFilteredBySyncStatus() {
        int entrega = notes.createNote(entregaRequest("ENTREGA"), "tech1", null, sedeId); // asset -> GLPI PENDING
        NoteItemRequest cable = new NoteItemRequest("COUNTABLE", cableTypeId, cableBrandId, cableModelId,
                null, null, 2, null, true, null);
        int cableOnly = notes.createNote(new CreateNoteRequest("ENTREGA", "Ana Diaz", "22222222", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, List.of(cable)), "tech1", null, sedeId); // GLPI N_A

        List<NoteSummaryResponse> pending = notes.getFiltered(new NotesFilter(
                null, null, null, null, null, null, null, null, null, null, List.of("PENDING"), null));
        assertEquals(List.of(entrega), pending.stream().map(NoteSummaryResponse::id).toList());

        List<NoteSummaryResponse> na = notes.getFiltered(new NotesFilter(
                null, null, null, null, null, null, null, null, null, null, List.of("N_A"), null));
        assertEquals(List.of(cableOnly), na.stream().map(NoteSummaryResponse::id).toList());

        // after syncing the asset, the ENTREGA note moves out of PENDING and into SYNCED
        int itemId = notes.getById(entrega).items().get(0).id();
        notes.updateItemGlpiStatus(itemId, "SYNCED", null, "tester");
        assertTrue(notes.getFiltered(new NotesFilter(
                null, null, null, null, null, null, null, null, null, null, List.of("PENDING"), null)).isEmpty());
        assertEquals(List.of(entrega), notes.getFiltered(new NotesFilter(
                null, null, null, null, null, null, null, null, null, null, List.of("SYNCED"), null))
                .stream().map(NoteSummaryResponse::id).toList());
    }

    @Test
    void getFilteredByReturnStatus() {
        int prestamo = notes.createNote(entregaRequest("PRÉSTAMO"), "tech1", null, sedeId); // RETURN PENDING
        int entrega = notes.createNote(entregaRequest("ENTREGA"), "tech1", null, sedeId);   // RETURN N_A

        List<NoteSummaryResponse> returnPending = notes.getFiltered(new NotesFilter(
                null, null, null, null, null, null, null, null, null, null, null, List.of("PENDING")));
        assertEquals(List.of(prestamo), returnPending.stream().map(NoteSummaryResponse::id).toList());

        List<NoteSummaryResponse> returnNa = notes.getFiltered(new NotesFilter(
                null, null, null, null, null, null, null, null, null, null, null, List.of("N_A")));
        assertEquals(List.of(entrega), returnNa.stream().map(NoteSummaryResponse::id).toList());

        notes.updateApprovalStatus(prestamo, "APPROVED", null, "tester");
        int itemId = notes.getById(prestamo).items().get(0).id();
        notes.updateItemReturnStatus(itemId, "RETURNED", null, "tester");
        assertEquals(List.of(prestamo), notes.getFiltered(new NotesFilter(
                null, null, null, null, null, null, null, null, null, null, null, List.of("RETURNED")))
                .stream().map(NoteSummaryResponse::id).toList());
    }

    // ── Remito de Envío ──────────────────────────────────────────────────────

    private CreateNoteRequest remitoToSede(int shippingInfoId) {
        NoteItemRequest asset = new NoteItemRequest("ASSET", notebookTypeId, dellBrandId, laptopModelId,
                "SNRMT1", "IT-SNRMT1", null, null, true, null);
        return new CreateNoteRequest("REMITO DE ENVÍO", null, null, null, null, null, null, null,
                null, null, null, null, null, shippingInfoId, null, null, null, List.of(asset));
    }

    private CreateNoteRequest remitoToCustom(String label, String address, String recipients) {
        NoteItemRequest asset = new NoteItemRequest("ASSET", notebookTypeId, dellBrandId, laptopModelId,
                "SNRMT2", "IT-SNRMT2", null, null, true, null);
        return new CreateNoteRequest("REMITO DE ENVÍO", null, null, null, null, null, null, null,
                null, null, null, null, null, null, label, address, recipients, List.of(asset));
    }

    private int insertShippingInfo(int forSedeId, String label) {
        jdbc.update("INSERT INTO SEDE_SHIPPING_INFO (sede_id, destination_label, address, recipients) VALUES (?, ?, ?, ?)",
                forSedeId, label, "Calle 123", "Encargado");
        return jdbc.queryForObject("SELECT id FROM SEDE_SHIPPING_INFO WHERE sede_id = ? AND deprecated = 0",
                Integer.class, forSedeId);
    }

    @Test
    void createRemitoToACatalogSedeLinksTheShippingInfoRow() {
        int destSede = insertSede("Campus Norte");
        int shippingInfoId = insertShippingInfo(destSede, "Depósito Norte");

        int id = notes.createNote(remitoToSede(shippingInfoId), "tech1", null, sedeId);

        NoteDetailResponse detail = notes.getById(id);
        assertEquals(destSede, detail.destinationSedeId());
        assertEquals("Depósito Norte", detail.destinationLabel());
        assertEquals("Calle 123", detail.destinationAddress());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM NOTE_REMITO_SEDE WHERE note_report_id = ?", Integer.class, id);
        assertEquals(1, rows);
    }

    @Test
    void createRemitoWithACustomDestinationStoresTheFreeText() {
        int id = notes.createNote(remitoToCustom("CAU Aeropuerto", "Terminal 2", "Jefe de CAU"), "tech1", null, sedeId);

        NoteDetailResponse detail = notes.getById(id);
        assertNull(detail.destinationSedeId());
        assertEquals("CAU Aeropuerto", detail.destinationLabel());
        assertEquals("Jefe de CAU", detail.destinationRecipients());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM NOTE_REMITO_OTHER WHERE note_report_id = ?", Integer.class, id);
        assertEquals(1, rows);
    }

    @Test
    void createRemitoWithoutAnyDestinationIsRejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> notes.createNote(remitoToCustom(null, null, null), "tech1", null, sedeId));
        assertEquals("REMITO_DESTINATION_REQUIRED", ex.getCode());
    }

    @Test
    void approvingARemitoToACatalogSedeMovesStockFromSourceToDestination() {
        int destSede = insertSede("Campus Norte");
        int shippingInfoId = insertShippingInfo(destSede, "Depósito Norte");
        int id = notes.createNote(remitoToSede(shippingInfoId), "tech1", null, sedeId);

        notes.updateApprovalStatus(id, "APPROVED", null, "tester");

        assertEquals(4, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId), "source Sede decremented");
        assertEquals(1, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, destSede), "destination Sede incremented");
        // idempotent — re-approving never double-moves
        notes.updateApprovalStatus(id, "APPROVED", null, "tester");
        assertEquals(4, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId));
        assertEquals(1, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, destSede));
    }

    @Test
    void approvingARemitoToACustomDestinationOnlyDecrementsTheSource() {
        int id = notes.createNote(remitoToCustom("CAU Aeropuerto", null, null), "tech1", null, sedeId);

        notes.updateApprovalStatus(id, "APPROVED", null, "tester");

        assertEquals(4, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId));
    }

    @Test
    void approvingARemitoWithInsufficientSourceStockThrowsAndMovesNothing() {
        int destSede = insertSede("Campus Norte");
        int shippingInfoId = insertShippingInfo(destSede, "Depósito Norte");
        catalog.setModelStock(laptopModelId, dellBrandId, notebookTypeId, sedeId, 0, "Vaciar", "tester");
        int id = notes.createNote(remitoToSede(shippingInfoId), "tech1", null, sedeId);

        ApiException ex = assertThrows(ApiException.class,
                () -> notes.updateApprovalStatus(id, "APPROVED", null, "tester"));
        assertEquals("STOCK_WOULD_GO_NEGATIVE", ex.getCode());
        assertEquals(0, catalog.getModelStock(laptopModelId, dellBrandId, notebookTypeId, destSede));
    }

    @Test
    void remitoDestinationLabelShowsAsTheRecipientInTheSummaryList() {
        int destSede = insertSede("Campus Norte");
        int shippingInfoId = insertShippingInfo(destSede, "Depósito Norte");
        int id = notes.createNote(remitoToSede(shippingInfoId), "tech1", null, sedeId);

        NoteSummaryResponse row = notes.getFiltered(new NotesFilter(
                List.of("REMITO DE ENVÍO"), null, null, null, null, null, null, null, null, null, null, null))
                .stream().filter(r -> r.id() == id).findFirst().orElseThrow();
        assertEquals("Depósito Norte", row.recipient());
        assertEquals("Depósito Norte", row.destinationLabel());
    }

    @Test
    void getSedeIdForNoteAndIsAssetItemHelpersWork() {
        int id = notes.createNote(entregaRequest("ENTREGA"), "tech1", null, sedeId);
        int itemId = notes.getById(id).items().get(0).id();

        assertEquals(sedeId, notes.getSedeIdForNote(id));
        assertTrue(notes.isAssetItem(itemId));
    }

    private int insertType(String name, boolean isAsset) {
        jdbc.update("INSERT INTO TYPE (name, is_asset) VALUES (?, ?)", name, isAsset ? 1 : 0);
        return jdbc.queryForObject("SELECT id FROM TYPE WHERE name = ?", Integer.class, name);
    }

    private int insertBrand(String name) {
        jdbc.update("INSERT INTO BRAND (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM BRAND WHERE name = ?", Integer.class, name);
    }

    private int insertSede(String name) {
        jdbc.update("INSERT INTO SEDE (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM SEDE WHERE name = ?", Integer.class, name);
    }

    private int insertProvider(String name) {
        jdbc.update("INSERT INTO PROVIDER (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM PROVIDER WHERE name = ?", Integer.class, name);
    }

    private int link(int typeId, int brandId) {
        jdbc.update("INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (?, ?)", typeId, brandId);
        return jdbc.queryForObject(
                "SELECT id FROM BRAND_TYPE_LINK WHERE type_id = ? AND brand_id = ?", Integer.class, typeId, brandId);
    }

    private int insertModel(int brandTypeId, String name) {
        jdbc.update("INSERT INTO MODEL (brand_type_id, name) VALUES (?, ?)", brandTypeId, name);
        return jdbc.queryForObject(
                "SELECT id FROM MODEL WHERE brand_type_id = ? AND name = ?", Integer.class, brandTypeId, name);
    }
}
