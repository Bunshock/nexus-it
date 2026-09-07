package com.bunshock.note_app_for_it.audit;

import com.bunshock.note_app_for_it.audit.dto.AuditAdminActionEntry;
import com.bunshock.note_app_for_it.audit.dto.AuditItemStatusEntry;
import com.bunshock.note_app_for_it.audit.dto.AuditLoginEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real JDBC test against embedded H2 (audit-repository-test-schema.sql — H2-native stand-in,
 * same precedent as the other *RepositoryTest classes).
 */
// @DirtiesContext forces a fresh embedded H2 instance for this class — @JdbcTest slice
// contexts are cached/shared by Spring across test classes with identical configuration,
// and this class's own @Sql schema (via IF NOT EXISTS) would otherwise silently lose a
// table-shape race against whichever other *RepositoryTest class happened to run first
// in the same JVM and already created a same-named table with different columns.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@JdbcTest
@Sql("/audit-repository-test-schema.sql")
class AuditRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private AuditRepository repository;

    private int typeId;
    private int brandId;
    private int modelId;
    private int sedeId;
    private int noteId;
    private int itemId;

    @BeforeEach
    void setUp() {
        repository = new AuditRepository(jdbc);

        jdbc.update("INSERT INTO TYPE (name) VALUES ('NOTEBOOK')");
        typeId = jdbc.queryForObject("SELECT id FROM TYPE WHERE name = 'NOTEBOOK'", Integer.class);
        jdbc.update("INSERT INTO BRAND (name) VALUES ('DELL')");
        brandId = jdbc.queryForObject("SELECT id FROM BRAND WHERE name = 'DELL'", Integer.class);
        jdbc.update("INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (?, ?)", typeId, brandId);
        jdbc.update("INSERT INTO MODEL (brand_type_id, name) VALUES ((SELECT id FROM BRAND_TYPE_LINK WHERE type_id = ? AND brand_id = ?), 'Latitude')", typeId, brandId);
        modelId = jdbc.queryForObject("SELECT id FROM MODEL WHERE name = 'Latitude'", Integer.class);
        jdbc.update("INSERT INTO SEDE (name) VALUES ('Campus Central')");
        sedeId = jdbc.queryForObject("SELECT id FROM SEDE WHERE name = 'Campus Central'", Integer.class);
        jdbc.update("INSERT INTO NOTE_REPORT (profile_type) VALUES ('ENTREGA')");
        noteId = jdbc.queryForObject("SELECT id FROM NOTE_REPORT WHERE profile_type = 'ENTREGA'", Integer.class);
        jdbc.update("INSERT INTO NOTE_ITEM (note_id) VALUES (?)", noteId);
        itemId = jdbc.queryForObject("SELECT id FROM NOTE_ITEM WHERE note_id = ?", Integer.class, noteId);
    }

    @Test
    void recordLoginThenReadItBack() {
        repository.recordLogin("jperez", true, null);
        repository.recordLogin("jperez", false, "NOT_REGISTERED");

        List<AuditLoginEntry> all = repository.getLoginAudit(null, null, null);
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(e -> e.success() && e.failureReason() == null));
        assertTrue(all.stream().anyMatch(e -> !e.success() && "NOT_REGISTERED".equals(e.failureReason())));
    }

    @Test
    void getLoginAuditFiltersByUsername() {
        repository.recordLogin("jperez", true, null);
        repository.recordLogin("mgarcia", true, null);

        List<AuditLoginEntry> filtered = repository.getLoginAudit("mgarcia", null, null);
        assertEquals(1, filtered.size());
        assertEquals("mgarcia", filtered.get(0).username());
    }

    @Test
    void recordItemStatusChangeThenReadItBack() {
        repository.recordItemStatusChange(itemId, "GLPI", "PENDING", "SYNCED", null, 1, "jperez");

        List<AuditItemStatusEntry> entries = repository.getItemStatusAudit(null, itemId, null, null);
        assertEquals(1, entries.size());
        AuditItemStatusEntry entry = entries.get(0);
        assertEquals("GLPI", entry.statusKind());
        assertEquals("PENDING", entry.oldStatus());
        assertEquals("SYNCED", entry.newStatus());
        assertEquals("jperez", entry.username());
    }

    @Test
    void getItemStatusAuditFiltersByNoteIdViaJoin() {
        jdbc.update("INSERT INTO NOTE_REPORT (profile_type) VALUES ('DEVOLUCIÓN')");
        int otherNoteId = jdbc.queryForObject("SELECT id FROM NOTE_REPORT WHERE profile_type = 'DEVOLUCIÓN'", Integer.class);
        jdbc.update("INSERT INTO NOTE_ITEM (note_id) VALUES (?)", otherNoteId);
        int otherItemId = jdbc.queryForObject("SELECT id FROM NOTE_ITEM WHERE note_id = ?", Integer.class, otherNoteId);

        repository.recordItemStatusChange(itemId, "GLPI", "PENDING", "SYNCED", null, 1, "jperez");
        repository.recordItemStatusChange(otherItemId, "RETURN", "PENDING", "RETURNED", null, 1, "jperez");

        List<AuditItemStatusEntry> forNote = repository.getItemStatusAudit(noteId, null, null, null);
        assertEquals(1, forNote.size());
        assertEquals(itemId, forNote.get(0).itemId());
    }

    @Test
    void recordStockChangeThenItIsPersisted() {
        repository.recordStockChange(modelId, brandId, typeId, sedeId, "jperez", 5, 4, "Aprobación de nota #1");

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_STOCK", Integer.class);
        assertEquals(1, count);
        var row = jdbc.queryForMap("SELECT * FROM AUDIT_STOCK");
        assertEquals(5, ((Number) row.get("old_stock")).intValue());
        assertEquals(4, ((Number) row.get("new_stock")).intValue());
    }

    @Test
    void recordStockChangeSilentlyNoOpsWhenNoBrandTypeLinkExists() {
        // A brand/type combination with no BRAND_TYPE_LINK row at all — must not throw, per
        // AuditRepository's own "audit writes must never crash the real mutation" contract.
        jdbc.update("INSERT INTO BRAND (name) VALUES ('NEVERLINKED')");
        int unlinkdBrandId = jdbc.queryForObject("SELECT id FROM BRAND WHERE name = 'NEVERLINKED'", Integer.class);

        assertDoesNotThrow(() ->
                repository.recordStockChange(modelId, unlinkdBrandId, typeId, sedeId, "jperez", 0, 1, "test"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_STOCK", Integer.class);
        assertEquals(0, count);
    }

    @Test
    void recordAdminActionThenReadItBackFilteredByActorAndTargetType() {
        repository.recordAdminAction("mgarcia", "RENAME_TYPE", "TYPE", String.valueOf(typeId), "NOTEBOOK", "LAPTOP", null);
        repository.recordAdminAction("jperez", "ADD_BRAND", "BRAND", String.valueOf(brandId), null, "DELL", null);

        List<AuditAdminActionEntry> all = repository.getAdminActionAudit(null, null, null, null);
        assertEquals(2, all.size());

        List<AuditAdminActionEntry> byActor = repository.getAdminActionAudit("mgarcia", null, null, null);
        assertEquals(1, byActor.size());
        assertEquals("RENAME_TYPE", byActor.get(0).action());

        List<AuditAdminActionEntry> byTargetType = repository.getAdminActionAudit(null, "BRAND", null, null);
        assertEquals(1, byTargetType.size());
        assertEquals("ADD_BRAND", byTargetType.get(0).action());
    }

    @Test
    void pagingReturnsOnlyTheRequestedPage() {
        for (int i = 0; i < 5; i++) {
            repository.recordLogin("user" + i, true, null);
        }

        List<AuditLoginEntry> firstPage = repository.getLoginAudit(null, 0, 2);
        List<AuditLoginEntry> secondPage = repository.getLoginAudit(null, 1, 2);

        assertEquals(2, firstPage.size());
        assertEquals(2, secondPage.size());
        assertNotEquals(firstPage.get(0).id(), secondPage.get(0).id());
    }

    @Test
    void oversizedPageSizeIsClampedNotRejected() {
        repository.recordLogin("jperez", true, null);
        assertDoesNotThrow(() -> repository.getLoginAudit(null, 0, 10_000));
    }
}
