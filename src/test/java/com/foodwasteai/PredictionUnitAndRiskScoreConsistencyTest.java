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
}
