package com.bunshock.note_app_for_it_frontend;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.EquipmentBrand;
import com.bunshock.note_app_for_it_frontend.models.EquipmentModel;
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
    void upsertSnValidationUpdatesInMemory() {
        List<SnValidationRow> rows = service.getAllSnValidationRows();
        assertFalse(rows.isEmpty());
        int modelId = rows.get(0).getModelId();

        service.upsertSnValidation(modelId, "[A-Z]{3}\\d{5}", true);

        assertTrue(service.getSnValidation(modelId).isPresent());
        assertEquals("[A-Z]{3}\\d{5}", service.getSnValidation(modelId).get().getRegexPattern());
    }
}
