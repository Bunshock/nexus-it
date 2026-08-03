package com.bunshock.note_app_for_it_frontend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bunshock.note_app_for_it_frontend.models.NoteReport;
import com.bunshock.note_app_for_it_frontend.models.Permission;
import com.bunshock.note_app_for_it_frontend.services.CachingEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.CachingHistoryService;
import com.bunshock.note_app_for_it_frontend.services.CachingUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.IUserRoleService;
import com.bunshock.note_app_for_it_frontend.services.MockEquipmentService;
import com.bunshock.note_app_for_it_frontend.services.MockUserRoleService;

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

    // ── Stock ────────────────────────────────────────────────────────

    private static final int SEDE_ID = 1;

    @Test
    void stockReadsReturnLocalWhenPrimaryThrows() {
        var notebook = local.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook")).findFirst().orElseThrow();
        var brand = local.getBrandsForType(notebook.getId()).get(0);
        var model = local.getModelsForBrandAndType(brand.getId(), notebook.getId()).get(0);
        local.setModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID, 9);

        CachingEquipmentService failingPrimary = new CachingEquipmentService(
            new FailingEquipmentService(), local);
        assertEquals(9, failingPrimary.getModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID));
    }

    @Test
    void stockWritesGoToBothPrimaryAndLocal() {
        var notebook = primary.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook")).findFirst().orElseThrow();
        var brand = primary.getBrandsForType(notebook.getId()).get(0);
        var model = primary.getModelsForBrandAndType(brand.getId(), notebook.getId()).get(0);

        caching.setModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID, 6);

        assertEquals(6, primary.getModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID));
        assertEquals(6, local.getModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID));
    }

    @Test
    void stockLocalWriteFailureDoesNotPropagate() {
        var notebook = primary.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook")).findFirst().orElseThrow();
        var brand = primary.getBrandsForType(notebook.getId()).get(0);
        var model = primary.getModelsForBrandAndType(brand.getId(), notebook.getId()).get(0);

        CachingEquipmentService failingLocal = new CachingEquipmentService(
            primary, new FailingEquipmentService());
        assertDoesNotThrow(() ->
            failingLocal.setModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID, 4));
        assertEquals(4, primary.getModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID));
    }

    // ── User role / permissions ─────────────────────────────────────

    @Test
    void userRoleReadsReturnPrimaryData() {
        MockUserRoleService primaryU = new MockUserRoleService();
        MockUserRoleService localU   = new MockUserRoleService();
        primaryU.setRole("jperez", IUserRoleService.ROLE_ADMIN);
        CachingUserRoleService cachingU = new CachingUserRoleService(primaryU, localU);

        assertEquals(IUserRoleService.ROLE_ADMIN, cachingU.getRole("jperez"));
    }

    @Test
    void userRoleReadsFallBackToLocalWhenPrimaryThrows() {
        MockUserRoleService localU = new MockUserRoleService();
        localU.setRole("jperez", IUserRoleService.ROLE_SUPERADMIN);
        CachingUserRoleService cachingU = new CachingUserRoleService(new FailingUserRoleService(), localU);

        assertEquals(IUserRoleService.ROLE_SUPERADMIN, cachingU.getRole("jperez"));
    }

    @Test
    void getSedeIdReadsReturnPrimaryData() {
        MockUserRoleService primaryU = new MockUserRoleService();
        MockUserRoleService localU   = new MockUserRoleService();
        primaryU.setSedeId("jperez", 5);
        CachingUserRoleService cachingU = new CachingUserRoleService(primaryU, localU);

        assertEquals(5, cachingU.getSedeId("jperez"));
    }

    @Test
    void getSedeIdFallsBackToLocalWhenPrimaryThrows() {
        MockUserRoleService localU = new MockUserRoleService();
        localU.setSedeId("jperez", 3);
        CachingUserRoleService cachingU = new CachingUserRoleService(new FailingUserRoleService(), localU);

        assertEquals(3, cachingU.getSedeId("jperez"));
    }

    @Test
    void getPermissionsForRoleReadsReturnPrimaryData() {
        MockUserRoleService primaryU = new MockUserRoleService();
        MockUserRoleService localU   = new MockUserRoleService();
        primaryU.setPermissionsForRole(IUserRoleService.ROLE_ADMIN, java.util.Set.of(Permission.MANAGE_TYPES));
        CachingUserRoleService cachingU = new CachingUserRoleService(primaryU, localU);

        assertEquals(java.util.Set.of(Permission.MANAGE_TYPES), cachingU.getPermissionsForRole(IUserRoleService.ROLE_ADMIN));
    }

    @Test
    void getPermissionsForRoleFallsBackToLocalWhenPrimaryThrows() {
        MockUserRoleService localU = new MockUserRoleService();
        localU.setPermissionsForRole(IUserRoleService.ROLE_ADMIN, java.util.Set.of(Permission.MANAGE_SEDES));
        CachingUserRoleService cachingU = new CachingUserRoleService(new FailingUserRoleService(), localU);

        assertEquals(java.util.Set.of(Permission.MANAGE_SEDES), cachingU.getPermissionsForRole(IUserRoleService.ROLE_ADMIN));
    }

    // ── Minimal failing stubs ────────────────────────────────────────

    private static class FailingUserRoleService implements IUserRoleService {
        @Override public String getRole(String username) { throw new RuntimeException("primary down"); }
        @Override public boolean isRegistered(String username) { throw new RuntimeException("primary down"); }
        @Override public Integer getSedeId(String username) { throw new RuntimeException("primary down"); }
        @Override public java.util.Set<Permission> getPermissionsForRole(String role) { throw new RuntimeException("primary down"); }
    }

    private static class FailingEquipmentService extends MockEquipmentService {
        @Override public java.util.List<com.bunshock.note_app_for_it_frontend.models.EquipmentType> getAllTypes() { throw new RuntimeException("primary down"); }
        @Override public void addType(String n, boolean a) { throw new RuntimeException("primary down"); }
        @Override public int getModelStock(int modelId, int brandId, int typeId, int sedeId) { throw new RuntimeException("primary down"); }
        @Override public void setModelStock(int modelId, int brandId, int typeId, int sedeId, int stock) { throw new RuntimeException("primary down"); }
    }

    private static class MockHistoryService implements com.bunshock.note_app_for_it_frontend.services.IHistoryService {
        private final List<NoteReport> store = new java.util.ArrayList<>();
        private int nextId = 1;
        @Override public int save(NoteReport r) { r.setId(nextId++); store.add(r); return r.getId(); }
        @Override public List<NoteReport> getAll() { return store; }
        @Override public NoteReport getById(int id) { return store.stream().filter(r -> r.getId() == id).findFirst().orElse(null); }
    }

    private static class FailingHistoryService implements com.bunshock.note_app_for_it_frontend.services.IHistoryService {
        @Override public int save(NoteReport r) { throw new RuntimeException("primary down"); }
        @Override public List<NoteReport> getAll() { throw new RuntimeException("primary down"); }
        @Override public NoteReport getById(int id) { throw new RuntimeException("primary down"); }
    }
}
