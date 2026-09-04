package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.InventoryTransaction;
import com.foodwasteai.service.FoodItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class InventoryMergeAndStockHistoryTest {

    private FoodItemService foodItemService;

    @BeforeEach
    public void setUp() {
        foodItemService = new FoodItemService();
    }

    @Test
    @DisplayName("CASE A: Same name, unit, price, and expiry -> Merges quantity and preserves ID")
    public void testCaseA_SameNameUnitPriceExpiry_MergesQuantity() throws SQLException {
        String testSuffix = "_" + System.currentTimeMillis();
        String name = "Milk" + testSuffix;
        LocalDate expiry = LocalDate.of(2026, 9, 1);
        BigDecimal price = new BigDecimal("5000.00");

        // Initial: 10 liter
        FoodItem item1 = new FoodItem(null, name, "Dairy", new BigDecimal("10.00"), "liter", price, expiry, BigDecimal.ZERO);
        FoodItem saved1 = foodItemService.addFoodItem(item1, 1L);
        assertNotNull(saved1.getId(), "Initial item must have ID");
        Long initialId = saved1.getId();
        assertEquals(0, new BigDecimal("10.00").compareTo(saved1.getQuantity()));

        // Add 5 liter with exact same attributes
        FoodItem item2 = new FoodItem(null, name, "Dairy", new BigDecimal("5.00"), "liter", price, expiry, BigDecimal.ZERO);
        FoodItem saved2 = foodItemService.addFoodItem(item2, 1L);

        assertEquals(initialId, saved2.getId(), "Must merge into the same food item ID");
        assertEquals(0, new BigDecimal("15.00").compareTo(saved2.getQuantity()), "Quantity must become 15 liter");

        // Verify stock addition history
        List<InventoryTransaction> history = foodItemService.getItemStockHistory(initialId);
        assertTrue(history.size() >= 2, "Must have at least initial stock and addition transactions");
        assertEquals(0, new BigDecimal("5.00").compareTo(history.get(0).getQuantity()), "Newest transaction must be +5");
    }

    @Test
    @DisplayName("CASE B: Same name, unit, expiry, but different price -> Creates NEW ID")
    public void testCaseB_DifferentPrice_CreatesNewId() throws SQLException {
        String testSuffix = "_" + System.currentTimeMillis();
        String name = "Milk" + testSuffix;
        LocalDate expiry = LocalDate.of(2026, 9, 1);

        // Initial: price 5000
        FoodItem item1 = new FoodItem(null, name, "Dairy", new BigDecimal("10.00"), "liter", new BigDecimal("5000.00"), expiry, BigDecimal.ZERO);
        FoodItem saved1 = foodItemService.addFoodItem(item1, 1L);

        // Add: price 5500
        FoodItem item2 = new FoodItem(null, name, "Dairy", new BigDecimal("5.00"), "liter", new BigDecimal("5500.00"), expiry, BigDecimal.ZERO);
        FoodItem saved2 = foodItemService.addFoodItem(item2, 1L);

        assertNotEquals(saved1.getId(), saved2.getId(), "Different price must create a NEW ID");
        assertEquals(0, new BigDecimal("10.00").compareTo(saved1.getQuantity()));
        assertEquals(0, new BigDecimal("5.00").compareTo(saved2.getQuantity()));
    }

    @Test
    @DisplayName("CASE C: Same name, unit, price, but different expiry -> Creates NEW ID")
    public void testCaseC_DifferentExpiry_CreatesNewId() throws SQLException {
        String testSuffix = "_" + System.currentTimeMillis();
        String name = "Milk" + testSuffix;
        BigDecimal price = new BigDecimal("5000.00");

        // Initial: expiry 2026-09-01
        FoodItem item1 = new FoodItem(null, name, "Dairy", new BigDecimal("10.00"), "liter", price, LocalDate.of(2026, 9, 1), BigDecimal.ZERO);
        FoodItem saved1 = foodItemService.addFoodItem(item1, 1L);

        // Add: expiry 2026-09-03
        FoodItem item2 = new FoodItem(null, name, "Dairy", new BigDecimal("5.00"), "liter", price, LocalDate.of(2026, 9, 3), BigDecimal.ZERO);
        FoodItem saved2 = foodItemService.addFoodItem(item2, 1L);

        assertNotEquals(saved1.getId(), saved2.getId(), "Different expiry must create a NEW ID");
        assertEquals(0, new BigDecimal("10.00").compareTo(saved1.getQuantity()));
        assertEquals(0, new BigDecimal("5.00").compareTo(saved2.getQuantity()));
    }

    @Test
    @DisplayName("CASE D: Case-insensitive and trimmed name matching -> Merges quantity")
    public void testCaseD_CaseInsensitiveAndTrim_Merges() throws SQLException {
        String baseName = "bread_" + System.currentTimeMillis();
        LocalDate expiry = LocalDate.of(2026, 9, 10);
        BigDecimal price = new BigDecimal("2500.00");

        // Initial: lowercase
        FoodItem item1 = new FoodItem(null, baseName.toLowerCase(), "Bakery", new BigDecimal("8.00"), "pcs", price, expiry, BigDecimal.ZERO);
        FoodItem saved1 = foodItemService.addFoodItem(item1, 1L);

        // Add: uppercase with whitespace
        FoodItem item2 = new FoodItem(null, "  " + baseName.toUpperCase() + "  ", "Bakery", new BigDecimal("4.00"), "pcs", price, expiry, BigDecimal.ZERO);
        FoodItem saved2 = foodItemService.addFoodItem(item2, 1L);

        assertEquals(saved1.getId(), saved2.getId(), "Case and whitespace differences must still match the same ID");
        assertEquals(0, new BigDecimal("12.00").compareTo(saved2.getQuantity()), "Quantity must become 12 pcs");
    }

    @Test
    @DisplayName("CASE E: Incompatible units (e.g. liter vs pcs) -> Do NOT merge")
    public void testCaseE_DifferentUnit_DoesNotMerge() throws SQLException {
        String testSuffix = "_" + System.currentTimeMillis();
        String name = "Yogurt" + testSuffix;
        LocalDate expiry = LocalDate.of(2026, 9, 5);
        BigDecimal price = new BigDecimal("3000.00");

        // Initial: 10 liter
        FoodItem item1 = new FoodItem(null, name, "Dairy", new BigDecimal("10.00"), "liter", price, expiry, BigDecimal.ZERO);
        FoodItem saved1 = foodItemService.addFoodItem(item1, 1L);

        // Add: 5 pcs
        FoodItem item2 = new FoodItem(null, name, "Dairy", new BigDecimal("5.00"), "pcs", price, expiry, BigDecimal.ZERO);
        FoodItem saved2 = foodItemService.addFoodItem(item2, 1L);

        assertNotEquals(saved1.getId(), saved2.getId(), "Incompatible units must create separate rows with different IDs");
        assertEquals("liter", saved1.getUnit());
        assertEquals("pcs", saved2.getUnit());
    }

    @Test
    @DisplayName("CASE F: Different ID = Different History (Strict isolation per food_item_id)")
    public void testCaseF_HistoryIsolatedPerFoodItemId() throws SQLException {
        String testSuffix = "_" + System.currentTimeMillis();
        String name = "Juice" + testSuffix;
        LocalDate expiry1 = LocalDate.of(2026, 9, 1);
        LocalDate expiry2 = LocalDate.of(2026, 9, 5);
        BigDecimal price = new BigDecimal("4000.00");

        // Batch 1: ID A
        FoodItem itemA = new FoodItem(null, name, "Produce", new BigDecimal("10.00"), "liter", price, expiry1, BigDecimal.ZERO);
        FoodItem savedA = foodItemService.addFoodItem(itemA, 1L);
        // Add to Batch 1
        FoodItem addA = new FoodItem(null, name, "Produce", new BigDecimal("5.00"), "liter", price, expiry1, BigDecimal.ZERO);
        foodItemService.addFoodItem(addA, 1L);

        // Batch 2: ID B
        FoodItem itemB = new FoodItem(null, name, "Produce", new BigDecimal("20.00"), "liter", price, expiry2, BigDecimal.ZERO);
        FoodItem savedB = foodItemService.addFoodItem(itemB, 1L);

        List<InventoryTransaction> historyA = foodItemService.getItemStockHistory(savedA.getId());
        List<InventoryTransaction> historyB = foodItemService.getItemStockHistory(savedB.getId());

        for (InventoryTransaction tx : historyA) {
            assertEquals(savedA.getId(), tx.getFoodItemId(), "History A must strictly contain only item A transactions");
        }
        for (InventoryTransaction tx : historyB) {
            assertEquals(savedB.getId(), tx.getFoodItemId(), "History B must strictly contain only item B transactions");
        }
        assertTrue(historyA.size() >= 2);
        assertTrue(historyB.size() >= 1);
    }

    @Test
    @DisplayName("Dynamic support for arbitrary future products (Apples, Rice, Pork, Curry)")
    public void testArbitraryFutureProducts_DynamicSupport() throws SQLException {
        String[] products = { "Fuji Apples " + System.currentTimeMillis(), "Jasmine Rice " + System.currentTimeMillis(), "Organic Pork " + System.currentTimeMillis() };
        String[] units = { "kg", "kg", "kg" };

        for (int i = 0; i < products.length; i++) {
            LocalDate exp = LocalDate.now().plusDays(10 + i);
            BigDecimal price = new BigDecimal(3000 + (i * 1000));
            FoodItem initial = foodItemService.addFoodItem(
                    new FoodItem(null, products[i], "Produce", new BigDecimal("10.00"), units[i], price, exp, BigDecimal.ZERO), 1L
            );
            FoodItem add = foodItemService.addFoodItem(
                    new FoodItem(null, products[i], "Produce", new BigDecimal("6.00"), units[i], price, exp, BigDecimal.ZERO), 1L
            );
            assertEquals(initial.getId(), add.getId(), "Dynamic product " + products[i] + " must merge correctly");
            assertEquals(0, new BigDecimal("16.00").compareTo(add.getQuantity()));
        }
    }
}