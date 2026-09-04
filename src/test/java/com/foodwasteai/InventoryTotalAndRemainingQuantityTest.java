package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.PredictionItem;
import com.foodwasteai.model.Sale;
import com.foodwasteai.model.WasteRecord;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.SalesService;
import com.foodwasteai.service.WasteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rigorous test suite validating:
 * CASE A — New stock (Total = 10 kg, Remaining = 10 kg)
 * CASE B — Add same batch (Total = 15 kg, Remaining = 15 kg)
 * CASE C — Sale (Total = 15 kg, Remaining = 11 kg)
 * CASE D — Add after sale (Total = 20 kg, Remaining = 16 kg)
 * CASE E — Confirmed waste (Total = 20 kg, Remaining = 10 kg)
 * CASE F — Separate batch IDs (different price/expiry -> separate Total and Remaining)
 * CASE G — No double sale deduction
 * CASE H — No double waste deduction
 * CASE I — Website-wide consistency of remaining quantity
 * CASE J — Future items dynamic accounting
 */
public class InventoryTotalAndRemainingQuantityTest {

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
    @DisplayName("CASE A: New stock -> Total = 10 kg, Remaining = 10 kg")
    public void testCaseA_NewStock() throws SQLException {
        String name = "Apples_CaseA_" + System.currentTimeMillis();
        LocalDate expiry = LocalDate.now().plusDays(10);
        BigDecimal price = new BigDecimal("3500.00");

        FoodItem initial = new FoodItem(null, name, "Produce", new BigDecimal("10.00"), "kg", price, expiry, BigDecimal.ZERO);
        FoodItem saved = foodItemService.addFoodItem(initial, 1L);

        assertNotNull(saved.getId(), "Item must have an ID");
        assertEquals(0, new BigDecimal("10.00").compareTo(saved.getTotalQuantity()), "Total Quantity must be 10.00 kg");
        assertEquals(0, new BigDecimal("10.00").compareTo(saved.getRemainingQuantity()), "Remaining Quantity must be 10.00 kg");
        assertEquals(0, new BigDecimal("10.00").compareTo(saved.getQuantity()), "Authoritative quantity must match Remaining Quantity");

        // Verify re-fetch from service
        Optional<FoodItem> fetched = foodItemService.getFoodItemById(saved.getId());
        assertTrue(fetched.isPresent());
        assertEquals(0, new BigDecimal("10.00").compareTo(fetched.get().getTotalQuantity()), "Re-fetched Total Quantity must be 10.00 kg");
        assertEquals(0, new BigDecimal("10.00").compareTo(fetched.get().getRemainingQuantity()), "Re-fetched Remaining Quantity must be 10.00 kg");
    }

    @Test
    @DisplayName("CASE B: Add same batch -> Total = 15 kg, Remaining = 15 kg")
    public void testCaseB_AddSameBatch() throws SQLException {
        String name = "Beef_CaseB_" + System.currentTimeMillis();
        LocalDate expiry = LocalDate.now().plusDays(7);
        BigDecimal price = new BigDecimal("14000.00");

        // Initial: 10 kg
        FoodItem item1 = new FoodItem(null, name, "Meat", new BigDecimal("10.00"), "kg", price, expiry, BigDecimal.ZERO);
        FoodItem saved1 = foodItemService.addFoodItem(item1, 1L);
        Long id1 = saved1.getId();

        // Add: 5 kg with exact same name, unit, price, expiry
        FoodItem item2 = new FoodItem(null, name, "Meat", new BigDecimal("5.00"), "kg", price, expiry, BigDecimal.ZERO);
        FoodItem saved2 = foodItemService.addFoodItem(item2, 1L);

        assertEquals(id1, saved2.getId(), "Must merge into the exact same item ID");
        assertEquals(0, new BigDecimal("15.00").compareTo(saved2.getTotalQuantity()), "Total Quantity must be 15.00 kg");
        assertEquals(0, new BigDecimal("15.00").compareTo(saved2.getRemainingQuantity()), "Remaining Quantity must be 15.00 kg");

        // Re-fetch check
        FoodItem fetched = foodItemService.getFoodItemById(id1).orElseThrow();
        assertEquals(0, new BigDecimal("15.00").compareTo(fetched.getTotalQuantity()), "Re-fetched Total Quantity must be 15.00 kg");
        assertEquals(0, new BigDecimal("15.00").compareTo(fetched.getRemainingQuantity()), "Re-fetched Remaining Quantity must be 15.00 kg");
    }

    @Test
    @DisplayName("CASE C: Sale -> Total = 15 kg, Remaining = 11 kg (Sell 4 kg)")
    public void testCaseC_SaleDeduction() throws SQLException {
        String name = "Rice_CaseC_" + System.currentTimeMillis();
        LocalDate expiry = LocalDate.now().plusDays(30);
        BigDecimal price = new BigDecimal("4500.00");

        // Initial 10 kg + 5 kg = 15 kg Total & Remaining
        FoodItem saved = foodItemService.addFoodItem(new FoodItem(null, name, "Grains", new BigDecimal("10.00"), "kg", price, expiry, BigDecimal.ZERO), 1L);
        saved = foodItemService.addFoodItem(new FoodItem(null, name, "Grains", new BigDecimal("5.00"), "kg", price, expiry, BigDecimal.ZERO), 1L);
        Long id = saved.getId();

        // Sell: 4 kg
        Sale sale = new Sale(id, new BigDecimal("4.00"), price, null, 1, LocalDateTime.now());
        salesService.recordSale(sale, 1L);

        // Verify
        FoodItem afterSale = foodItemService.getFoodItemById(id).orElseThrow();
        assertEquals(0, new BigDecimal("15.00").compareTo(afterSale.getTotalQuantity()), "Total Quantity must remain 15.00 kg after sale");
        assertEquals(0, new BigDecimal("11.00").compareTo(afterSale.getRemainingQuantity()), "Remaining Quantity must be 11.00 kg after sale");
        assertEquals(0, new BigDecimal("11.00").compareTo(afterSale.getQuantity()), "Quantity must equal 11.00 kg");
    }

    @Test
    @DisplayName("CASE D: Add after sale -> Total = 20 kg, Remaining = 16 kg (Add 5 kg to 11 kg remaining)")
    public void testCaseD_AddAfterSale() throws SQLException {
        String name = "Flour_CaseD_" + System.currentTimeMillis();
        LocalDate expiry = LocalDate.now().plusDays(25);
        BigDecimal price = new BigDecimal("3000.00");

        // Step 1: Initial 10 kg
        FoodItem saved = foodItemService.addFoodItem(new FoodItem(null, name, "Baking", new BigDecimal("10.00"), "kg", price, expiry, BigDecimal.ZERO), 1L);
        // Step 2: Add 5 kg -> Total 15, Remaining 15
        saved = foodItemService.addFoodItem(new FoodItem(null, name, "Baking", new BigDecimal("5.00"), "kg", price, expiry, BigDecimal.ZERO), 1L);
        Long id = saved.getId();
        // Step 3: Sell 4 kg -> Total 15, Remaining 11
        salesService.recordSale(new Sale(id, new BigDecimal("4.00"), price, null, 1, LocalDateTime.now()), 1L);

        // Step 4: Add 5 kg to same batch
        FoodItem addedAgain = foodItemService.addFoodItem(new FoodItem(null, name, "Baking", new BigDecimal("5.00"), "kg", price, expiry, BigDecimal.ZERO), 1L);

        assertEquals(id, addedAgain.getId(), "Must maintain the same item ID");
        assertEquals(0, new BigDecimal("20.00").compareTo(addedAgain.getTotalQuantity()), "Total Quantity must be 20.00 kg (10 + 5 + 5)");
        assertEquals(0, new BigDecimal("16.00").compareTo(addedAgain.getRemainingQuantity()), "Remaining Quantity must be 16.00 kg (11 + 5)");

        FoodItem fetched = foodItemService.getFoodItemById(id).orElseThrow();
        assertEquals(0, new BigDecimal("20.00").compareTo(fetched.getTotalQuantity()));
        assertEquals(0, new BigDecimal("16.00").compareTo(fetched.getRemainingQuantity()));
    }

    @Test
    @DisplayName("CASE E: Confirmed waste -> Total = 20 kg, Remaining = 10 kg (Confirmed waste 6 kg)")
    public void testCaseE_ConfirmedWasteDeduction() throws SQLException {
        String name = "Fish_CaseE_" + System.currentTimeMillis();
        LocalDate expiry = LocalDate.now().plusDays(4);
        BigDecimal price = new BigDecimal("8000.00");

        // Initial 10 + 5 = 15, Sell 4 = 11, Add 5 = 16 (Total 20)
        FoodItem saved = foodItemService.addFoodItem(new FoodItem(null, name, "Seafood", new BigDecimal("10.00"), "kg", price, expiry, BigDecimal.ZERO), 1L);
        foodItemService.addFoodItem(new FoodItem(null, name, "Seafood", new BigDecimal("5.00"), "kg", price, expiry, BigDecimal.ZERO), 1L);
        Long id = saved.getId();
        salesService.recordSale(new Sale(id, new BigDecimal("4.00"), price, null, 1, LocalDateTime.now()), 1L);
        foodItemService.addFoodItem(new FoodItem(null, name, "Seafood", new BigDecimal("5.00"), "kg", price, expiry, BigDecimal.ZERO), 1L);

        // Confirmed waste: 6 kg
        WasteRecord waste = new WasteRecord(id, new BigDecimal("6.00"), WasteRecord.Reason.EXPIRED, price.multiply(new BigDecimal("6.00")), LocalDateTime.now(), "Confirmed spoilage");
        wasteService.recordWaste(waste, 1L);

        FoodItem afterWaste = foodItemService.getFoodItemById(id).orElseThrow();
        assertEquals(0, new BigDecimal("20.00").compareTo(afterWaste.getTotalQuantity()), "Total Quantity must remain 20.00 kg after confirmed waste");
        assertEquals(0, new BigDecimal("10.00").compareTo(afterWaste.getRemainingQuantity()), "Remaining Quantity must be 10.00 kg (16 - 6)");
        assertEquals(0, new BigDecimal("10.00").compareTo(afterWaste.getQuantity()), "Authoritative quantity must be 10.00 kg");
    }

    @Test
    @DisplayName("CASE F: Separate batch IDs -> Different price or expiry gets separate Total and Remaining")
    public void testCaseF_SeparateBatchIds() throws SQLException {
        String name = "Pork_CaseF_" + System.currentTimeMillis();

        // Batch 1: Price 9000, Expiry Day +5, Qty 10
        FoodItem batch1 = foodItemService.addFoodItem(
                new FoodItem(null, name, "Meat", new BigDecimal("10.00"), "kg", new BigDecimal("9000.00"), LocalDate.now().plusDays(5), BigDecimal.ZERO), 1L);

        // Batch 2: Price 9500 (different price!), Expiry Day +5, Qty 8
        FoodItem batch2 = foodItemService.addFoodItem(
                new FoodItem(null, name, "Meat", new BigDecimal("8.00"), "kg", new BigDecimal("9500.00"), LocalDate.now().plusDays(5), BigDecimal.ZERO), 1L);

        // Batch 3: Price 9000, Expiry Day +12 (different expiry!), Qty 12
        FoodItem batch3 = foodItemService.addFoodItem(
                new FoodItem(null, name, "Meat", new BigDecimal("12.00"), "kg", new BigDecimal("9000.00"), LocalDate.now().plusDays(12), BigDecimal.ZERO), 1L);

        assertNotEquals(batch1.getId(), batch2.getId(), "Batch 1 and Batch 2 must have separate IDs due to price difference");
        assertNotEquals(batch1.getId(), batch3.getId(), "Batch 1 and Batch 3 must have separate IDs due to expiry difference");

        // Verify independent Total and Remaining
        assertEquals(0, new BigDecimal("10.00").compareTo(batch1.getTotalQuantity()));
        assertEquals(0, new BigDecimal("10.00").compareTo(batch1.getRemainingQuantity()));

        assertEquals(0, new BigDecimal("8.00").compareTo(batch2.getTotalQuantity()));
        assertEquals(0, new BigDecimal("8.00").compareTo(batch2.getRemainingQuantity()));

        assertEquals(0, new BigDecimal("12.00").compareTo(batch3.getTotalQuantity()));
        assertEquals(0, new BigDecimal("12.00").compareTo(batch3.getRemainingQuantity()));
    }

    @Test
    @DisplayName("CASE G: No double sale deduction -> Sale is deducted exactly once")
    public void testCaseG_NoDoubleSaleDeduction() throws SQLException {
        String name = "Chicken_CaseG_" + System.currentTimeMillis();
        LocalDate expiry = LocalDate.now().plusDays(6);
        BigDecimal price = new BigDecimal("6000.00");

        FoodItem item = foodItemService.addFoodItem(
                new FoodItem(null, name, "Poultry", new BigDecimal("20.00"), "kg", price, expiry, BigDecimal.ZERO), 1L);
        Long id = item.getId();

        Sale sale = new Sale(id, new BigDecimal("5.00"), price, null, 1, LocalDateTime.now());
        salesService.recordSale(sale, 1L);

        FoodItem fetched = foodItemService.getFoodItemById(id).orElseThrow();
        assertEquals(0, new BigDecimal("15.00").compareTo(fetched.getRemainingQuantity()), "Remaining must be exactly 15.00 (not double deducted)");
        assertEquals(0, new BigDecimal("20.00").compareTo(fetched.getTotalQuantity()), "Total must remain 20.00");
    }

    @Test
    @DisplayName("CASE H: No double waste deduction -> Confirmed waste is deducted exactly once")
    public void testCaseH_NoDoubleWasteDeduction() throws SQLException {
        String name = "Tomatoes_CaseH_" + System.currentTimeMillis();
        LocalDate expiry = LocalDate.now().plusDays(4);
        BigDecimal price = new BigDecimal("2000.00");

        FoodItem item = foodItemService.addFoodItem(
                new FoodItem(null, name, "Produce", new BigDecimal("15.00"), "kg", price, expiry, BigDecimal.ZERO), 1L);
        Long id = item.getId();

        WasteRecord waste = new WasteRecord(id, new BigDecimal("3.00"), WasteRecord.Reason.SPOILED, new BigDecimal("6000.00"), LocalDateTime.now(), "Overripe");
        wasteService.recordWaste(waste, 1L);

        FoodItem fetched = foodItemService.getFoodItemById(id).orElseThrow();
        assertEquals(0, new BigDecimal("12.00").compareTo(fetched.getRemainingQuantity()), "Remaining must be exactly 12.00 (not double deducted)");
        assertEquals(0, new BigDecimal("15.00").compareTo(fetched.getTotalQuantity()), "Total must remain 15.00");
    }

    @Test
    @DisplayName("CASE I: Website-wide consistency -> Same food_item_id exposes identical remaining quantity across all models")
    public void testCaseI_WebsiteWideConsistency() throws SQLException {
        String name = "Cooking_Oil_CaseI_" + System.currentTimeMillis();
        LocalDate expiry = LocalDate.now().plusDays(60);
        BigDecimal price = new BigDecimal("12000.00");

        FoodItem item = foodItemService.addFoodItem(
                new FoodItem(null, name, "Pantry", new BigDecimal("25.00"), "liter", price, expiry, BigDecimal.ZERO), 1L);
        salesService.recordSale(new Sale(item.getId(), new BigDecimal("7.00"), price, null, 1, LocalDateTime.now()), 1L);

        FoodItem currentFood = foodItemService.getFoodItemById(item.getId()).orElseThrow();
        BigDecimal expectedRemaining = new BigDecimal("18.00");

        // 1. FoodItem model consistency
        assertEquals(0, expectedRemaining.compareTo(currentFood.getRemainingQuantity()));
        assertEquals(0, expectedRemaining.compareTo(currentFood.getQuantity()));

        // 2. PredictionItem model consistency
        PredictionItem pi = new PredictionItem();
        pi.setFoodItemId(item.getId());
        pi.setCurrentStock(currentFood.getQuantity());
        assertEquals(0, expectedRemaining.compareTo(pi.getRemainingQuantity()));
        assertEquals(0, expectedRemaining.compareTo(pi.getStock()));
        assertEquals(0, expectedRemaining.compareTo(pi.getCurrentStock()));

        // 3. PrologAssessment model consistency
        PrologAssessment pa = new PrologAssessment();
        pa.setFoodItemId(item.getId());
        pa.setStock(currentFood.getQuantity().doubleValue());
        assertEquals(18.0, pa.getRemainingQuantity(), 0.001);
        assertEquals(18.0, pa.getStock(), 0.001);
    }

    @Test
    @DisplayName("CASE J: Future item -> Works dynamically for brand new food item without code changes")
    public void testCaseJ_FutureItemDynamicAccounting() throws SQLException {
        String futureItemName = "Organic Dragonfruit Puree " + System.currentTimeMillis();
        LocalDate futureExpiry = LocalDate.now().plusDays(14);
        BigDecimal futurePrice = new BigDecimal("18500.00");

        // Add initial 50 packages
        FoodItem futureItem = foodItemService.addFoodItem(
                new FoodItem(null, futureItemName, "Beverage", new BigDecimal("50.00"), "pack", futurePrice, futureExpiry, BigDecimal.ZERO), 1L);
        Long futureId = futureItem.getId();
        assertNotNull(futureId);

        // Add 25 more packages
        foodItemService.addFoodItem(
                new FoodItem(null, futureItemName, "Beverage", new BigDecimal("25.00"), "pack", futurePrice, futureExpiry, BigDecimal.ZERO), 1L);

        // Sell 15 packages
        salesService.recordSale(new Sale(futureId, new BigDecimal("15.00"), futurePrice, null, 1, LocalDateTime.now()), 1L);

        // Waste 5 packages
        wasteService.recordWaste(new WasteRecord(futureId, new BigDecimal("5.00"), WasteRecord.Reason.DAMAGED, futurePrice.multiply(new BigDecimal("5.00")), LocalDateTime.now(), "Packaging leak"), 1L);

        FoodItem result = foodItemService.getFoodItemById(futureId).orElseThrow();
        assertEquals(0, new BigDecimal("75.00").compareTo(result.getTotalQuantity()), "Total quantity must be 75.00 pack (50 + 25)");
        assertEquals(0, new BigDecimal("55.00").compareTo(result.getRemainingQuantity()), "Remaining quantity must be 55.00 pack (75 - 15 - 5)");
        assertEquals(0, new BigDecimal("55.00").compareTo(result.getQuantity()), "Quantity must match Remaining Quantity (55.00)");
    }
}
