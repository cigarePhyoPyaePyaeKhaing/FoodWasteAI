package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.Sale;
import com.foodwasteai.model.WasteRecord;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.SalesService;
import com.foodwasteai.service.WasteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ServiceLayerTest {

    private FoodItemService foodItemService;
    private SalesService salesService;
    private WasteService wasteService;

    @BeforeEach
    public void setUp() {
        foodItemService = new FoodItemService();
        salesService = new SalesService(new com.foodwasteai.dao.SalesDao(), foodItemService);
        wasteService = new WasteService(new com.foodwasteai.dao.WasteRecordDao(), foodItemService);
    }

    @Test
    @DisplayName("Should perform full CRUD on FoodItemService")
    public void testFoodItemCrud() throws SQLException {
        FoodItem newItem = new FoodItem(null, "Test Mango", "Produce", new BigDecimal("20.00"), "kg",
                new BigDecimal("3000.00"), LocalDate.now().plusDays(5), new BigDecimal("5.00"));

        FoodItem created = foodItemService.createFoodItem(newItem, 1L);
        assertNotNull(created.getId());
        assertEquals("Test Mango", created.getName());
        assertEquals("OK", created.getStatus());

        Optional<FoodItem> found = foodItemService.getFoodItemById(created.getId());
        assertTrue(found.isPresent());

        // Update
        created.setQuantity(new BigDecimal("35.00"));
        boolean updated = foodItemService.updateFoodItem(created, 1L);
        assertTrue(updated);

        // Delete
        boolean deleted = foodItemService.deleteFoodItem(created.getId());
        assertTrue(deleted);
    }

    @Test
    @DisplayName("Should record sale and deduct inventory stock")
    public void testSaleRecording() throws SQLException {
        // Chicken Breast starts with 50 kg in fallback
        Optional<FoodItem> chickenBefore = foodItemService.getFoodItemById(1L);
        assertTrue(chickenBefore.isPresent());
        BigDecimal initialQty = chickenBefore.get().getQuantity();

        Sale sale = new Sale(1L, new BigDecimal("5.00"), new BigDecimal("6500.00"), null, 10, java.time.LocalDateTime.now());
        Sale recorded = salesService.recordSale(sale, 1L);

        assertNotNull(recorded.getId());
        assertEquals(0, new BigDecimal("32500.00").compareTo(recorded.getTotalAmount()));

        Optional<FoodItem> chickenAfter = foodItemService.getFoodItemById(1L);
        assertTrue(chickenAfter.isPresent());
        assertEquals(0, initialQty.subtract(new BigDecimal("5.00")).compareTo(chickenAfter.get().getQuantity()));
    }

    @Test
    @DisplayName("Should record waste and calculate monetary loss accurately")
    public void testWasteRecording() throws SQLException {
        WasteRecord record = new WasteRecord(1L, new BigDecimal("2.00"), WasteRecord.Reason.EXPIRED, null, java.time.LocalDateTime.now(), "Test spoilage");
        WasteRecord saved = wasteService.recordWaste(record, 1L);

        assertNotNull(saved.getId());
        assertEquals(0, new BigDecimal("13000.00").compareTo(saved.getMonetaryLoss())); // 2.00 * 6500 = 13000
    }
}
