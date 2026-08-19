package com.bunshock.note_app_for_it_frontend;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;

import com.bunshock.note_app_for_it_frontend.controllers.DatabaseSectionController;
import com.bunshock.note_app_for_it_frontend.models.catalog.EquipmentType;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// sortStockFirstThenAlphabetical() is a pure sorting helper (its only external dependency,
// genericLabel(), falls back to the hardcoded default when ConfigService isn't loaded — exactly
// this test's situation), so it's exercised via reflection on a bare instance without needing
// ServiceLocator — same "impractical to exercise the rest of this controller in isolation" gap
// already accepted elsewhere for DatabaseSectionController/MainController/HistoryController.
class DatabaseSectionControllerTest {

    private final DatabaseSectionController controller = new DatabaseSectionController();

    @SuppressWarnings("unchecked")
    private List<EquipmentType> sort(List<EquipmentType> items) throws Exception {
        Method m = DatabaseSectionController.class.getDeclaredMethod(
            "sortStockFirstThenAlphabetical", List.class, Function.class, Function.class);
        m.setAccessible(true);
        Function<EquipmentType, Integer> stockLookup = EquipmentType::getId; // id doubles as stock below
        Function<EquipmentType, String> nameLookup = EquipmentType::getName;
        return (List<EquipmentType>) m.invoke(controller, items, stockLookup, nameLookup);
    }

    private EquipmentType type(int stock, String name) {
        // id doubles as the stock value here (sort() above reads getId() as the stock lookup) —
        // avoids a separate test-only model just to carry two int fields.
        return new EquipmentType(stock, name, true, false);
    }

    @Test
    void groupsStockedItemsBeforeZeroStockItems() throws Exception {
        List<EquipmentType> sorted = sort(List.of(
            type(0, "Alpha"),
            type(5, "Bravo"),
            type(0, "Charlie"),
            type(3, "Delta")));

        assertEquals(List.of("Bravo", "Delta", "Alpha", "Charlie"),
            sorted.stream().map(EquipmentType::getName).toList());
    }

    @Test
    void sortsWithinEachGroupAlphabeticallyCaseInsensitive() throws Exception {
        List<EquipmentType> sorted = sort(List.of(
            type(1, "banana"),
            type(1, "Apple")));

        assertEquals(List.of("Apple", "banana"),
            sorted.stream().map(EquipmentType::getName).toList());
    }

    @Test
    void genericFallbackSortsLastWithinItsOwnStockGroupNotGloballyLast() throws Exception {
        List<EquipmentType> sorted = sort(List.of(
            type(0, "Genérico / Otro"),
            type(0, "Zebra"),
            type(5, "Apple")));

        // "Apple" (has stock) comes first; within the zero-stock group, "Zebra" still sorts
        // before "Genérico / Otro" even though Z > G alphabetically — generic is pinned last
        // within its own group, not just alphabetically ordered like everything else.
        assertEquals(List.of("Apple", "Zebra", "Genérico / Otro"),
            sorted.stream().map(EquipmentType::getName).toList());
    }

    @Test
    void genericFallbackWithStockStillSortsLastAmongStockedItemsOnly() throws Exception {
        List<EquipmentType> sorted = sort(List.of(
            type(5, "Genérico / Otro"),
            type(2, "Alpha"),
            type(0, "Beta")));

        assertEquals(List.of("Alpha", "Genérico / Otro", "Beta"),
            sorted.stream().map(EquipmentType::getName).toList());
    }
}
