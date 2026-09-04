package com.foodwasteai;

import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.service.PredictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class PrologReasoningTest {

    private PredictionService predictionService;

    @BeforeEach
    public void setUp() {
        predictionService = new PredictionService();
    }

    @Test
    @DisplayName("User Example 1: Fresh Milk expires tomorrow (1 day) -> Risk = HIGH, prioritize expiry over quantity")
    public void testFreshMilkExpiresTomorrowHighRisk() {
        // Fresh Milk: Quantity 40kg, Demand 24kg, Expiry 1 day (tomorrow), WasteRate 0.08, CurrentProd 26kg
        PrologAssessment assessment = predictionService.assessFoodItem(
                "Fresh Milk", 40.0, 24.0, 1, 0.08, 26.0
        );

        assertNotNull(assessment);
        assertEquals("Fresh Milk", assessment.getItem());
        assertEquals("Fresh Milk", assessment.getFoodName());
        assertEquals("HIGH", assessment.getRisk());
        assertEquals("HIGH", assessment.getRiskLevel());
        assertEquals(85.0, assessment.getRiskPercentage(), 5.0);

        // Verify reason prioritizing expiry within 24 hours
        assertNotNull(assessment.getReason());
        assertTrue(assessment.getReason().toLowerCase().contains("24 hours") ||
                   assessment.getReason().toLowerCase().contains("1 day") ||
                   assessment.getReason().toLowerCase().contains("expires"),
                "Reason must explain imminent expiry: " + assessment.getReason());

        // Verify recommendation
        assertNotNull(assessment.getRecommendation());
        assertTrue(assessment.getRecommendation().toLowerCase().contains("reduce") ||
                   assessment.getRecommendation().toLowerCase().contains("redistribute"),
                "Recommendation must advise immediate mitigation");
        assertEquals("IMMEDIATE_USE", assessment.getPriorityUsage());
    }

    @Test
    @DisplayName("User Example 2: Fresh Fish with 3 days expiry -> Risk = MEDIUM")
    public void testFreshFishThreeDaysExpiryMediumRisk() {
        // Fresh Fish: Quantity 20kg, Demand 16kg, Expiry 3 days, WasteRate 0.12, CurrentProd 18kg
        PrologAssessment assessment = predictionService.assessFoodItem(
                "Fresh Fish", 20.0, 16.0, 3, 0.12, 18.0
        );

        assertNotNull(assessment);
        assertEquals("Fresh Fish", assessment.getItem());
        assertEquals("MEDIUM", assessment.getRisk());
        assertEquals("MEDIUM", assessment.getRiskLevel());
        assertEquals(55.0, assessment.getRiskPercentage(), 5.0);

        // Verify reason explains 2-3 days expiry
        assertNotNull(assessment.getReason());
        assertTrue(assessment.getReason().toLowerCase().contains("2-3 days") ||
                   assessment.getReason().toLowerCase().contains("3 days") ||
                   assessment.getReason().toLowerCase().contains("expires"),
                "Reason must mention approaching expiry: " + assessment.getReason());
    }

    @Test
    @DisplayName("User Example 3: Rice with 30 days expiry and balanced stock -> Risk = LOW")
    public void testRiceSafeExpiryLowRisk() {
        // Rice: Quantity 80kg, Demand 70kg, Expiry 30 days, WasteRate 0.02, CurrentProd 75kg
        PrologAssessment assessment = predictionService.assessFoodItem(
                "Rice", 80.0, 70.0, 30, 0.02, 75.0
        );

        assertNotNull(assessment);
        assertEquals("Rice", assessment.getItem());
        assertEquals("LOW", assessment.getRisk());
        assertEquals("LOW", assessment.getRiskLevel());
        assertTrue(assessment.getRiskPercentage() <= 30.0, "Risk percentage should be <= 30%");

        // Verify reason explains safe shelf life
        assertNotNull(assessment.getReason());
        assertTrue(assessment.getReason().toLowerCase().contains("safe") ||
                   assessment.getReason().toLowerCase().contains("balanced") ||
                   assessment.getReason().toLowerCase().contains("standard"),
                "Reason must explain safe shelf life / balanced stock: " + assessment.getReason());
    }

    @Test
    @DisplayName("Case 4: Chicken with high stock, near expiry, and high historical waste -> HIGH Risk")
    public void testChickenHighRiskCase() {
        // Chicken: Stock=50.0 kg, Demand=30.0 kg (166% ratio), Expiry=1 day, Waste History=0.22 (22%), Planned Prod=33.0 kg
        PrologAssessment assessment = predictionService.assessFoodItem(
                "Fresh Chicken Breast", 50.0, 30.0, 1, 0.22, 33.0
        );

        assertNotNull(assessment);
        assertEquals("Fresh Chicken Breast", assessment.getFoodName());
        assertEquals("HIGH", assessment.getRiskLevel());
        assertEquals(85.0, assessment.getRiskPercentage(), 5.0);

        // Verify explainable reasons
        assertFalse(assessment.getReasons().isEmpty(), "Reasons list must not be empty");
        boolean hasStockReason = assessment.getReasons().stream().anyMatch(r -> r.toLowerCase().contains("stock") || r.toLowerCase().contains("demand"));
        boolean hasExpiryReason = assessment.getReasons().stream().anyMatch(r -> r.toLowerCase().contains("expiry") || r.toLowerCase().contains("expires") || r.toLowerCase().contains("24 hours"));
        assertTrue(hasStockReason || hasExpiryReason, "Must contain stock or expiry reasoning");

        // Verify actionable mitigation recommendation
        assertNotNull(assessment.getRecommendation());
        assertTrue(assessment.getRecommendation().toLowerCase().contains("reduce") ||
                   assessment.getRecommendation().toLowerCase().contains("redistribute") ||
                   assessment.getRecommendation().toLowerCase().contains("pause") ||
                   assessment.getRecommendedProduction() < 33.0,
                "Recommendation must advise reducing or pausing production");
    }

    @Test
    @DisplayName("Case 5: Salad with short shelf-life (2 days) and high stock -> HIGH / IMMEDIATE_USE")
    public void testSaladImminentExpiry() {
        // Salad: Stock=18.5 kg, Demand=10.0 kg, Expiry=2 days, Waste History=0.18, Planned Prod=12.0 kg
        PrologAssessment assessment = predictionService.assessFoodItem(
                "Organic Garden Salad Mix", 18.5, 10.0, 2, 0.18, 12.0
        );

        assertNotNull(assessment);
        assertEquals("HIGH", assessment.getRiskLevel());
        assertTrue(assessment.getPriorityUsage().contains("IMMEDIATE") || assessment.getPriorityUsage().contains("PRIORITY"));
    }

    @Test
    @DisplayName("Case 6: End-to-end inventory evaluation pulling real items")
    public void testAllInventoryPrediction() throws SQLException {
        com.foodwasteai.service.FoodItemService foodItemService = new com.foodwasteai.service.FoodItemService();
        com.foodwasteai.model.FoodItem testItem = foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Test Prolog Eval Item " + System.currentTimeMillis(), "Dairy",
                        new java.math.BigDecimal("30.00"), "kg", new java.math.BigDecimal("4500.00"),
                        java.time.LocalDate.now().plusDays(1), new java.math.BigDecimal("5.00")), 1L
        );
        assertNotNull(testItem.getId());

        Map<String, Object> report = predictionService.assessAllInventory();

        assertNotNull(report);
        assertTrue(report.containsKey("overallRiskScore"));
        assertTrue(report.containsKey("items"));
        assertTrue(report.containsKey("potentialSavings"));

        @SuppressWarnings("unchecked")
        java.util.List<PrologAssessment> items = (java.util.List<PrologAssessment>) report.get("items");
        assertNotNull(items);
        assertFalse(items.isEmpty());
        Optional<PrologAssessment> firstOpt = predictionService.assessFoodItemById(items.get(0).getFoodItemId());
        assertTrue(firstOpt.isPresent());
        assertEquals(items.get(0).getFoodName(), firstOpt.get().getFoodName());
    }
}
