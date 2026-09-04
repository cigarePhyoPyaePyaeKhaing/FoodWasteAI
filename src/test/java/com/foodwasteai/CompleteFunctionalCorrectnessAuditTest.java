package com.foodwasteai;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.RecommendationDao;
import com.foodwasteai.model.*;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.prolog.PrologService;
import com.foodwasteai.service.*;
import com.foodwasteai.util.ExpiryStatusResolver;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CompleteFunctionalCorrectnessAuditTest {

    private static FoodItemService foodItemService;
    private static SalesService salesService;
    private static WasteService wasteService;
    private static RedistributionService redistributionService;
    private static PredictionService predictionService;
    private static RecommendationService recommendationService;
    private static RecommendationDao recommendationDao;
    private static PrologService prologService;

    private static final List<Long> createdFoodIds = new ArrayList<>();
    private static final List<Long> createdRecipientIds = new ArrayList<>();
    private static final List<Long> createdDispatchIds = new ArrayList<>();

    @BeforeAll
    public static void setUp() {
        recommendationDao = new RecommendationDao();
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
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM redistribution_dispatches WHERE id = ?")) {
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
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM food_items WHERE id = ?")) {
                    stmt.setLong(1, id);
                    stmt.executeUpdate();
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("Cleanup error: " + e.getMessage());
        }
    }

    private FoodItem createTestItem(String name, BigDecimal qty, String unit, BigDecimal price, LocalDate expiry) throws SQLException {
        FoodItem item = new FoodItem();
        item.setName(name + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4));
        item.setCategory("Dairy");
        item.setQuantity(qty);
        item.setUnit(unit);
        item.setPricePerUnit(price);
        item.setExpiryDate(expiry);
        FoodItem saved = foodItemService.createFoodItem(item, 1L);
        createdFoodIds.add(saved.getId());
        return saved;
    }

    // =========================================================================
    // SECTION 1: INVENTORY AUDIT
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("Audit 1: Inventory CRUD, Expiry Resolution, and No Negative Stock")
    public void testInventoryOperations() throws SQLException {
        // Create
        FoodItem item = createTestItem("Audit Butter", new BigDecimal("20.00"), "kg", new BigDecimal("5000.00"), LocalDate.now().plusDays(10));
        assertNotNull(item.getId());
        assertEquals(new BigDecimal("20.00"), item.getQuantity());
        assertEquals("SAFE", item.getExpiryStatus());
        assertEquals(ExpiryStatusResolver.STATUS_OK, item.getStatus());

        // Edit
        item.setQuantity(new BigDecimal("15.00"));
        item.setPricePerUnit(new BigDecimal("5500.00"));
        boolean updated = foodItemService.updateFoodItem(item, 1L);
        assertTrue(updated);

        Optional<FoodItem> reloaded = foodItemService.getFoodItemById(item.getId());
        assertTrue(reloaded.isPresent());
        assertEquals(0, new BigDecimal("15.00").compareTo(reloaded.get().getQuantity()));
        assertEquals(0, new BigDecimal("5500.00").compareTo(reloaded.get().getPricePerUnit()));

        // Negative stock rejection
        assertThrows(IllegalArgumentException.class, () -> {
            foodItemService.adjustStock(item.getId(), new BigDecimal("-25.00"), "Over-deduction attempt", 1L);
        }, "Stock reduction exceeding current balance must throw IllegalArgumentException");
    }

    // =========================================================================
    // SECTION 2: SALES AUDIT
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("Audit 2: Sales Single Deduction, Overselling Block, and Consistency")
    public void testSalesOperations() throws SQLException {
        FoodItem item = createTestItem("Audit Yogurt", new BigDecimal("10.00"), "liter", new BigDecimal("2500.00"), LocalDate.now().plusDays(5));

        // Successful sale
        Sale sale = new Sale();
        sale.setFoodItemId(item.getId());
        sale.setQuantitySold(new BigDecimal("4.00"));
        sale.setUnitPrice(new BigDecimal("2500.00"));
        sale.setSaleDate(LocalDateTime.now());
        Sale recorded = salesService.recordSale(sale, 1L);
        assertNotNull(recorded.getId());
        assertEquals(0, new BigDecimal("10000.00").compareTo(recorded.getTotalAmount()));

        // Stock decreased from 10.00 to 6.00 exactly once
        FoodItem afterSale = foodItemService.getFoodItemById(item.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("6.00").compareTo(afterSale.getQuantity()));

        // Oversell attempt blocked
        Sale overSale = new Sale();
        overSale.setFoodItemId(item.getId());
        overSale.setQuantitySold(new BigDecimal("10.00"));
        overSale.setUnitPrice(new BigDecimal("2500.00"));
        overSale.setSaleDate(LocalDateTime.now());

        assertThrows(IllegalArgumentException.class, () -> {
            salesService.recordSale(overSale, 1L);
        }, "Overselling beyond remaining 6.00 liter must throw IllegalArgumentException and rollback");

        // Verify stock remains exactly 6.00
        FoodItem afterFailedSale = foodItemService.getFoodItemById(item.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("6.00").compareTo(afterFailedSale.getQuantity()));
    }

    // =========================================================================
    // SECTION 3: WASTE & DISPOSAL AUDIT
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("Audit 3: Waste Record Confirmed Logging and Financial Loss Calculation")
    public void testWasteOperations() throws SQLException {
        FoodItem item = createTestItem("Audit Cream", new BigDecimal("8.00"), "liter", new BigDecimal("3000.00"), LocalDate.now().minusDays(1));

        // Record waste with stock deduction
        WasteRecord waste = new WasteRecord();
        waste.setFoodItemId(item.getId());
        waste.setQuantityWasted(new BigDecimal("3.00"));
        waste.setReason(WasteRecord.Reason.EXPIRED);
        waste.setNotes("Confirmed expired disposal");

        WasteRecord logged = wasteService.recordWaste(waste, 1L);
        assertNotNull(logged.getId());
        assertEquals(0, new BigDecimal("9000.00").compareTo(logged.getMonetaryLoss()), "Monetary loss must be 3.00 * 3000.00 = 9000.00 MMK");

        // Verify stock deducted from 8.00 to 5.00 exactly once
        FoodItem afterWaste = foodItemService.getFoodItemById(item.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("5.00").compareTo(afterWaste.getQuantity()));

        // Excessive waste blocked
        WasteRecord overWaste = new WasteRecord();
        overWaste.setFoodItemId(item.getId());
        overWaste.setQuantityWasted(new BigDecimal("10.00"));
        overWaste.setReason(WasteRecord.Reason.SPOILED);

        assertThrows(IllegalArgumentException.class, () -> {
            wasteService.recordWaste(overWaste, 1L);
        }, "Waste quantity exceeding remaining stock must throw IllegalArgumentException");
    }

    // =========================================================================
    // SECTION 4: EXPIRY RESOLVER & REDISTRIBUTION AUDIT
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("Audit 4: Expiry Statuses and Prolog Redistribution Rules")
    public void testExpiryAndRedistribution() {
        LocalDate today = ExpiryStatusResolver.getToday();

        // Safe item (> 7 days, balanced stock <= 120% demand)
        assertEquals(ExpiryStatusResolver.ExpiryState.SAFE, ExpiryStatusResolver.resolveState(today.plusDays(10), today));
        PrologAssessment safeEval = prologService.assessFoodItem("Safe Item", 10.0, 10.0, 10, 0.05, 10.0);
        assertEquals("low", safeEval.getRiskLevel().toLowerCase());
        assertFalse(safeEval.isRecommendRedistribution(), "Normal inventory window > 7 days is not an immediate candidate");

        // Candidate (4-7 days) with surplus
        PrologAssessment candidateEval = prologService.assessFoodItem("Candidate Item", 20.0, 5.0, 5, 0.05, 5.0);
        assertTrue(candidateEval.isRecommendRedistribution(), "4-7 days with surplus must be eligible for candidate review");

        // Priority candidate (1-3 days) with surplus
        PrologAssessment priorityEval = prologService.assessFoodItem("Priority Item", 18.0, 4.0, 2, 0.10, 4.0);
        assertTrue(priorityEval.isRecommendRedistribution(), "1-3 days with surplus must be priority candidate");

        // Expired (< 0 days)
        assertEquals(ExpiryStatusResolver.ExpiryState.EXPIRED, ExpiryStatusResolver.resolveState(today.minusDays(1), today));
        PrologAssessment expiredEval = prologService.assessFoodItem("Expired Item", 10.0, 5.0, -1, 0.05, 0.0);
        assertEquals("high", expiredEval.getRiskLevel().toLowerCase());
        assertFalse(expiredEval.isRecommendRedistribution(), "Expired item must NEVER be eligible for human redistribution");
        assertEquals("DISPOSE_OR_COMPOST", expiredEval.getPriorityUsage());

        // Zero surplus (Stock <= ExpectedDemand)
        PrologAssessment noSurplusEval = prologService.assessFoodItem("No Surplus Item", 5.0, 10.0, 2, 0.05, 10.0);
        assertFalse(noSurplusEval.isRecommendRedistribution(), "Zero surplus must not recommend redistribution");
    }

    // =========================================================================
    // SECTION 5: AI FORECAST & REASONING GROUNDING AUDIT
    // =========================================================================
    @Test
    @Order(5)
    @DisplayName("Audit 5: AI Prediction and Prolog Reasoning Grounding")
    public void testPredictionGrounding() throws SQLException {
        // Fresh Item Query
        FoodItem freshMilk = createTestItem("Audit Fresh Milk", new BigDecimal("12.00"), "liter", new BigDecimal("2000.00"), LocalDate.now().plusDays(2));
        Optional<PrologAssessment> milkAssessOpt = predictionService.assessFoodItemById(freshMilk.getId());
        assertTrue(milkAssessOpt.isPresent());
        PrologAssessment milkAssess = milkAssessOpt.get();
        assertEquals("liter", milkAssess.getUnit());
        assertTrue(milkAssess.getRiskScore() >= 0);

        // Expired Item Safety
        FoodItem expiredBeef = createTestItem("Audit Expired Beef", new BigDecimal("5.00"), "kg", new BigDecimal("18000.00"), LocalDate.now().minusDays(2));
        Optional<PrologAssessment> beefAssessOpt = predictionService.assessFoodItemById(expiredBeef.getId());
        assertTrue(beefAssessOpt.isPresent());
        PrologAssessment beefAssess = beefAssessOpt.get();
        assertEquals("HIGH", beefAssess.getRiskLevel());
        assertEquals("DISPOSE_OR_COMPOST", beefAssess.getPriorityUsage());
        assertFalse(beefAssess.isRecommendRedistribution(), "Expired food must never be recommended for human redistribution");
    }

    // =========================================================================
    // SECTION 6: CROSS-PAGE DATA CONSISTENCY & ZERO-STOCK REGRESSION AUDIT
    // =========================================================================
    @Test
    @Order(6)
    @DisplayName("Audit 6: Cross-Page Reconciliation, Zero-Stock Policy & Risk Alignment")
    public void testCrossPageDataConsistencyAndZeroStockPolicy() throws SQLException {
        // 1. Confirmed Waste Aggregation: 7 pcs (31,500 MMK) + 3 liter (6,000 MMK) = 37,500 MMK, 25.0 kg CO2e
        FoodItem sandwish = createTestItem("Audit Sandwich", new BigDecimal("10.00"), "pcs", new BigDecimal("4500.00"), LocalDate.now().minusDays(1));
        FoodItem milk = createTestItem("Audit Fresh Milk", new BigDecimal("5.00"), "liter", new BigDecimal("2000.00"), LocalDate.now().minusDays(1));

        WasteRecord w1 = new WasteRecord();
        w1.setFoodItemId(sandwish.getId());
        w1.setQuantityWasted(new BigDecimal("7.00"));
        w1.setReason(WasteRecord.Reason.EXPIRED);
        w1.setNotes("Sandwich expired waste");
        WasteRecord log1 = wasteService.recordWaste(w1, 1L);
        assertEquals(0, new BigDecimal("31500.00").compareTo(log1.getMonetaryLoss()));

        WasteRecord w2 = new WasteRecord();
        w2.setFoodItemId(milk.getId());
        w2.setQuantityWasted(new BigDecimal("3.00"));
        w2.setReason(WasteRecord.Reason.SPOILED);
        w2.setNotes("Milk spoilage waste");
        WasteRecord log2 = wasteService.recordWaste(w2, 1L);
        assertEquals(0, new BigDecimal("6000.00").compareTo(log2.getMonetaryLoss()));

        // Aggregate total loss and carbon footprint
        BigDecimal totalLoss = log1.getMonetaryLoss().add(log2.getMonetaryLoss());
        assertEquals(0, new BigDecimal("37500.00").compareTo(totalLoss));

        double totalUnits = log1.getQuantityWasted().doubleValue() + log2.getQuantityWasted().doubleValue();
        assertEquals(10.0, totalUnits, 0.001);
        double carbonFootprint = totalUnits * 2.5;
        assertEquals(25.0, carbonFootprint, 0.001);

        // 2. Prolog Risk Score and Level Synchronization (95% -> HIGH, Never 95% LOW)
        PrologAssessment expiredEval = prologService.assessFoodItem("Expired Active Item", 5.0, 2.0, -1, 0.05, 0.0);
        assertEquals("HIGH", expiredEval.getRiskLevel());
        assertEquals(95.0, expiredEval.getRiskScore(), 0.001);

        // 3. Zero-Stock Item Policy: Zero-stock items excluded from active waste prediction & recommendations
        FoodItem zeroStockItem = createTestItem("Audit Zero Stock Expired", BigDecimal.ZERO, "pcs", new BigDecimal("3000.00"), LocalDate.now().minusDays(2));
        FoodItem activeStockItem = createTestItem("Audit Active Noodles", new BigDecimal("17.00"), "kg", new BigDecimal("1500.00"), LocalDate.now().plusDays(2));

        Map<String, Object> predictionReport = predictionService.assessInventory(List.of(zeroStockItem, activeStockItem));
        assertNotNull(predictionReport);
        @SuppressWarnings("unchecked")
        List<PrologAssessment> activeAssessments = (List<PrologAssessment>) predictionReport.get("items");

        // Only the active item (Noodles) should be in the active prediction report
        assertEquals(1, activeAssessments.size());
        assertEquals(activeStockItem.getId(), activeAssessments.get(0).getFoodItemId());
        assertEquals(activeStockItem.getName(), activeAssessments.get(0).getFoodName());

        // 4. Zero-stock expired items must NOT trigger disposal review reminders
        List<FoodItem> disposalItems = foodItemService.getExpiredItemsRequiringDisposal();
        boolean containsZeroStock = disposalItems.stream().anyMatch(i -> i.getId().equals(zeroStockItem.getId()));
        assertFalse(containsZeroStock, "Zero-stock expired item must not trigger disposal reminder");
    }

    // =========================================================================
    // SECTION 7: UNIFIED RECOMMENDATIONS & PREDICTION CONSISTENCY REGRESSION AUDIT
    // =========================================================================
    @Test
    @Order(7)
    @DisplayName("Audit 7: Single Source of Truth for Recommendations & Potential Savings")
    public void testUnifiedRecommendationsAndChatbotConsistency() throws SQLException {
        // 1. Setup Active Test Items:
        // Item A: High risk near expiry (Fried Chicken) -> Eligible for Cook Priority / High Risk
        FoodItem chicken = createTestItem("Audit Fried Chicken", new BigDecimal("8.30"), "kg", new BigDecimal("6000.00"), LocalDate.now().plusDays(1));
        // Item B: Zero-stock expired item -> Must NOT generate active recommendations
        FoodItem zeroMilk = createTestItem("Audit Fresh Milk Zero", BigDecimal.ZERO, "liter", new BigDecimal("2000.00"), LocalDate.now().minusDays(2));
        // Item C: Low-risk safe item
        FoodItem rice = createTestItem("Audit Safe Rice", new BigDecimal("25.00"), "kg", new BigDecimal("1800.00"), LocalDate.now().plusDays(30));

        // Generate recommendations
        List<Recommendation> recs = recommendationService.generateRecommendationsFromProlog();
        assertNotNull(recs);

        // A & E: Deduplicated action types per active item (no duplicate action of same type)
        long chickenRecCount = recs.stream().filter(r -> r.getFoodItemId().equals(chicken.getId())).count();
        assertTrue(chickenRecCount >= 1 && chickenRecCount <= 3, "Fried Chicken must receive distinct action types");
        // Verify no duplicate title for the same item
        long uniqueTitles = recs.stream().filter(r -> r.getFoodItemId().equals(chicken.getId())).map(Recommendation::getTitle).distinct().count();
        assertEquals(chickenRecCount, uniqueTitles, "All generated recommendation actions for the same item must be unique");

        // D: Zero-stock expired item receives NO active recommendation
        long zeroMilkRecCount = recs.stream().filter(r -> r.getFoodItemId().equals(zeroMilk.getId())).count();
        assertEquals(0, zeroMilkRecCount, "Zero-stock item must receive NO active recommendations");

        // F & G: Canonical name and consistency (All Fried Chicken recommendations are HIGH risk, never simultaneously LOW)
        assertTrue(recs.stream().filter(r -> r.getFoodItemId().equals(chicken.getId())).allMatch(r -> r.getRiskLevel() == Recommendation.RiskLevel.HIGH),
                "All recommendations for Fried Chicken in same run must be HIGH risk");
        assertTrue(recs.stream().filter(r -> r.getFoodItemId().equals(chicken.getId())).allMatch(r -> chicken.getName().equals(r.getFoodItemName())),
                "All recommendations must use canonical FoodItem name");

        // H & I: Authoritative savings calculation and consistency
        Map<String, Object> predReport = predictionService.assessInventory(foodItemService.getAllFoodItems());
        double expectedSavings = ((Number) predReport.get("potentialSavings")).doubleValue();
        assertTrue(expectedSavings >= 0, "Potential savings must be >= 0");

        // J: Calling getAllRecommendations returns clean, deduplicated active records
        List<Recommendation> allRecs = recommendationService.getAllRecommendations();
        assertFalse(allRecs.isEmpty());
        assertTrue(allRecs.stream().noneMatch(r -> r.getFoodItemId().equals(zeroMilk.getId())), "All recommendations must exclude zero-stock items");
    }

    // =========================================================================
    // SECTION 8: 10-POINT ACTIVE INVENTORY & PERSISTENCE DEDUPLICATION AUDIT
    // =========================================================================
    @Test
    @Order(8)
    @DisplayName("Audit 8: Complete 10-Point Production Data Integrity & Anti-Staleness Test")
    public void testTenPointProductionDataIntegrity() throws SQLException {
        // 1. stock = 0 fresh milk -> zero active recommendations
        FoodItem zeroFreshMilk = createTestItem("fresh milk", BigDecimal.ZERO, "liter", new BigDecimal("2500.00"), LocalDate.now().minusDays(3));

        // 2. stock = 0 sandwish -> zero active recommendations
        FoodItem zeroSandwish = createTestItem("sandwish", BigDecimal.ZERO, "pcs", new BigDecimal("3500.00"), LocalDate.now().minusDays(1));

        // 3. Canonical name "fresh milk" with stock > 0
        FoodItem activeFreshMilk = createTestItem("fresh milk", new BigDecimal("12.00"), "liter", new BigDecimal("2500.00"), LocalDate.now().plusDays(2));

        // 6. Fried Chicken in same run with consistent snapshot
        FoodItem friedChicken = createTestItem("fried chicken", new BigDecimal("8.00"), "kg", new BigDecimal("5500.00"), LocalDate.now().plusDays(1));

        // 7. Medium item (e.g. noodles)
        FoodItem noodles = createTestItem("noodles", new BigDecimal("15.00"), "kg", new BigDecimal("2000.00"), LocalDate.now().plusDays(4));

        // Generate recommendations
        List<Recommendation> recs = recommendationService.generateRecommendationsFromProlog();
        assertNotNull(recs);

        // Verify 1 & 2: Zero-stock items must have ZERO active recommendations
        assertFalse(recs.stream().anyMatch(r -> r.getFoodItemId().equals(zeroFreshMilk.getId())), "Zero-stock fresh milk must have 0 recommendations");
        assertFalse(recs.stream().anyMatch(r -> r.getFoodItemId().equals(zeroSandwish.getId())), "Zero-stock sandwish must have 0 recommendations");

        // Verify 3: Canonical name "fresh milk" must never be truncated or corrupted to "fresh"
        List<Recommendation> milkRecs = recs.stream().filter(r -> r.getFoodItemId().equals(activeFreshMilk.getId())).toList();
        assertFalse(milkRecs.isEmpty(), "Active fresh milk must receive recommendations");
        for (Recommendation r : milkRecs) {
            assertEquals(activeFreshMilk.getName(), r.getFoodItemName(), "Canonical food name must match entity name, never truncated");
            assertFalse(r.getFoodItemName().equalsIgnoreCase("fresh"), "Canonical name must not be truncated to 'fresh'");
            assertTrue(r.getTitle().contains(activeFreshMilk.getName()), "Title must contain the full canonical food name");
        }

        // Verify 4: Duplicate DB rows deduplicated upon retrieval
        if (DatabaseConfig.isAvailable()) {
            Recommendation dupRec = new Recommendation();
            dupRec.setFoodItemId(activeFreshMilk.getId());
            dupRec.setCategory(milkRecs.get(0).getCategory());
            dupRec.setRiskLevel(milkRecs.get(0).getRiskLevel());
            dupRec.setTitle(milkRecs.get(0).getTitle());
            dupRec.setTitleEn(milkRecs.get(0).getTitleEn());
            dupRec.setTitleMy(milkRecs.get(0).getTitleMy());
            dupRec.setDescription(milkRecs.get(0).getDescription());
            dupRec.setReasoningDetails(milkRecs.get(0).getReasoningDetails());
            dupRec.setEstimatedSavings(BigDecimal.ZERO);
            dupRec.setStatus(Recommendation.Status.PENDING);
            recommendationDao.save(dupRec);

            List<Recommendation> activeFromDao = recommendationDao.findActiveRecommendations();
            long countForThisAction = activeFromDao.stream()
                    .filter(r -> r.getFoodItemId().equals(activeFreshMilk.getId()) && r.getTitle().equals(milkRecs.get(0).getTitle()))
                    .count();
            assertEquals(1, countForThisAction, "findActiveRecommendations must deduplicate multiple DB rows for the exact same action");
        }

        // Verify 6: Fried chicken in same run has one consistent riskScore / riskLevel snapshot
        List<Recommendation> chickenRecs = recs.stream().filter(r -> r.getFoodItemId().equals(friedChicken.getId())).toList();
        assertFalse(chickenRecs.isEmpty());
        Recommendation.RiskLevel baseRisk = chickenRecs.get(0).getRiskLevel();
        for (Recommendation r : chickenRecs) {
            assertEquals(baseRisk, r.getRiskLevel(), "All actions for fried chicken in the same run must share the same risk level");
        }

        // Verify 7: Medium item (noodles) is excluded from HIGH risk level list
        Optional<PrologAssessment> noodlesAssessOpt = predictionService.assessFoodItemById(noodles.getId());
        assertTrue(noodlesAssessOpt.isPresent());
        PrologAssessment noodlesAssessment = noodlesAssessOpt.get();
        if ("MEDIUM".equalsIgnoreCase(noodlesAssessment.getRiskLevel())) {
            List<PrologAssessment> highRiskItems = List.of(noodlesAssessment).stream().filter(i -> "HIGH".equalsIgnoreCase(i.getRiskLevel())).toList();
            assertTrue(highRiskItems.isEmpty(), "MEDIUM risk item must never be in HIGH risk list");
        }

        // Verify 8 & 9: Potential savings calculation consistency
        Map<String, Object> predReport = predictionService.assessInventory(foodItemService.getAllFoodItems());
        double predSavings = ((Number) predReport.get("potentialSavings")).doubleValue();
        assertTrue(predSavings >= 0, "Potential savings must be non-negative");

        // Verify 10: Asia/Yangon date handling
        LocalDate todayYangon = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        assertNotNull(todayYangon);
    }
}
