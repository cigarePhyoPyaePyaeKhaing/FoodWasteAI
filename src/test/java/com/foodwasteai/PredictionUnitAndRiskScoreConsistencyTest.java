package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.PredictionItem;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.prolog.PrologService;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.PredictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite verifying:
 * 1. AI Prediction Unit Integrity (liter, kg, pieces preserved across Java, API, and UI)
 * 2. Risk Score Single Source of Truth (authoritative SWI-Prolog riskScore synchronized across components)
 * 3. Expiry reasoning consistency and bounded predicted waste quantity.
 */
public class PredictionUnitAndRiskScoreConsistencyTest {

    private PrologService prologService;
    private PredictionService predictionService;
    private FoodItemService foodItemService;

    @BeforeEach
    public void setUp() {
        prologService = new PrologService();
        foodItemService = new FoodItemService();
        predictionService = new PredictionService();
    }

    @Test
    @DisplayName("1. Fresh Milk unit = liter -> prediction API & assessment preserves unit = liter")
    public void testFreshMilkUnitPreservation() {
        PrologAssessment assessment = prologService.assessFoodItem(
                "Fresh Milk", "liter", 8.0, 1.0, 1, 0.08, 2.0
        );

        assertNotNull(assessment);
        assertEquals("liter", assessment.getUnit(), "Assessment must preserve unit as liter");
        assertEquals("Fresh Milk", assessment.getFoodName());
        assertEquals("HIGH", assessment.getRiskLevel());
        assertEquals(85.0, assessment.getRiskScore(), 0.01, "Authoritative Prolog risk score for 24h expiry must be 85.0");
        assertTrue(assessment.getPredictedWasteQuantity() > 0, "Predicted waste must be positive");
        assertTrue(assessment.getPredictedWasteQuantity() <= 8.0, "Predicted waste must not exceed current stock of 8.0 liter");
    }

    @Test
    @DisplayName("2. Rice unit = kg -> renders kg and preserves unit")
    public void testRiceUnitPreservation() {
        PrologAssessment assessment = prologService.assessFoodItem(
                "Jasmine Rice", "kg", 10.0, 10.0, 10, 0.02, 5.0
        );

        assertNotNull(assessment);
        assertEquals("kg", assessment.getUnit());
        assertEquals("LOW", assessment.getRiskLevel());
        assertEquals(18.0, assessment.getRiskScore(), 0.01);
        assertEquals(0.0, assessment.getPredictedWasteQuantity(), 0.01, "Low risk item should have 0.0 predicted waste");
    }

    @Test
    @DisplayName("3. Eggs unit = pieces -> renders pieces and preserves unit")
    public void testEggsUnitPreservation() {
        PrologAssessment assessment = prologService.assessFoodItem(
                "Farm Eggs", "pieces", 20.0, 20.0, 3, 0.05, 20.0
        );

        assertNotNull(assessment);
        assertEquals("pieces", assessment.getUnit());
        assertEquals("MEDIUM", assessment.getRiskLevel());
        assertEquals(55.0, assessment.getRiskScore(), 0.01);
        assertTrue(assessment.getPredictedWasteQuantity() <= 20.0, "Predicted pieces cannot exceed stock");
    }

    @Test
    @DisplayName("4. Prolog risk score 85 -> riskScore and riskPercentage match identically (No 100% inflation)")
    public void testRiskScoreSynchronization() {
        PrologAssessment assessment = prologService.assessFoodItem(
                "Fresh Milk", "liter", 8.0, 1.0, 1, 0.08, 2.0
        );

        assertEquals("HIGH", assessment.getRiskLevel());
        assertEquals(85.0, assessment.getRiskScore(), 0.01);
        assertEquals(85.0, assessment.getRiskPercentage(), 0.01);
        assertNotEquals(100.0, assessment.getRiskScore(), "HIGH risk must NOT be inflated to 100%");
    }

    @Test
    @DisplayName("5. Expiry logic: Expiry in past (< 0 days) -> HIGH risk (95%) and DISPOSE_OR_COMPOST")
    public void testExpiredInPastLogic() {
        PrologAssessment assessment = prologService.assessFoodItem(
                "Expired Yogurt", "cups", 5.0, 2.0, -1, 0.10, 0.0
        );

        assertEquals("HIGH", assessment.getRiskLevel());
        assertEquals(95.0, assessment.getRiskScore(), 0.01);
        assertEquals("DISPOSE_OR_COMPOST", assessment.getPriorityUsage());
        assertTrue(assessment.getReason().toLowerCase().contains("passed expiration") || assessment.getReason().toLowerCase().contains("expired"),
                "Reason must indicate item has passed expiration date");
        assertEquals(5.0, assessment.getPredictedWasteQuantity(), 0.01, "Expired item has entire stock as waste");
    }

    @Test
    @DisplayName("6. Expiry logic: Same-day expiry (0 days) -> HIGH risk (85%) and IMMEDIATE_USE")
    public void testSameDayExpiryLogic() {
        PrologAssessment assessment = prologService.assessFoodItem(
                "Fresh Bread", "loaves", 10.0, 3.0, 0, 0.15, 5.0
        );

        assertEquals("HIGH", assessment.getRiskLevel());
        assertEquals(85.0, assessment.getRiskScore(), 0.01);
        assertEquals("IMMEDIATE_USE", assessment.getPriorityUsage());
        assertTrue(assessment.getReason().toLowerCase().contains("today") || assessment.getReason().toLowerCase().contains("24 hours"),
                "Reason must explicitly indicate same-day expiry");
    }

    @Test
    @DisplayName("7. Predicted waste quantity bounds: Never negative, never exceeds available stock")
    public void testPredictedQuantityBounds() {
        // High stock, low demand, 24h expiry
        PrologAssessment highStock = prologService.assessFoodItem(
                "Fresh Milk", "liter", 8.0, 1.0, 1, 0.05, 2.0
        );
        assertTrue(highStock.getPredictedWasteQuantity() >= 0.0, "Cannot be negative");
        assertTrue(highStock.getPredictedWasteQuantity() <= 8.0, "Cannot exceed current stock");

        // Zero stock item
        PrologAssessment zeroStock = prologService.assessFoodItem(
                "Empty Item", "kg", 0.0, 5.0, 1, 0.05, 5.0
        );
        assertEquals(0.0, zeroStock.getPredictedWasteQuantity(), 0.01);
    }

    @Test
    @DisplayName("8. End-to-end Inventory Assessment: Preserves unit and risk score for created food items")
    public void testEndToEndInventoryUnitAndRisk() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Fresh Milk E2E " + System.currentTimeMillis(), "Dairy", new BigDecimal("8.00"), "liter",
                        new BigDecimal("2000.00"), LocalDate.now().plusDays(1), new BigDecimal("2.00")), null
        );

        Optional<PrologAssessment> opt = predictionService.assessFoodItemById(item.getId());
        assertTrue(opt.isPresent(), "Assessment must be generated");

        PrologAssessment a = opt.get();
        assertEquals("liter", a.getUnit(), "Unit must be liter");
        assertEquals("HIGH", a.getRiskLevel());
        assertEquals(85.0, a.getRiskScore(), 0.01);
        assertEquals(a.getRiskScore(), a.getRiskPercentage(), "riskScore and riskPercentage must be identical");
        assertTrue(a.getPredictedWasteQuantity() <= 8.0);
    }

    @Test
    @DisplayName("9. PredictionItem Model: Synchronized getters for unit, riskScore, and predictedWasteQuantity")
    public void testPredictionItemModel() {
        PredictionItem pi = new PredictionItem();
        pi.setFoodItemName("Fresh Milk");
        pi.setUnit("liter");
        pi.setRiskScore(new BigDecimal("85.00"));
        pi.setPredictedWasteQuantity(new BigDecimal("7.40"));

        assertEquals("liter", pi.getUnit());
        assertEquals(new BigDecimal("85.00"), pi.getRiskScore());
        assertEquals(new BigDecimal("85.00"), pi.getRiskPercentage());
        assertEquals(new BigDecimal("7.40"), pi.getPredictedWasteQty());
        assertEquals(new BigDecimal("7.40"), pi.getPredictedWasteQuantity());
    }

    @Test
    @DisplayName("10. Multi-Unit Breakdown Formatting: Accurately formats single and mixed unit breakdowns without mixing units")
    public void testFormatUnitBreakdownString() {
        // Empty
        assertEquals("0.0", PredictionService.formatUnitBreakdownString(null));
        assertEquals("0.0", PredictionService.formatUnitBreakdownString(java.util.Collections.emptyMap()));

        // Single unit
        Map<String, Double> singleKg = new java.util.LinkedHashMap<>();
        singleKg.put("kg", 4.5);
        assertEquals("4.5 kg", PredictionService.formatUnitBreakdownString(singleKg));

        // Mixed units
        Map<String, Double> mixed = new java.util.LinkedHashMap<>();
        mixed.put("kg", 3.2);
        mixed.put("liter", 1.5);
        mixed.put("pieces", 6.0);
        String formatted = PredictionService.formatUnitBreakdownString(mixed);
        assertEquals("3.2 kg • 1.5 liter • 6 pieces", formatted);
    }

    @Test
    @DisplayName("11. Incompatible Unit Safety: Assesses items of different units without mathematically adding incompatible units into kg")
    public void testIncompatibleUnitSafety() throws SQLException {
        FoodItem milk = new FoodItem(101L, "Fresh Milk", "Dairy", new BigDecimal("5.00"), "liter",
                new BigDecimal("2000.00"), LocalDate.now().plusDays(1), new BigDecimal("1.00"));
        FoodItem beef = new FoodItem(102L, "Beef Steak", "Meat", new BigDecimal("3.00"), "kg",
                new BigDecimal("18000.00"), LocalDate.now().plusDays(1), new BigDecimal("0.50"));
        FoodItem eggs = new FoodItem(103L, "Eggs", "Dairy", new BigDecimal("10.00"), "pieces",
                new BigDecimal("300.00"), LocalDate.now().plusDays(1), new BigDecimal("2.00"));

        Map<String, Object> report = predictionService.assessInventory(java.util.List.of(milk, beef, eggs));
        assertNotNull(report);

        // Verification: report contains unitBreakdown and formattedTotalWaste
        assertTrue(report.containsKey("unitBreakdown"));
        assertTrue(report.containsKey("formattedTotalWaste"));
        assertTrue(report.containsKey("predictionTime"));

        @SuppressWarnings("unchecked")
        Map<String, Double> breakdown = (Map<String, Double>) report.get("unitBreakdown");
        assertNotNull(breakdown);

        // Verify each unit is partitioned and preserved
        Double kgWaste = breakdown.get("kg");
        Double literWaste = breakdown.get("liter");
        Double piecesWaste = breakdown.get("pieces");

        if (kgWaste != null && kgWaste > 0) {
            assertEquals(kgWaste, ((Number) report.get("expectedTotalWasteKg")).doubleValue(), 0.01,
                    "expectedTotalWasteKg should only track kg-based items, not liters or pieces");
        }

        String formattedTotal = (String) report.get("formattedTotalWaste");
        assertNotNull(formattedTotal);
        assertFalse(formattedTotal.isEmpty());
    }

    @Test
    @DisplayName("12. Strict Tomorrow Prediction: Selects ONLY active products expiring EXACTLY tomorrow (current_date + 1 day)")
    public void testStrictTomorrowExpirySelection() {
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        LocalDate tomorrow = today.plusDays(1);
        LocalDate pastExpiry = today.minusDays(1);
        LocalDate todayExpiry = today;
        LocalDate laterExpiry = today.plusDays(2);
        LocalDate evenLaterExpiry = today.plusDays(5);

        FoodItem cheese = new FoodItem(201L, "cheese", "Dairy", new BigDecimal("34.00"), "pcs",
                new BigDecimal("2000.00"), tomorrow, new BigDecimal("1.00")); // Expiring tomorrow -> INCLUDED!
        FoodItem tomato = new FoodItem(202L, "tomato", "Produce", new BigDecimal("23.00"), "kg",
                new BigDecimal("1500.00"), tomorrow, new BigDecimal("2.00")); // Expiring tomorrow -> INCLUDED!
        FoodItem pork = new FoodItem(203L, "pork", "Meat", new BigDecimal("12.00"), "kg",
                new BigDecimal("12000.00"), todayExpiry, new BigDecimal("2.00")); // Expiring today -> EXCLUDED!
        FoodItem sugar = new FoodItem(204L, "sugar", "Dry Goods", new BigDecimal("15.00"), "kg",
                new BigDecimal("3000.00"), laterExpiry, new BigDecimal("1.00")); // Expiring after tomorrow -> EXCLUDED!
        FoodItem milk = new FoodItem(205L, "milk", "Dairy", new BigDecimal("2.00"), "liter",
                new BigDecimal("2000.00"), evenLaterExpiry, new BigDecimal("1.00")); // Expiring after tomorrow -> EXCLUDED!
        FoodItem zeroStock = new FoodItem(206L, "Zero Salt", "Condiments", BigDecimal.ZERO, "kg",
                new BigDecimal("500.00"), tomorrow, new BigDecimal("1.00")); // Stock == 0 -> EXCLUDED!
        FoodItem oldMeat = new FoodItem(207L, "Old Chicken", "Meat", new BigDecimal("5.00"), "kg",
                new BigDecimal("8000.00"), pastExpiry, new BigDecimal("1.00")); // Expired -> EXCLUDED!

        List<FoodItem> items = List.of(cheese, tomato, pork, sugar, milk, zeroStock, oldMeat);
        Map<String, Object> tomorrowPred = predictionService.calculateTomorrowPrediction(items);

        assertNotNull(tomorrowPred);
        assertEquals(tomorrow.toString(), tomorrowPred.get("predictionDate"));
        assertEquals(tomorrow.toString(), tomorrowPred.get("nearestExpiryDate"));
        assertEquals(1L, ((Number) tomorrowPred.get("nearestExpiryDaysRemaining")).longValue());

        @SuppressWarnings("unchecked")
        List<PrologAssessment> selectedItems = (List<PrologAssessment>) tomorrowPred.get("items");
        assertNotNull(selectedItems);
        assertEquals(2, selectedItems.size(), "Only cheese and tomato expiring tomorrow should be selected");

        List<String> names = selectedItems.stream().map(a -> a.getFoodName().toLowerCase()).toList();
        assertTrue(names.contains("cheese"), "cheese must be included");
        assertTrue(names.contains("tomato"), "tomato must be included");
        assertFalse(names.contains("pork"), "Today-expiring pork must NOT be included in Tomorrow prediction");
        assertFalse(names.contains("sugar"), "Later-expiring sugar must NOT be included in Tomorrow prediction");
        assertFalse(names.contains("milk"), "Later-expiring milk must NOT be included in Tomorrow prediction");
        assertFalse(names.contains("zero salt"), "Zero stock salt must NOT be included");
        assertFalse(names.contains("old chicken"), "Past expired chicken must NOT be included");

        @SuppressWarnings("unchecked")
        Map<String, Double> unitBreakdown = (Map<String, Double>) tomorrowPred.get("unitBreakdown");
        assertNotNull(unitBreakdown);
        assertTrue(unitBreakdown.containsKey("pcs"), "Must contain pcs unit breakdown");
        assertTrue(unitBreakdown.containsKey("kg"), "Must contain kg unit breakdown");

        @SuppressWarnings("unchecked")
        List<String> quantities = (List<String>) tomorrowPred.get("quantities");
        assertNotNull(quantities);
        assertEquals(2, quantities.size(), "Must produce 2 distinct unit quantities (never combine incompatible units)");
    }

    @Test
    @DisplayName("13. Exclude Zero Stock and Past Expired Products from Tomorrow's Prediction")
    public void testExcludeZeroStockAndPastExpired() {
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        FoodItem zeroStock = new FoodItem(210L, "Zero Beef", "Meat", BigDecimal.ZERO, "kg",
                new BigDecimal("15000.00"), today.plusDays(1), new BigDecimal("1.00"));
        FoodItem negativeStock = new FoodItem(211L, "Negative Pork", "Meat", new BigDecimal("-2.00"), "kg",
                new BigDecimal("12000.00"), today.plusDays(1), new BigDecimal("1.00"));
        FoodItem expiredItem = new FoodItem(212L, "Old Chicken", "Meat", new BigDecimal("5.00"), "kg",
                new BigDecimal("8000.00"), today.minusDays(2), new BigDecimal("1.00"));

        Map<String, Object> tomorrowPred = predictionService.calculateTomorrowPrediction(List.of(zeroStock, negativeStock, expiredItem));
        assertNotNull(tomorrowPred);
        assertNull(tomorrowPred.get("nearestExpiryDate"), "Nearest expiry date must be null when no eligible items exist");
        assertEquals("0.0", tomorrowPred.get("formattedTotalWaste"));

        @SuppressWarnings("unchecked")
        List<PrologAssessment> selectedItems = (List<PrologAssessment>) tomorrowPred.get("items");
        assertTrue(selectedItems.isEmpty(), "No items should be selected if all are zero stock or expired");
    }

    @Test
    @DisplayName("14. Sum Multiple Products with Same Unit Safely: Sugar + Pork both using kg")
    public void testSumMultipleProductsSameUnit() {
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        LocalDate targetDate = today.plusDays(1);

        FoodItem sugar = new FoodItem(220L, "Sugar", "Dry Goods", new BigDecimal("15.00"), "kg",
                new BigDecimal("3000.00"), targetDate, new BigDecimal("1.00"));
        FoodItem pork = new FoodItem(221L, "Pork", "Meat", new BigDecimal("8.00"), "kg",
                new BigDecimal("12000.00"), targetDate, new BigDecimal("1.00"));

        Map<String, Object> tomorrowPred = predictionService.calculateTomorrowPrediction(List.of(sugar, pork));
        assertNotNull(tomorrowPred);

        @SuppressWarnings("unchecked")
        List<PrologAssessment> selected = (List<PrologAssessment>) tomorrowPred.get("items");
        assertEquals(2, selected.size());

        double sugarWaste = selected.get(0).getPredictedWasteQuantity();
        double porkWaste = selected.get(1).getPredictedWasteQuantity();
        double expectedSum = Math.round((sugarWaste + porkWaste) * 10.0) / 10.0;

        @SuppressWarnings("unchecked")
        Map<String, Double> breakdown = (Map<String, Double>) tomorrowPred.get("unitBreakdown");
        assertEquals(expectedSum, breakdown.get("kg"), 0.05, "Quantities of same unit must be safely summed");

        @SuppressWarnings("unchecked")
        List<String> quantities = (List<String>) tomorrowPred.get("quantities");
        assertEquals(1, quantities.size(), "Single unit should produce exactly 1 quantity line");
        assertTrue(quantities.get(0).contains("kg"));
    }

    @Test
    @DisplayName("15. Popup Item Breakdown Exactly Matches Tomorrow Card Unit Totals")
    public void testCardTotalMatchesPopupBreakdown() {
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        LocalDate targetDate = today.plusDays(1);

        FoodItem milk = new FoodItem(230L, "Milk", "Dairy", new BigDecimal("4.00"), "liter",
                new BigDecimal("2000.00"), targetDate, new BigDecimal("1.00"));
        FoodItem rice = new FoodItem(231L, "Rice", "Grains", new BigDecimal("10.00"), "kg",
                new BigDecimal("2500.00"), targetDate, new BigDecimal("1.00"));
        FoodItem eggs = new FoodItem(232L, "Eggs", "Dairy", new BigDecimal("12.00"), "pack",
                new BigDecimal("4000.00"), targetDate, new BigDecimal("1.00"));

        Map<String, Object> tomorrowPred = predictionService.calculateTomorrowPrediction(List.of(milk, rice, eggs));

        @SuppressWarnings("unchecked")
        List<PrologAssessment> items = (List<PrologAssessment>) tomorrowPred.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Double> breakdown = (Map<String, Double>) tomorrowPred.get("unitBreakdown");

        // Sum items by unit directly from the list of popup items
        Map<String, Double> popupSums = new java.util.LinkedHashMap<>();
        for (PrologAssessment item : items) {
            String u = item.getUnit();
            popupSums.put(u, popupSums.getOrDefault(u, 0.0) + item.getPredictedWasteQuantity());
        }

        // Verify popup sums match the card unit breakdown totals exactly
        for (Map.Entry<String, Double> entry : breakdown.entrySet()) {
            Double popupSum = popupSums.get(entry.getKey());
            assertNotNull(popupSum, "Popup items must contain unit " + entry.getKey());
            assertEquals(entry.getValue(), popupSum, 0.001, "Card total and popup breakdown must match 1:1");
        }
    }

    @Test
    @DisplayName("16. Full Inventory Assessment Report Includes Tomorrow Prediction Metadata")
    public void testFullInventoryAssessmentReportIncludesTomorrowPrediction() throws SQLException {
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        FoodItem item = new FoodItem(240L, "Test Beef", "Meat", new BigDecimal("5.00"), "kg",
                new BigDecimal("15000.00"), today.plusDays(2), new BigDecimal("1.00"));

        Map<String, Object> report = predictionService.assessInventory(List.of(item));
        assertNotNull(report);
        assertTrue(report.containsKey("tomorrowPrediction"), "Report must include tomorrowPrediction");
        assertTrue(report.containsKey("nearestExpiryDate"), "Report must include nearestExpiryDate");
        assertTrue(report.containsKey("nearestExpiryFormatted"), "Report must include nearestExpiryFormatted");
        assertTrue(report.containsKey("tomorrowQuantities"), "Report must include tomorrowQuantities");
        assertTrue(report.containsKey("tomorrowItems"), "Report must include tomorrowItems");
    }

    @Test
    @DisplayName("17. Two identical or case/whitespace variant 'cheese' items produce only one cheese in Tomorrow Prediction")
    public void testDuplicateProductsDeduplicatedInTomorrowPrediction() {
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        LocalDate targetDate = today.plusDays(1);

        // Two items with normalized name 'cheese' (one 'cheese', one ' CHEESE ')
        FoodItem cheese1 = new FoodItem(8L, "cheese", "Dairy", new BigDecimal("34.00"), "pcs",
                new BigDecimal("2000.00"), targetDate, new BigDecimal("1.00"));
        FoodItem cheese2 = new FoodItem(9L, " CHEESE ", "Dairy", new BigDecimal("34.00"), "pcs",
                new BigDecimal("2000.00"), targetDate, new BigDecimal("1.00"));

        Map<String, Object> tomorrowPred = predictionService.calculateTomorrowPrediction(List.of(cheese1, cheese2));
        assertNotNull(tomorrowPred);

        @SuppressWarnings("unchecked")
        List<PrologAssessment> items = (List<PrologAssessment>) tomorrowPred.get("items");
        assertNotNull(items);
        assertEquals(1, items.size(), "Duplicate product names must be deduplicated to exactly 1 product card");
        assertEquals("cheese", items.get(0).getFoodName().trim().toLowerCase());
        assertEquals("pcs", items.get(0).getUnit());
        assertEquals(34.0, items.get(0).getStock(), 0.01);

        // Verify unitBreakdown only includes the single canonical product's waste
        @SuppressWarnings("unchecked")
        Map<String, Double> breakdown = (Map<String, Double>) tomorrowPred.get("unitBreakdown");
        assertNotNull(breakdown);
        assertEquals(items.get(0).getPredictedWasteQuantity(), breakdown.get("pcs"), 0.05,
                "Card total must match single deduplicated product waste, not doubled");
    }

    @Test
    @DisplayName("18. Different products sharing same nearest expiry date all appear without collision")
    public void testDifferentProductsSharingNearestExpiryAllAppear() {
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        LocalDate targetDate = today.plusDays(1);

        FoodItem cheese = new FoodItem(8L, "Cheese", "Dairy", new BigDecimal("20.00"), "pcs",
                new BigDecimal("2000.00"), targetDate, new BigDecimal("1.00"));
        FoodItem butter = new FoodItem(10L, "Butter", "Dairy", new BigDecimal("10.00"), "kg",
                new BigDecimal("3000.00"), targetDate, new BigDecimal("1.00"));
        FoodItem bread = new FoodItem(11L, "Bread", "Bakery", new BigDecimal("5.00"), "loaf",
                new BigDecimal("1500.00"), targetDate.plusDays(2), new BigDecimal("1.00")); // later date

        Map<String, Object> tomorrowPred = predictionService.calculateTomorrowPrediction(List.of(cheese, butter, bread));
        assertNotNull(tomorrowPred);

        @SuppressWarnings("unchecked")
        List<PrologAssessment> items = (List<PrologAssessment>) tomorrowPred.get("items");
        assertNotNull(items);
        assertEquals(2, items.size(), "Only products from nearest expiry date (Cheese & Butter) must appear");

        List<String> productNames = items.stream().map(a -> a.getFoodName().trim().toLowerCase()).toList();
        assertTrue(productNames.contains("cheese"), "Must contain Cheese");
        assertTrue(productNames.contains("butter"), "Must contain Butter");
    }

    @Test
    @DisplayName("19. No fallback to future dates: When no products expire tomorrow, tomorrow prediction is empty")
    public void testNoFallbackWhenNoProductsExpireTomorrow() {
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();

        // Products expire in 2, 3, and 5 days, but NONE tomorrow
        FoodItem bread = new FoodItem(301L, "Bread", "Bakery", new BigDecimal("5.00"), "pcs",
                new BigDecimal("1000.00"), today.plusDays(2), new BigDecimal("1.00"));
        FoodItem milk = new FoodItem(302L, "Milk", "Dairy", new BigDecimal("4.00"), "liter",
                new BigDecimal("2500.00"), today.plusDays(3), new BigDecimal("1.00"));

        Map<String, Object> tomorrowPred = predictionService.calculateTomorrowPrediction(List.of(bread, milk));
        assertNotNull(tomorrowPred);

        @SuppressWarnings("unchecked")
        List<PrologAssessment> items = (List<PrologAssessment>) tomorrowPred.get("items");
        assertNotNull(items);
        assertTrue(items.isEmpty(), "Must NOT fall back to day 2 or day 3; must return 0 items for tomorrow");

        @SuppressWarnings("unchecked")
        List<String> quantities = (List<String>) tomorrowPred.get("quantities");
        assertNotNull(quantities);
        assertTrue(quantities.isEmpty(), "Quantities must be empty");
        assertEquals("0.0", tomorrowPred.get("formattedTotalWaste"));
        assertNull(tomorrowPred.get("nearestExpiryDate"), "nearestExpiryDate must be null when nothing expires tomorrow");
    }

    @Test
    @DisplayName("20. Pork and Sugar expiring today are excluded from tomorrow prediction but captured in todayActualWaste")
    public void testPorkAndSugarExpiringTodayExcludedFromTomorrowPrediction() throws Exception {
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        LocalDate tomorrow = today.plusDays(1);

        FoodItem pork = new FoodItem(16L, "pork", "Meat", new BigDecimal("12.00"), "kg",
                new BigDecimal("20000.00"), today, new BigDecimal("1.00"));
        FoodItem sugar = new FoodItem(12L, "sugar", "Baking", new BigDecimal("12.00"), "kg",
                new BigDecimal("1500.00"), today, new BigDecimal("1.00"));
        FoodItem bread = new FoodItem(14L, "bread", "Bakery", new BigDecimal("5.00"), "pcs",
                new BigDecimal("2500.00"), tomorrow, new BigDecimal("1.00"));
        FoodItem milk = new FoodItem(13L, "milk", "Dairy", new BigDecimal("15.00"), "liter",
                new BigDecimal("4000.00"), tomorrow, new BigDecimal("1.00"));
        FoodItem beef = new FoodItem(15L, "beef", "Meat", new BigDecimal("32.00"), "kg",
                new BigDecimal("25000.00"), today.plusDays(2), new BigDecimal("1.00"));

        List<FoodItem> inventory = List.of(pork, sugar, bread, milk, beef);

        // Tomorrow prediction test
        Map<String, Object> tomorrowPred = predictionService.calculateTomorrowPrediction(inventory);
        @SuppressWarnings("unchecked")
        List<PrologAssessment> tomorrowItems = (List<PrologAssessment>) tomorrowPred.get("items");
        assertNotNull(tomorrowItems);
        assertEquals(2, tomorrowItems.size(), "Only Bread and Milk should be in tomorrow prediction");

        List<String> names = tomorrowItems.stream().map(a -> a.getFoodName().trim().toLowerCase()).toList();
        assertTrue(names.contains("bread"), "Bread must be in tomorrow prediction");
        assertTrue(names.contains("milk"), "Milk must be in tomorrow prediction");
        assertFalse(names.contains("pork"), "Pork must NOT be in tomorrow prediction");
        assertFalse(names.contains("sugar"), "Sugar must NOT be in tomorrow prediction");
        assertFalse(names.contains("beef"), "Beef (future date) must NOT be in tomorrow prediction");

        // Today actual waste test
        Map<String, Object> todayActual = predictionService.calculateTodayActualWaste(inventory);
        assertNotNull(todayActual);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actualItems = (List<Map<String, Object>>) todayActual.get("items");
        assertEquals(2, actualItems.size(), "Today actual waste should have 2 items (pork, sugar)");

        // Check monetary loss: 12 * 20000 + 12 * 1500 = 240000 + 18000 = 258000 MMK
        double loss = (Double) todayActual.get("totalLoss");
        assertEquals(258000.0, loss, 0.01, "Total financial loss must be 258,000 MMK");

        // Check carbon: 24 kg * 2.5 = 60.0 kg CO2e
        double carbon = (Double) todayActual.get("carbonKg");
        assertEquals(60.0, carbon, 0.01, "Carbon impact must be 60.0 kg CO2e");

        // AssessInventory report test
        Map<String, Object> report = predictionService.assessInventory(inventory);
        @SuppressWarnings("unchecked")
        List<PrologAssessment> reportTomorrowItems = (List<PrologAssessment>) report.get("tomorrowItems");
        assertEquals(2, reportTomorrowItems.size(), "Tomorrow prediction items must only have tomorrow items (bread, milk)");
        List<String> reportTomorrowNames = reportTomorrowItems.stream().map(a -> a.getFoodName().trim().toLowerCase()).toList();
        assertTrue(reportTomorrowNames.contains("bread"), "Report tomorrow items must contain bread");
        assertTrue(reportTomorrowNames.contains("milk"), "Report tomorrow items must contain milk");
        assertFalse(reportTomorrowNames.contains("beef"), "Report tomorrow items must not contain beef");
        assertFalse(reportTomorrowNames.contains("pork"), "Report tomorrow items must not contain pork");
        assertFalse(reportTomorrowNames.contains("sugar"), "Report tomorrow items must not contain sugar");

        // Verify active items in report also strictly exclude today's actual waste (pork, sugar)
        @SuppressWarnings("unchecked")
        List<PrologAssessment> reportItems = (List<PrologAssessment>) report.get("items");
        List<String> reportItemNames = reportItems.stream().map(a -> a.getFoodName().trim().toLowerCase()).toList();
        assertFalse(reportItemNames.contains("pork"), "Report items must not contain pork (expires today)");
        assertFalse(reportItemNames.contains("sugar"), "Report items must not contain sugar (expires today)");
    }

    @Test
    @DisplayName("21. Single Date Rule: Correct classification across Expired, Today, Tomorrow, and Future")
    public void testSingleDateRuleClassification() {
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();

        LocalDate pastDate = today.minusDays(2);
        LocalDate todayDate = today;
        LocalDate tomorrowDate = today.plusDays(1);
        LocalDate futureDate = today.plusDays(5);

        // State assertions
        assertEquals(com.foodwasteai.util.ExpiryStatusResolver.ExpiryState.EXPIRED,
                com.foodwasteai.util.ExpiryStatusResolver.resolveState(pastDate, today));
        assertEquals(com.foodwasteai.util.ExpiryStatusResolver.ExpiryState.SAME_DAY_EXPIRY,
                com.foodwasteai.util.ExpiryStatusResolver.resolveState(todayDate, today));
        assertEquals(com.foodwasteai.util.ExpiryStatusResolver.ExpiryState.NEAR_EXPIRY,
                com.foodwasteai.util.ExpiryStatusResolver.resolveState(tomorrowDate, today));
        assertEquals(com.foodwasteai.util.ExpiryStatusResolver.ExpiryState.SAFE,
                com.foodwasteai.util.ExpiryStatusResolver.resolveState(futureDate, today));

        // Helper assertions
        assertTrue(com.foodwasteai.util.ExpiryStatusResolver.isExpiresToday(todayDate, today));
        assertFalse(com.foodwasteai.util.ExpiryStatusResolver.isExpiresToday(tomorrowDate, today));
        assertTrue(com.foodwasteai.util.ExpiryStatusResolver.isTomorrowCandidate(tomorrowDate, today));
        assertFalse(com.foodwasteai.util.ExpiryStatusResolver.isTomorrowCandidate(futureDate, today));
    }

    @Test
    @DisplayName("22. Zero and negative quantity items are excluded from both tomorrow prediction and today waste")
    public void testZeroQuantityExcludedFromPredictionAndWaste() {
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        LocalDate tomorrow = today.plusDays(1);

        FoodItem zeroToday = new FoodItem(401L, "Zero Today", "Produce", BigDecimal.ZERO, "kg",
                new BigDecimal("1000.00"), today, new BigDecimal("1.00"));
        FoodItem zeroTomorrow = new FoodItem(402L, "Zero Tomorrow", "Produce", BigDecimal.ZERO, "pcs",
                new BigDecimal("1000.00"), tomorrow, new BigDecimal("1.00"));

        Map<String, Object> tomorrowPred = predictionService.calculateTomorrowPrediction(List.of(zeroToday, zeroTomorrow));
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) tomorrowPred.get("items");
        assertTrue(items.isEmpty(), "Zero quantity items must be excluded from tomorrow prediction");

        Map<String, Object> todayActual = predictionService.calculateTodayActualWaste(List.of(zeroToday, zeroTomorrow));
        @SuppressWarnings("unchecked")
        List<?> wasteItems = (List<?>) todayActual.get("items");
        assertTrue(wasteItems.isEmpty(), "Zero quantity items must be excluded from today actual waste");
    }
}

