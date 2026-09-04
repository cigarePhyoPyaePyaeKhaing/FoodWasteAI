package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.WasteRecord;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.PredictionService;
import com.foodwasteai.service.WasteService;
import com.foodwasteai.util.ExpiryStatusResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification test suite for Confirmed Waste to Inventory Quantity Synchronization.
 * Validates:
 * 1. Full-stock waste deduction (12 kg -> 0.00 kg)
 * 2. Partial-waste deduction (12 kg - 5 kg = 7.00 kg)
 * 3. Page reloads (GET requests never perform repeated deductions)
 * 4. Duplicate request idempotency (no duplicate waste rows, no double deductions)
 * 5. Negative inventory rejection (invalid waste rejected, stock intact)
 * 6. Future/new food item automatic applicability
 * 7. 0-inventory items naturally excluded from AI prediction
 * 8. Waste Records persist and remain visible when stock is 0
 * 9. Real backend 0.00 representation returned for inventory page
 * 10. Repeated Run Evaluation never double-deducts expired items
 */
public class ConfirmedWasteInventorySyncTest {

    private FoodItemService foodItemService;
    private WasteService wasteService;
    private PredictionService predictionService;

    @BeforeEach
    public void setUp() {
        foodItemService = new FoodItemService();
        wasteService = new WasteService(new com.foodwasteai.dao.WasteRecordDao(), foodItemService);
        predictionService = new PredictionService(
                new com.foodwasteai.prolog.PrologService(),
                foodItemService,
                new com.foodwasteai.dao.SalesDao(),
                new com.foodwasteai.dao.WasteRecordDao(),
                new com.foodwasteai.dao.PredictionDao(),
                wasteService
        );
    }

    @Test
    @DisplayName("1. Full-stock waste: inventory 12 kg, waste 12 kg -> inventory becomes exactly 0.00 kg")
    public void testFullStockWaste_InventoryBecomesZero() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Test Full Waste Pork " + System.currentTimeMillis(), "Meat",
                        new BigDecimal("12.00"), "kg", new BigDecimal("18000.00"),
                        LocalDate.now().plusDays(2), new BigDecimal("2.00")), 1L
        );
        Long itemId = item.getId();

        WasteRecord waste = new WasteRecord(
                itemId, new BigDecimal("12.00"), WasteRecord.Reason.EXPIRED, null, LocalDateTime.now(), "Full batch waste"
        );
        WasteRecord recorded = wasteService.recordWaste(waste, 1L);

        assertNotNull(recorded.getId());
        assertEquals(0, new BigDecimal("12.00").compareTo(recorded.getQuantityWasted()), "Waste record quantity must be 12.00 kg");
        assertEquals("kg", recorded.getUnit());

        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.get().getQuantity()), "Inventory quantity must become exactly 0");
        assertEquals("0.00", after.get().getQuantity().setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    @Test
    @DisplayName("2. Partial waste: inventory 12 kg, waste 5 kg -> inventory becomes 7.00 kg")
    public void testPartialWaste_InventoryDeductedMathematically() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Test Partial Waste Sugar " + System.currentTimeMillis(), "Baking",
                        new BigDecimal("12.00"), "kg", new BigDecimal("3500.00"),
                        LocalDate.now().plusDays(5), new BigDecimal("2.00")), 1L
        );
        Long itemId = item.getId();

        WasteRecord waste = new WasteRecord(
                itemId, new BigDecimal("5.00"), WasteRecord.Reason.DAMAGED, null, LocalDateTime.now(), "Bag damaged"
        );
        WasteRecord recorded = wasteService.recordWaste(waste, 1L);

        assertNotNull(recorded.getId());
        assertEquals(0, new BigDecimal("5.00").compareTo(recorded.getQuantityWasted()));

        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("7.00").compareTo(after.get().getQuantity()), "Inventory must be 12 - 5 = 7.00 kg");
        assertEquals("7.00", after.get().getQuantity().setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    @Test
    @DisplayName("3. Reload: repeated GET requests never perform another deduction")
    public void testReload_NeverPerformsDeduction() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Reload Safe Bread " + System.currentTimeMillis(), "Bakery",
                        new BigDecimal("12.00"), "pcs", new BigDecimal("1500.00"),
                        LocalDate.now().plusDays(3), new BigDecimal("2.00")), 1L
        );
        Long itemId = item.getId();

        // 1st waste operation: 4 pcs
        WasteRecord waste = new WasteRecord(
                itemId, new BigDecimal("4.00"), WasteRecord.Reason.EXPIRED, null, LocalDateTime.now(), "Initial disposal"
        );
        wasteService.recordWaste(waste, 1L);

        // Simulate reloading pages 10 times via GET equivalents
        for (int i = 0; i < 10; i++) {
            List<FoodItem> inv = foodItemService.getAllFoodItems();
            List<WasteRecord> records = wasteService.getAllWasteRecords();
            assertNotNull(inv);
            assertNotNull(records);
        }

        Optional<FoodItem> after10Reloads = foodItemService.getFoodItemById(itemId);
        assertTrue(after10Reloads.isPresent());
        assertEquals(0, new BigDecimal("8.00").compareTo(after10Reloads.get().getQuantity()),
                "Reloading must NEVER deduct inventory (must remain 8.00 pcs)");
    }

    @Test
    @DisplayName("4. Duplicate request: idempotent submission blocks duplicate waste and double deduction")
    public void testDuplicateSubmission_BlocksDuplicateAndDoubleDeduction() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Idempotent Beef " + System.currentTimeMillis(), "Meat",
                        new BigDecimal("15.00"), "kg", new BigDecimal("22000.00"),
                        LocalDate.now().plusDays(4), new BigDecimal("2.00")), 1L
        );
        Long itemId = item.getId();
        String token = "client_req_token_" + System.currentTimeMillis();

        WasteRecord w1 = new WasteRecord(itemId, new BigDecimal("5.00"), WasteRecord.Reason.SPOILED, null, LocalDateTime.now(), "Attempt 1");
        w1.setClientRequestId(token);
        WasteRecord r1 = wasteService.recordWaste(w1, 1L);

        WasteRecord w2 = new WasteRecord(itemId, new BigDecimal("5.00"), WasteRecord.Reason.SPOILED, null, LocalDateTime.now(), "Attempt 2 (duplicate)");
        w2.setClientRequestId(token);
        WasteRecord r2 = wasteService.recordWaste(w2, 1L);

        assertEquals(r1.getId(), r2.getId(), "Duplicate request must return the existing waste record");

        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("10.00").compareTo(after.get().getQuantity()),
                "Inventory must be deducted exactly once (15 - 5 = 10.00 kg, not 5.00 kg)");
    }

    @Test
    @DisplayName("5. Negative inventory rejection: waste exceeding available stock is rejected safely")
    public void testQuantityNeverBecomesNegative() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Strict Stock Cheese " + System.currentTimeMillis(), "Dairy",
                        new BigDecimal("10.00"), "kg", new BigDecimal("12000.00"),
                        LocalDate.now().plusDays(3), new BigDecimal("2.00")), 1L
        );
        Long itemId = item.getId();

        WasteRecord overWaste = new WasteRecord(
                itemId, new BigDecimal("15.00"), WasteRecord.Reason.EXPIRED, null, LocalDateTime.now(), "Over-waste"
        );

        assertThrows(IllegalArgumentException.class, () -> {
            wasteService.recordWaste(overWaste, 1L);
        }, "Excess waste must throw IllegalArgumentException");

        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("10.00").compareTo(after.get().getQuantity()), "Inventory must remain intact at 10.00 kg");
        assertTrue(after.get().getQuantity().compareTo(BigDecimal.ZERO) >= 0, "Inventory must never be negative");
    }

    @Test
    @DisplayName("6. Future/new food item follows the same rule without code changes")
    public void testFutureNewFoodItem_FollowsSameRule() throws SQLException {
        FoodItem newFood = foodItemService.createFoodItem(
                new FoodItem(null, "Organic Starfruit " + System.currentTimeMillis(), "Produce",
                        new BigDecimal("25.00"), "kg", new BigDecimal("4500.00"),
                        LocalDate.now().minusDays(1), new BigDecimal("5.00")), 1L
        );
        Long newId = newFood.getId();

        // Convert full stock
        WasteRecord waste = new WasteRecord(
                newId, newFood.getQuantity(), WasteRecord.Reason.EXPIRED, null, LocalDateTime.now(), "Expired starfruit batch"
        );
        wasteService.recordWaste(waste, 1L);

        Optional<FoodItem> after = foodItemService.getFoodItemById(newId);
        assertTrue(after.isPresent());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.get().getQuantity()), "New food item stock must become exactly 0.00");
    }

    @Test
    @DisplayName("7. Item with inventory 0 is naturally excluded from AI prediction")
    public void testZeroInventoryItem_NaturallyExcludedFromPrediction() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();
        LocalDate tomorrow = today.plusDays(1);

        FoodItem activeTomorrow = new FoodItem(101L, "Active Milk", "Dairy", new BigDecimal("10.00"), "liter",
                new BigDecimal("3000.00"), tomorrow, new BigDecimal("2.00"));

        FoodItem zeroStockItem = new FoodItem(102L, "Wasted Pork", "Meat", BigDecimal.ZERO, "kg",
                new BigDecimal("20000.00"), tomorrow, new BigDecimal("2.00"));

        List<FoodItem> items = List.of(activeTomorrow, zeroStockItem);

        // Batch assessment
        Map<String, Object> report = predictionService.assessInventory(items);
        @SuppressWarnings("unchecked")
        List<PrologAssessment> assessed = (List<PrologAssessment>) report.get("items");
        List<String> assessedNames = assessed.stream().map(a -> a.getFoodName().toLowerCase()).toList();

        assertFalse(assessedNames.contains("wasted pork"), "0-inventory item must be excluded from active prediction items");

        // Tomorrow prediction
        Map<String, Object> tomorrowPred = predictionService.calculateTomorrowPrediction(items);
        @SuppressWarnings("unchecked")
        List<PrologAssessment> tomorrowItems = (List<PrologAssessment>) tomorrowPred.get("items");
        List<String> tomorrowNames = tomorrowItems.stream().map(a -> a.getFoodName().toLowerCase()).toList();

        assertFalse(tomorrowNames.contains("wasted pork"), "0-inventory item must NOT appear in tomorrow prediction");
        assertTrue(tomorrowNames.contains("active milk"), "Active item with stock must be in tomorrow prediction");
    }

    @Test
    @DisplayName("8. Waste Record remains visible after inventory becomes 0")
    public void testWasteRecord_RemainsVisibleAfterStockZero() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Fish Batch " + System.currentTimeMillis(), "Seafood",
                        new BigDecimal("8.00"), "kg", new BigDecimal("15000.00"),
                        LocalDate.now().plusDays(2), new BigDecimal("1.00")), 1L
        );
        Long itemId = item.getId();

        WasteRecord waste = new WasteRecord(
                itemId, new BigDecimal("8.00"), WasteRecord.Reason.EXPIRED, null, LocalDateTime.now(), "Full fish batch waste"
        );
        wasteService.recordWaste(waste, 1L);

        // Verify stock is 0
        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.get().getQuantity()));

        // Verify waste records query still returns the historical waste record
        List<WasteRecord> itemWaste = wasteService.getWasteByFoodItemId(itemId);
        assertFalse(itemWaste.isEmpty(), "Waste record must remain visible");
        assertEquals(1, itemWaste.size());
        assertEquals(0, new BigDecimal("8.00").compareTo(itemWaste.get(0).getQuantityWasted()));
    }

    @Test
    @DisplayName("9. Real backend 0.00 representation returned for inventory page")
    public void testBackendRealZeroRepresentation() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Rice Bag " + System.currentTimeMillis(), "Grains",
                        new BigDecimal("20.00"), "kg", new BigDecimal("2500.00"),
                        LocalDate.now().plusDays(10), new BigDecimal("2.00")), 1L
        );
        Long itemId = item.getId();

        wasteService.recordWaste(new WasteRecord(itemId, new BigDecimal("20.00"), WasteRecord.Reason.SPOILED, null, LocalDateTime.now(), "Water damage"), 1L);

        FoodItem fetched = foodItemService.getFoodItemById(itemId).orElseThrow();
        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), fetched.getQuantity());
        assertEquals("0.00", fetched.getQuantity().toPlainString());
        assertEquals("kg", fetched.getUnit());
    }

    @Test
    @DisplayName("10. Automatic expiry-driven transition and Repeated Run Evaluation never double-deducts")
    public void testAutomaticExpiryTransition_AndRepeatedRunEvaluation() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();

        // Create expired item with unsold stock (12.00 kg)
        FoodItem expiredPork = foodItemService.createFoodItem(
                new FoodItem(null, "Auto Expired Pork " + System.currentTimeMillis(), "Meat",
                        new BigDecimal("12.00"), "kg", new BigDecimal("18000.00"),
                        today.minusDays(1), new BigDecimal("1.00")), 1L
        );
        Long porkId = expiredPork.getId();

        // Run Evaluation #1 (triggers convertExpiredInventoryToWaste)
        List<WasteRecord> convertedFirstRun = wasteService.convertExpiredInventoryToWaste(1L);
        assertFalse(convertedFirstRun.isEmpty(), "First run must convert the expired item");

        FoodItem afterRun1 = foodItemService.getFoodItemById(porkId).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(afterRun1.getQuantity()), "Stock must be exactly 0.00 after Run Evaluation");

        List<WasteRecord> wasteAfterRun1 = wasteService.getWasteByFoodItemId(porkId);
        assertEquals(1, wasteAfterRun1.size(), "Exactly 1 waste record must exist");
        assertEquals(0, new BigDecimal("12.00").compareTo(wasteAfterRun1.get(0).getQuantityWasted()));

        // Repeated Run Evaluation (e.g. 5 repeated evaluations)
        for (int i = 0; i < 5; i++) {
            List<WasteRecord> repeated = wasteService.convertExpiredInventoryToWaste(1L);
            assertTrue(repeated.isEmpty(), "Subsequent evaluation must NOT re-convert the 0-stock item");
        }

        // Inventory must remain 0.00 kg, and waste records must remain exactly 1
        FoodItem afterRepeated = foodItemService.getFoodItemById(porkId).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(afterRepeated.getQuantity()), "Stock must remain 0.00 kg");
        List<WasteRecord> wasteAfterRepeated = wasteService.getWasteByFoodItemId(porkId);
        assertEquals(1, wasteAfterRepeated.size(), "Must NOT have duplicate waste records");
    }
}
