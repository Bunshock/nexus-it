package com.bunshock.note_app_for_it_frontend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.services.CachingEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.CachingHistoryService;
import com.bunshock.note_app_for_it_frontend.services.MockEquipmentService;

class CachingServiceTest {

    private MockEquipmentService primary;
    private MockEquipmentService local;
    private CachingEquipmentService caching;

    @BeforeEach
    void setUp() {
        primary = new MockEquipmentService();
        local   = new MockEquipmentService();
        caching = new CachingEquipmentService(primary, local);
    }

    @Test
    void readsReturnPrimaryData() {
        primary.addType("REMOTE_TYPE", true);
        var types = caching.getAllTypes();
        assertTrue(types.stream().anyMatch(t -> t.getName().equals("REMOTE_TYPE")));
    }

    @Test
    void writesGoToBothPrimaryAndLocal() {
        caching.addType("SHARED_TYPE", true);
        assertTrue(primary.getAllTypes().stream().anyMatch(t -> t.getName().equals("SHARED_TYPE")));
        assertTrue(local.getAllTypes().stream().anyMatch(t -> t.getName().equals("SHARED_TYPE")));
    }

    @Test
    void readsReturnLocalWhenPrimaryThrows() {
        local.addType("LOCAL_ONLY_TYPE", true);
        CachingEquipmentService failingPrimary = new CachingEquipmentService(
            new FailingEquipmentService(), local);
        var types = failingPrimary.getAllTypes();
        assertTrue(types.stream().anyMatch(t -> t.getName().equals("LOCAL_ONLY_TYPE")));
    }

    @Test
    void localWriteFailureDoesNotPropagate() {
        CachingEquipmentService failingLocal = new CachingEquipmentService(
            primary, new FailingEquipmentService());
        assertDoesNotThrow(() -> failingLocal.addType("TYPE_A", true));
        assertTrue(primary.getAllTypes().stream().anyMatch(t -> t.getName().equals("TYPE_A")));
    }

    @Test
    void cachingHistoryServiceSavesToBoth() {
        MockHistoryService primaryH = new MockHistoryService();
        MockHistoryService localH   = new MockHistoryService();
        CachingHistoryService cachingH = new CachingHistoryService(primaryH, localH);

        NoteReport r = new NoteReport();
        r.setProfileType("TEST");
        r.setCreatedAt(java.time.LocalDateTime.now());
        cachingH.save(r);

        assertEquals(1, primaryH.getAll().size());
        assertEquals(1, localH.getAll().size());
    }

    @Test
    void cachingHistoryReadsFromLocalWhenPrimaryFails() {
        MockHistoryService localH = new MockHistoryService();
        NoteReport r = new NoteReport();
        r.setProfileType("LOCAL_REPORT");
        r.setCreatedAt(java.time.LocalDateTime.now());
        localH.save(r);

        CachingHistoryService cachingH = new CachingHistoryService(new FailingHistoryService(), localH);
        List<NoteReport> all = cachingH.getAll();
        assertEquals(1, all.size());
        assertEquals("LOCAL_REPORT", all.get(0).getProfileType());
    }

    // ── Minimal failing stubs ────────────────────────────────────────

    private static class FailingEquipmentService extends MockEquipmentService {
        @Override public java.util.List<com.bunshock.note_app_for_it_frontend.models.EquipmentType> getAllTypes() { throw new RuntimeException("primary down"); }
        @Override public void addType(String n, boolean a) { throw new RuntimeException("primary down"); }
    }

    private static class MockHistoryService implements com.bunshock.note_app_for_it_frontend.services.IHistoryService {
        private final List<NoteReport> store = new java.util.ArrayList<>();
        private int nextId = 1;
        @Override public int save(NoteReport r) { r.setId(nextId++); store.add(r); return r.getId(); }
        @Override public List<NoteReport> getAll() { return store; }
        @Override public NoteReport getById(int id) { return store.stream().filter(r -> r.getId() == id).findFirst().orElse(null); }
        @Override public void markGlpiSynced(int id) {}
    }

    private static class FailingHistoryService implements com.bunshock.note_app_for_it_frontend.services.IHistoryService {
        @Override public int save(NoteReport r) { throw new RuntimeException("primary down"); }
        @Override public List<NoteReport> getAll() { throw new RuntimeException("primary down"); }
        @Override public NoteReport getById(int id) { throw new RuntimeException("primary down"); }
        @Override public void markGlpiSynced(int id) { throw new RuntimeException("primary down"); }
    }
}
