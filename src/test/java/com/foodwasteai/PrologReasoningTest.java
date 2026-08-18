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
    @DisplayName("Case 1: Chicken with high stock, near expiry, and high historical waste -> HIGH Risk (82%)")
    public void testChickenHighRiskCase() {
        // Chicken: Stock=50.0 kg, Demand=30.0 kg (166% ratio), Expiry=1 day, Waste History=0.22 (22%), Planned Prod=33.0 kg
        PrologAssessment assessment = predictionService.assessFoodItem(
                "Fresh Chicken Breast", 50.0, 30.0, 1, 0.22, 33.0
        );

        assertNotNull(assessment);
        assertEquals("Fresh Chicken Breast", assessment.getFoodName());
        assertEquals("HIGH", assessment.getRiskLevel());
        assertEquals(82.0, assessment.getRiskPercentage(), 5.0);

        // Verify explainable reasons
        assertFalse(assessment.getReasons().isEmpty(), "Reasons list must not be empty");
        boolean hasStockReason = assessment.getReasons().stream().anyMatch(r -> r.toLowerCase().contains("stock") || r.toLowerCase().contains("demand"));
        boolean hasExpiryReason = assessment.getReasons().stream().anyMatch(r -> r.toLowerCase().contains("expiry"));
        assertTrue(hasStockReason || hasExpiryReason, "Must contain stock or expiry reasoning");

        // Verify actionable mitigation recommendation
        assertNotNull(assessment.getRecommendation());
        assertTrue(assessment.getRecommendation().toLowerCase().contains("reduce") ||
                   assessment.getRecommendation().toLowerCase().contains("pause") ||
                   assessment.getRecommendedProduction() < 33.0,
                "Recommendation must advise reducing or pausing production");
    }

    @Test
    @DisplayName("Case 2: Rice with normal stock and low waste history -> LOW Risk (20%)")
    public void testRiceLowRiskCase() {
        // Rice: Stock=20.0 kg, Demand=30.0 kg, Expiry=60 days, Waste History=0.02 (2%), Planned Prod=30.0 kg
        PrologAssessment assessment = predictionService.assessFoodItem(
                "Premium Jasmine Rice", 20.0, 30.0, 60, 0.02, 30.0
        );

        assertNotNull(assessment);
        assertEquals("Premium Jasmine Rice", assessment.getFoodName());
        assertEquals("LOW", assessment.getRiskLevel());
        assertTrue(assessment.getRiskPercentage() <= 30.0, "Risk percentage should be <= 30%");

        // Verify reasons
        assertFalse(assessment.getReasons().isEmpty());
        assertTrue(assessment.getReasons().stream().anyMatch(r -> r.toLowerCase().contains("balanced") || r.toLowerCase().contains("safe") || r.toLowerCase().contains("standard")));
    }

    @Test
    @DisplayName("Case 3: Salad with short shelf-life and overstock -> HIGH / IMMEDIATE_USE")
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
    @DisplayName("Case 4: End-to-end inventory evaluation pulling real items")
    public void testAllInventoryPrediction() throws SQLException {
        Map<String, Object> report = predictionService.assessAllInventory();

        assertNotNull(report);
        assertTrue(report.containsKey("overallRiskScore"));
        assertTrue(report.containsKey("items"));
        assertTrue(report.containsKey("potentialSavings"));

        Optional<PrologAssessment> chickenOpt = predictionService.assessFoodItemById(1L);
        assertTrue(chickenOpt.isPresent());
        assertEquals("Fresh Chicken Breast", chickenOpt.get().getFoodName());
    }
}
