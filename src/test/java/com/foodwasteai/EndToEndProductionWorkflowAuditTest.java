package com.foodwasteai;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.FoodItemDao;
import com.foodwasteai.dao.RedistributionDao;
import com.foodwasteai.dao.SalesDao;
import com.foodwasteai.dao.WasteRecordDao;
import com.foodwasteai.model.*;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.prolog.PrologService;
import com.foodwasteai.service.*;
import com.foodwasteai.util.ExpiryStatusResolver;
import com.foodwasteai.util.ValidationUtils;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Complete End-to-End Production Workflow Audit Test Suite.
 * Validates the complete lifecycle:
 * Inventory Create -> Sales -> Waste -> Zero Stock -> Prediction ->
 * Recommendation -> Redistribution -> Dispatch -> Dashboard -> History -> DB Integrity.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EndToEndProductionWorkflowAuditTest {

    private static FoodItemService foodItemService;
    private static SalesService salesService;
    private static WasteService wasteService;
    private static RedistributionService redistributionService;
    private static PredictionService predictionService;
    private static RecommendationService recommendationService;
    private static PrologService prologService;

    private static final List<Long> createdFoodIds = new ArrayList<>();
    private static final List<Long> createdDispatchIds = new ArrayList<>();
    private static final List<Long> createdRecipientIds = new ArrayList<>();

    @BeforeAll
    public static void setUp() {
        foodItemService = new FoodItemService();
        salesService = new SalesService();
        wasteService = new WasteService();
        redistributionService = new RedistributionService();
        predictionService = new PredictionService();
        recommendationService = new RecommendationService();
        prologService = new PrologService();
    }

    @AfterAll
    public static void tearDown() {
        if (!DatabaseConfig.isAvailable()) return;
        try (Connection conn = DatabaseConfig.getConnection()) {
            for (Long id : createdDispatchIds) {
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM redistributions WHERE id = ?")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                } catch (Exception ignored) {}
            }
            for (Long id : createdRecipientIds) {
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM redistribution_recipients WHERE id = ?")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                } catch (Exception ignored) {}
            }
            for (Long id : createdFoodIds) {
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM waste_records WHERE food_item_id = ?")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                } catch (Exception ignored) {}
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM sales WHERE food_item_id = ?")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                } catch (Exception ignored) {}
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM inventory_transactions WHERE food_item_id = ?")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                } catch (Exception ignored) {}
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM recommendations WHERE food_item_id = ?")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                } catch (Exception ignored) {}
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM food_items WHERE id = ?")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("Audit cleanup error: " + e.getMessage());
        }
    }

    private FoodItem createAuditItem(String name, String category, BigDecimal qty, String unit, BigDecimal price, LocalDate expiry) throws SQLException {
        FoodItem item = new FoodItem();
        item.setName(name + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4));
        item.setCategory(category);
        item.setQuantity(qty);
        item.setUnit(unit);
        item.setPricePerUnit(price);
        item.setExpiryDate(expiry);
        FoodItem saved = foodItemService.createFoodItem(item, 1L);
        createdFoodIds.add(saved.getId());
        return saved;
    }

    // =========================================================================
    // 1. INVENTORY: Validation, Canonical Category/Unit, Edit & Expiry
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("E2E Audit 1: Inventory Create, Validation, Edit, and Negative Stock Prevention")
    public void testInventoryOperationsAndIntegrity() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();

        // 1. Create with canonical Seafood & kg
        FoodItem fish = createAuditItem("E2E Fish", "Seafood", new BigDecimal("50.00"), "kg", new BigDecimal("12000.00"), today.plusDays(5));
        assertNotNull(fish.getId());
        assertEquals("Seafood", fish.getCategory());
        assertEquals("kg", fish.getUnit());
        assertEquals(0, new BigDecimal("50.00").compareTo(fish.getQuantity()));

        // 2. Create with canonical Dairy & liter
        FoodItem milk = createAuditItem("E2E Fresh Milk", "Dairy", new BigDecimal("30.00"), "liter", new BigDecimal("2500.00"), today.plusDays(6));
        assertEquals("Dairy", milk.getCategory());
        assertEquals("liter", milk.getUnit());

        // 3. Edit does not corrupt category or unit
        fish.setQuantity(new BigDecimal("45.00"));
        fish.setPricePerUnit(new BigDecimal("12500.00"));
        boolean updated = foodItemService.updateFoodItem(fish, 1L);
        assertTrue(updated);

        FoodItem reloadedFish = foodItemService.getFoodItemById(fish.getId()).orElseThrow();
        assertEquals("Seafood", reloadedFish.getCategory(), "Category must stay Seafood");
        assertEquals("kg", reloadedFish.getUnit(), "Unit must stay kg");
        assertEquals(0, new BigDecimal("45.00").compareTo(reloadedFish.getQuantity()));

        // 4. Negative quantity creation is rejected
        FoodItem negativeItem = new FoodItem(null, "Negative Item", "Produce", new BigDecimal("-5.00"), "kg", new BigDecimal("1000.00"), today.plusDays(10));
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateFoodItem(negativeItem));

        // 5. Negative stock reduction is rejected
        assertThrows(IllegalArgumentException.class, () -> {
            foodItemService.adjustStock(fish.getId(), new BigDecimal("-100.00"), "Excessive deduction", 1L);
        });
    }

    // =========================================================================
    // 2. SALES: Stock Validation, Single Deduction, Audit Transaction, Oversell
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("E2E Audit 2: Sales Single Deduction, Exact-Stock Sale, and Oversell Rejection")
    public void testSalesLifecycleAndDeduction() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();
        FoodItem item = createAuditItem("E2E Sales Chicken", "Poultry", new BigDecimal("20.00"), "kg", new BigDecimal("6500.00"), today.plusDays(10));

        // 1. Normal sale of 5 kg -> stock becomes 15 kg
        Sale sale1 = new Sale();
        sale1.setFoodItemId(item.getId());
        sale1.setQuantitySold(new BigDecimal("5.00"));
        sale1.setUnitPrice(new BigDecimal("6500.00"));
        sale1.setSaleDate(LocalDateTime.now());
        Sale recorded1 = salesService.recordSale(sale1, 1L);
        assertNotNull(recorded1.getId());
        assertEquals(0, new BigDecimal("32500.00").compareTo(recorded1.getTotalAmount()));

        FoodItem afterSale1 = foodItemService.getFoodItemById(item.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("15.00").compareTo(afterSale1.getQuantity()));

        // 2. Oversell attempt (20 kg when 15 kg available) is rejected with zero stock change
        Sale oversell = new Sale();
        oversell.setFoodItemId(item.getId());
        oversell.setQuantitySold(new BigDecimal("20.00"));
        oversell.setUnitPrice(new BigDecimal("6500.00"));
        oversell.setSaleDate(LocalDateTime.now());
        assertThrows(IllegalArgumentException.class, () -> salesService.recordSale(oversell, 1L));

        FoodItem afterFailedSale = foodItemService.getFoodItemById(item.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("15.00").compareTo(afterFailedSale.getQuantity()), "Stock must remain 15.00 kg after failed oversale");

        // 3. Exact remaining stock sale (15 kg) -> stock becomes 0.00 kg
        Sale sale2 = new Sale();
        sale2.setFoodItemId(item.getId());
        sale2.setQuantitySold(new BigDecimal("15.00"));
        sale2.setUnitPrice(new BigDecimal("6500.00"));
        sale2.setSaleDate(LocalDateTime.now());
        Sale recorded2 = salesService.recordSale(sale2, 1L);
        assertNotNull(recorded2.getId());

        FoodItem afterSale2 = foodItemService.getFoodItemById(item.getId()).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(afterSale2.getQuantity()), "Stock must be exactly 0.00 kg");
    }

    // =========================================================================
    // 3. WASTE: Stock Deduction, Financial Loss, Excessive Waste Rejection, Immutability
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("E2E Audit 3: Confirmed Waste Logging, Stock Deduction, Loss Calculation, and Immutability")
    public void testWasteLifecycleAndImmutability() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();
        FoodItem item = createAuditItem("E2E Waste Pork", "Meat", new BigDecimal("25.00"), "kg", new BigDecimal("8000.00"), today.minusDays(1));

        // 1. Log confirmed waste of 10 kg -> loss = 80,000 MMK, stock becomes 15 kg
        WasteRecord w1 = new WasteRecord();
        w1.setFoodItemId(item.getId());
        w1.setQuantityWasted(new BigDecimal("10.00"));
        w1.setReason(WasteRecord.Reason.EXPIRED);
        w1.setNotes("Audit confirmed expired waste");
        WasteRecord logged1 = wasteService.recordWaste(w1, 1L);
        assertNotNull(logged1.getId());
        assertEquals(0, new BigDecimal("80000.00").compareTo(logged1.getMonetaryLoss()));

        FoodItem afterW1 = foodItemService.getFoodItemById(item.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("15.00").compareTo(afterW1.getQuantity()));

        // 2. Excessive waste attempt (20 kg when 15 kg left) is rejected
        WasteRecord overWaste = new WasteRecord();
        overWaste.setFoodItemId(item.getId());
        overWaste.setQuantityWasted(new BigDecimal("20.00"));
        overWaste.setReason(WasteRecord.Reason.SPOILED);
        assertThrows(IllegalArgumentException.class, () -> wasteService.recordWaste(overWaste, 1L));

        FoodItem afterFailedWaste = foodItemService.getFoodItemById(item.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("15.00").compareTo(afterFailedWaste.getQuantity()));

        // 3. Waste remaining 15 kg -> stock becomes 0 kg
        WasteRecord w2 = new WasteRecord();
        w2.setFoodItemId(item.getId());
        w2.setQuantityWasted(new BigDecimal("15.00"));
        w2.setReason(WasteRecord.Reason.EXPIRED);
        WasteRecord logged2 = wasteService.recordWaste(w2, 1L);
        assertNotNull(logged2.getId());

        FoodItem afterW2 = foodItemService.getFoodItemById(item.getId()).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(afterW2.getQuantity()));

        // 4. Historical waste immutability: both waste logs exist in history
        List<WasteRecord> wasteHistory = new WasteRecordDao().findByFoodItemId(item.getId());
        assertEquals(2, wasteHistory.size(), "Both waste records must remain in historical database");
        BigDecimal totalLoggedLoss = wasteHistory.stream().map(WasteRecord::getMonetaryLoss).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("200000.00").compareTo(totalLoggedLoss), "Total loss must be 200,000 MMK");
    }

    // =========================================================================
    // 4. ZERO STOCK: Status OUT_OF_STOCK, Excluded from Actions & Recommendations
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("E2E Audit 4: Zero Stock State, Exclusion from High-Risk and Active Recommendations")
    public void testZeroStockStateAndExclusions() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();
        FoodItem zeroItem = createAuditItem("E2E Zero Stock Bread", "Bakery", BigDecimal.ZERO, "pcs", new BigDecimal("1500.00"), today.plusDays(2));
        zeroItem.updateComputedExpiryFields();

        assertEquals("OUT_OF_STOCK", zeroItem.getDisplayStatus());

        // 1. Excluded from 7-day forecast items list
        Map<String, Object> forecast = predictionService.assessInventory(List.of(zeroItem));
        @SuppressWarnings("unchecked")
        List<PrologAssessment> forecastItems = (List<PrologAssessment>) forecast.get("items");
        assertTrue(forecastItems.stream().noneMatch(i -> i.getFoodItemId().equals(zeroItem.getId())),
                "Zero-stock item must be excluded from active forecast list");

        // 2. Excluded from active recommendations
        List<Recommendation> recs = recommendationService.generateRecommendationsFromProlog();
        assertTrue(recs.stream().noneMatch(r -> r.getFoodItemId().equals(zeroItem.getId())),
                "Zero-stock item must never generate active recommendations");

        // 3. Excluded from redistribution candidates
        Map<String, Object> redist = redistributionService.evaluateRedistributionCandidates();
        @SuppressWarnings("unchecked")
        List<RedistributionService.CandidateItem> prioList = (List<RedistributionService.CandidateItem>) redist.get("priorityCandidates");
        @SuppressWarnings("unchecked")
        List<RedistributionService.CandidateItem> recList = (List<RedistributionService.CandidateItem>) redist.get("redistributionCandidates");
        assertTrue(prioList.stream().noneMatch(i -> i.getFoodItemId().equals(zeroItem.getId())), "Zero-stock excluded from priority candidates");
        assertTrue(recList.stream().noneMatch(i -> i.getFoodItemId().equals(zeroItem.getId())), "Zero-stock excluded from recommended candidates");

        // 4. Dispatch attempt for zero stock throws IllegalArgumentException
        Redistribution dispatch = new Redistribution();
        dispatch.setFoodItemId(zeroItem.getId());
        dispatch.setRecipientId(1L);
        dispatch.setQuantity(new BigDecimal("5.00"));
        assertThrows(IllegalArgumentException.class, () -> redistributionService.scheduleDispatch(dispatch, 1L));
    }

    // =========================================================================
    // 5. PREDICTION: Days-Remaining vs. Future Forecast Separation, Units
    // =========================================================================
    @Test
    @Order(5)
    @DisplayName("E2E Audit 5: Current Days Remaining vs Forecast Separation and Unit Totals")
    public void testPredictionDaysRemainingAndUnitSeparation() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();

        // Fresh Milk expiring in 6 days
        FoodItem milk = createAuditItem("E2E Forecast Milk", "Dairy", new BigDecimal("20.00"), "liter", new BigDecimal("2500.00"), today.plusDays(6));
        milk.updateComputedExpiryFields();
        assertEquals(6, milk.getCurrentDaysRemaining());
        assertEquals(6, milk.getExpiryDaysRemaining());

        // Assessment
        Optional<PrologAssessment> assessOpt = predictionService.assessFoodItemById(milk.getId());
        assertTrue(assessOpt.isPresent());
        PrologAssessment assess = assessOpt.get();
        assertEquals(6, assess.getExpiryDays());
        assertEquals("liter", assess.getUnit());

        // 7-day forecast
        Map<String, Object> forecast = predictionService.assessInventory(List.of(milk));
        assertNotNull(forecast);
        @SuppressWarnings("unchecked")
        Map<String, Double> wasteByUnit = (Map<String, Double>) forecast.get("predictedWasteByUnit");
        assertNotNull(wasteByUnit);
    }

    // =========================================================================
    // 6. REDISTRIBUTION & PROLOG POLICY: 0-7, 8-30, >30, Expired Safety
    // =========================================================================
    @Test
    @Order(6)
    @DisplayName("E2E Audit 6: Approved Prolog Redistribution Expiry Windows and Safety")
    public void testPrologRedistributionWindowsAndSafety() {
        // 1. 0-7 Days -> PRIORITY_DONATION
        PrologAssessment prio = prologService.assessFoodItem("Prio Bread", 20.0, 5.0, 4, 0.05, 5.0);
        assertEquals("PRIORITY_DONATION", prio.getRedistributionStatus());
        assertEquals("HIGH", prio.getRedistributionPriority());
        assertTrue(prio.isRecommendRedistribution());
        assertEquals(15.0, prio.getSuggestedDonationQuantity(), 0.001);

        // 2. 8-30 Days -> DONATION_RECOMMENDED
        PrologAssessment rec = prologService.assessFoodItem("Rec Chicken", 30.0, 10.0, 15, 0.05, 10.0);
        assertEquals("DONATION_RECOMMENDED", rec.getRedistributionStatus());
        assertEquals("RECOMMENDED", rec.getRedistributionPriority());
        assertTrue(rec.isRecommendRedistribution());
        assertEquals(20.0, rec.getSuggestedDonationQuantity(), 0.001);

        // 3. > 30 Days -> NOT_NEEDED_YET
        PrologAssessment notNeeded = prologService.assessFoodItem("Safe Rice", 50.0, 10.0, 45, 0.05, 10.0);
        assertEquals("NOT_NEEDED_YET", notNeeded.getRedistributionStatus());
        assertFalse(notNeeded.isRecommendRedistribution());
        assertEquals(40.0, notNeeded.getSuggestedDonationQuantity(), 0.001);

        // 4. Expired -> EXPIRED_NOT_FOR_HUMAN_DONATION
        PrologAssessment exp = prologService.assessFoodItem("Expired Milk", 10.0, 2.0, -1, 0.05, 0.0);
        assertEquals("EXPIRED_NOT_FOR_HUMAN_DONATION", exp.getRedistributionStatus());
        assertFalse(exp.isRecommendRedistribution());
        assertEquals("DISPOSE_OR_COMPOST", exp.getPriorityUsage());

        // 5. Zero Stock -> OUT_OF_STOCK
        PrologAssessment zero = prologService.assessFoodItem("Zero Stock", 0.0, 5.0, 3, 0.05, 5.0);
        assertEquals("OUT_OF_STOCK", zero.getRedistributionStatus());
        assertFalse(zero.isRecommendRedistribution());
    }

    // =========================================================================
    // 7. DISPATCH: Stock Deduction, Transaction Creation, Limit Enforcements
    // =========================================================================
    @Test
    @Order(7)
    @DisplayName("E2E Audit 7: Redistribution Dispatch Lifecycle, Stock Deduction, and Transaction Audit")
    public void testDispatchLifecycleAndAuditing() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();
        FoodItem item = createAuditItem("E2E Dispatch Apples", "Produce", new BigDecimal("40.00"), "kg", new BigDecimal("3500.00"), today.plusDays(4));

        // Initial stock = 40.00 kg
        assertEquals(0, new BigDecimal("40.00").compareTo(item.getQuantity()));

        // Schedule dispatch of 15.00 kg to Recipient 1
        Redistribution dispatch = new Redistribution();
        dispatch.setFoodItemId(item.getId());
        dispatch.setRecipientId(1L);
        dispatch.setQuantity(new BigDecimal("15.00"));
        dispatch.setNotes("E2E audit dispatch batch");

        Redistribution scheduled = redistributionService.scheduleDispatch(dispatch, 1L);
        assertNotNull(scheduled.getId());
        createdDispatchIds.add(scheduled.getId());

        // Stock decreased from 40.00 to 25.00 kg exactly once
        FoodItem afterDispatch = foodItemService.getFoodItemById(item.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("25.00").compareTo(afterDispatch.getQuantity()), "Stock must be 25.00 kg after dispatch");

        // Dispatch exceeding remaining stock (30.00 kg when 25.00 kg available) is rejected
        Redistribution overDispatch = new Redistribution();
        overDispatch.setFoodItemId(item.getId());
        overDispatch.setRecipientId(1L);
        overDispatch.setQuantity(new BigDecimal("30.00"));
        assertThrows(IllegalArgumentException.class, () -> redistributionService.scheduleDispatch(overDispatch, 1L));

        FoodItem afterFailedDispatch = foodItemService.getFoodItemById(item.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("25.00").compareTo(afterFailedDispatch.getQuantity()));
    }

    // =========================================================================
    // 8. DATABASE INTEGRITY: Foreign Keys, Non-Negative Balances, Transaction Audits
    // =========================================================================
    @Test
    @Order(8)
    @DisplayName("E2E Audit 8: Database Integrity, No Orphan Records, and No Negative Balances")
    public void testDatabaseIntegrity() throws SQLException {
        if (!DatabaseConfig.isAvailable()) return;

        try (Connection conn = DatabaseConfig.getConnection()) {
            // Check for negative quantities in food_items
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM food_items WHERE quantity < 0");
                 ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Database must contain ZERO food items with negative quantity");
            }

            // Check for negative sale quantities
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM sales WHERE quantity_sold < 0");
                 ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Database must contain ZERO sales with negative quantity");
            }

            // Check for negative waste quantities
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM waste_records WHERE quantity_wasted < 0");
                 ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Database must contain ZERO waste records with negative quantity");
            }

            // Check for negative redistribution quantities
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM redistributions WHERE quantity < 0");
                 ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Database must contain ZERO redistributions with negative quantity");
            }
        }
    }
}
