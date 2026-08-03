package com.bunshock.note_app_for_it_frontend;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
import com.bunshock.note_app_for_it_frontend.models.EquipmentProvider;
import com.bunshock.note_app_for_it_frontend.models.EquipmentType;
import com.bunshock.note_app_for_it_frontend.models.SnValidationRow;
import com.bunshock.note_app_for_it_frontend.services.MockEquipmentService;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MockEquipmentServiceTest {

    private final MockEquipmentService service = new MockEquipmentService();

    @Test
    void loadsTypes() {
        List<EquipmentType> types = service.getAllTypes();
        assertFalse(types.isEmpty());
    }

    @Test
    void notebookIsAsset() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElse(null);
        assertNotNull(notebook);
        assertTrue(notebook.isAsset());
    }

    @Test
    void notebookRequiresSerialByDefault() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElse(null);
        assertNotNull(notebook);
        assertTrue(notebook.isRequiresSerial());
    }

    @Test
    void setRequiresSerialIsReflectedInGetAllTypes() {
        EquipmentType monitor = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Monitor"))
            .findFirst().orElseThrow();
        assertFalse(monitor.isRequiresSerial());

        service.setRequiresSerial(monitor.getId(), true);

        EquipmentType updated = service.getAllTypes().stream()
            .filter(t -> t.getId() == monitor.getId())
            .findFirst().orElseThrow();
        assertTrue(updated.isRequiresSerial());
    }

    @Test
    void renameTypePreservesRequiresSerialFlag() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();

        service.renameType(notebook.getId(), "Laptop");

        EquipmentType renamed = service.getAllTypes().stream()
            .filter(t -> t.getId() == notebook.getId())
            .findFirst().orElseThrow();
        assertEquals("Laptop", renamed.getName());
        assertTrue(renamed.isRequiresSerial());
    }

    @Test
    void headsetIsNotAsset() {
        EquipmentType headset = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Headset"))
            .findFirst().orElse(null);
        assertNotNull(headset);
        assertFalse(headset.isAsset());
    }

    @Test
    void brandsForNotebookNotEmpty() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();
        List<EquipmentBrand> brands = service.getBrandsForType(notebook.getId());
        assertFalse(brands.isEmpty());
    }

    @Test
    void cascadeTypeToModels() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();
        List<EquipmentBrand> brands = service.getBrandsForType(notebook.getId());
        EquipmentBrand dell = brands.stream()
            .filter(b -> b.getName().equalsIgnoreCase("Dell"))
            .findFirst().orElseThrow();
        List<EquipmentModel> models = service.getModelsForBrandAndType(dell.getId(), notebook.getId());
        assertFalse(models.isEmpty());
    }

    @Test
    void addAndRetrieveNewType() {
        service.addType("TestType", true);
        boolean found = service.getAllTypes().stream()
            .anyMatch(t -> t.getName().equals("TestType"));
        assertTrue(found);
    }

    @Test
    void getAllSnValidationRowsContainsAllModels() {
        List<SnValidationRow> rows = service.getAllSnValidationRows();
        assertFalse(rows.isEmpty());
        rows.forEach(r -> {
            assertNotNull(r.getTypeName());
            assertNotNull(r.getBrandName());
            assertNotNull(r.getModelName());
        });
    }

    @Test
    void addBrandForTypeCreatesLinkAndBrand() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();
        int before = service.getBrandsForType(notebook.getId()).size();

        service.addBrandForType("BRANDTEST", notebook.getId());

        List<EquipmentBrand> after = service.getBrandsForType(notebook.getId());
        assertEquals(before + 1, after.size());
        assertTrue(after.stream().anyMatch(b -> b.getName().equals("BRANDTEST")));
    }

    @Test
    void addBrandForTypeIsIdempotentForExistingBrand() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();
        EquipmentBrand existing = service.getBrandsForType(notebook.getId()).get(0);

        service.addBrandForType(existing.getName(), notebook.getId());

        long count = service.getBrandsForType(notebook.getId()).stream()
            .filter(b -> b.getName().equalsIgnoreCase(existing.getName()))
            .count();
        assertEquals(1, count);
    }

    @Test
    void renameTypeIsReflectedInGetAllTypes() {
        service.addType("OldTypeName", true);
        EquipmentType created = service.getAllTypes().stream()
            .filter(t -> t.getName().equals("OldTypeName"))
            .findFirst().orElseThrow();

        service.renameType(created.getId(), "NewTypeName");

        assertTrue(service.getAllTypes().stream().anyMatch(t -> t.getName().equals("NewTypeName")));
        assertFalse(service.getAllTypes().stream().anyMatch(t -> t.getName().equals("OldTypeName")));
    }

    @Test
    void renameBrandIsReflectedAfterRename() {
        service.addBrand("OldBrand");
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();
        service.addBrandForType("OldBrand", notebook.getId());
        EquipmentBrand brand = service.getBrandsForType(notebook.getId()).stream()
            .filter(b -> b.getName().equals("OldBrand"))
            .findFirst().orElseThrow();

        service.renameBrand(brand.getId(), "NewBrand");

        assertTrue(service.getBrandsForType(notebook.getId()).stream()
            .anyMatch(b -> b.getName().equals("NewBrand")));
    }

    @Test
    void renameModelIsReflectedAfterRename() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();
        EquipmentBrand brand = service.getBrandsForType(notebook.getId()).get(0);
        service.addModel("OldModel", brand.getId(), notebook.getId());
        EquipmentModel model = service.getModelsForBrandAndType(brand.getId(), notebook.getId())
            .stream().filter(m -> m.getName().equals("OldModel"))
            .findFirst().orElseThrow();

        service.renameModel(model.getId(), "NewModel");

        assertTrue(service.getModelsForBrandAndType(brand.getId(), notebook.getId())
            .stream().anyMatch(m -> m.getName().equals("NewModel")));
    }

    @Test
    void upsertSnValidationUpdatesInMemory() {
        List<SnValidationRow> rows = service.getAllSnValidationRows();
        assertFalse(rows.isEmpty());
        int modelId = rows.get(0).getModelId();

        service.upsertSnValidation(modelId, "[A-Z]{3}\\d{5}", true);

        assertTrue(service.getSnValidation(modelId).isPresent());
        assertEquals("[A-Z]{3}\\d{5}", service.getSnValidation(modelId).get().getRegexPattern());
    }

    // ── Providers ─────────────────────────────────────────────────────────────

    @Test
    void getAllProvidersEmptyByDefault() {
        assertTrue(service.getAllProviders().isEmpty());
    }

    @Test
    void addProviderIsReflectedInGetAllProviders() {
        service.addProvider("TechCorp S.A.");
        assertTrue(service.getAllProviders().stream().anyMatch(p -> p.getName().equals("TechCorp S.A.")));
    }

    @Test
    void renameProviderIsReflectedAfterRename() {
        service.addProvider("OldProviderName");
        EquipmentProvider created = service.getAllProviders().stream()
            .filter(p -> p.getName().equals("OldProviderName"))
            .findFirst().orElseThrow();

        service.renameProvider(created.getId(), "NewProviderName");

        assertTrue(service.getAllProviders().stream().anyMatch(p -> p.getName().equals("NewProviderName")));
        assertFalse(service.getAllProviders().stream().anyMatch(p -> p.getName().equals("OldProviderName")));
    }

    @Test
    void removeProviderDeletesIt() {
        service.addProvider("TempProvider");
        EquipmentProvider created = service.getAllProviders().stream()
            .filter(p -> p.getName().equals("TempProvider"))
            .findFirst().orElseThrow();

        service.removeProvider(created.getId());

        assertFalse(service.getAllProviders().stream().anyMatch(p -> p.getName().equals("TempProvider")));
    }

    // ── Stock ────────────────────────────────────────────────────────────────
    // MockEquipmentService is a plain in-memory list with no FK enforcement, so any int works as
    // a stand-in sedeId — unlike SqliteEquipmentServiceTest, there's no real SEDE row to insert.
    private static final int SEDE_ID = 1;

    @Test
    void modelStockDefaultsToZero() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();
        EquipmentBrand brand = service.getBrandsForType(notebook.getId()).get(0);
        EquipmentModel model = service.getModelsForBrandAndType(brand.getId(), notebook.getId()).get(0);

        assertEquals(0, service.getModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID));
    }

    @Test
    void setModelStockThenGetModelStockRoundTrips() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();
        EquipmentBrand brand = service.getBrandsForType(notebook.getId()).get(0);
        EquipmentModel model = service.getModelsForBrandAndType(brand.getId(), notebook.getId()).get(0);

        service.setModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID, 8);
        assertEquals(8, service.getModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID));

        service.setModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID, 3);
        assertEquals(3, service.getModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID));
    }

    @Test
    void setModelStockIsIndependentPerSede() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();
        EquipmentBrand brand = service.getBrandsForType(notebook.getId()).get(0);
        EquipmentModel model = service.getModelsForBrandAndType(brand.getId(), notebook.getId()).get(0);

        service.setModelStock(model.getId(), brand.getId(), notebook.getId(), 1, 10);
        service.setModelStock(model.getId(), brand.getId(), notebook.getId(), 2, 4);

        assertEquals(10, service.getModelStock(model.getId(), brand.getId(), notebook.getId(), 1));
        assertEquals(4, service.getModelStock(model.getId(), brand.getId(), notebook.getId(), 2));
    }

    @Test
    void getStockTotalsByTypeSumsAcrossBrandsAndModels() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();
        List<EquipmentBrand> brands = service.getBrandsForType(notebook.getId());
        int expectedTotal = 0;
        for (EquipmentBrand b : brands) {
            List<EquipmentModel> models = service.getModelsForBrandAndType(b.getId(), notebook.getId());
            if (models.isEmpty()) continue;
            service.setModelStock(models.get(0).getId(), b.getId(), notebook.getId(), SEDE_ID, 5);
            expectedTotal += 5;
        }

        assertEquals(expectedTotal, service.getStockTotalsByType(SEDE_ID).getOrDefault(notebook.getId(), 0));
    }

    @Test
    void getStockTotalsByTypeWithNullSedeCombinesEverySede() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();
        EquipmentBrand brand = service.getBrandsForType(notebook.getId()).get(0);
        EquipmentModel model = service.getModelsForBrandAndType(brand.getId(), notebook.getId()).get(0);

        service.setModelStock(model.getId(), brand.getId(), notebook.getId(), 1, 4);
        service.setModelStock(model.getId(), brand.getId(), notebook.getId(), 2, 6);

        assertEquals(10, service.getStockTotalsByType(null).getOrDefault(notebook.getId(), 0));
    }

    @Test
    void getStockTotalsByModelForBrandAndTypeReflectsSetStock() {
        EquipmentType notebook = service.getAllTypes().stream()
            .filter(t -> t.getName().equalsIgnoreCase("Notebook"))
            .findFirst().orElseThrow();
        EquipmentBrand brand = service.getBrandsForType(notebook.getId()).get(0);
        EquipmentModel model = service.getModelsForBrandAndType(brand.getId(), notebook.getId()).get(0);

        service.setModelStock(model.getId(), brand.getId(), notebook.getId(), SEDE_ID, 12);

        assertEquals(12, service.getStockTotalsByModelForBrandAndType(brand.getId(), notebook.getId(), SEDE_ID)
            .getOrDefault(model.getId(), 0));
    }
}
