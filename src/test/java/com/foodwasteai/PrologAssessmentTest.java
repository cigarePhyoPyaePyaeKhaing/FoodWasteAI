package com.foodwasteai;

import com.foodwasteai.model.ApiResponse;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.service.PredictionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PrologAssessmentTest {

    @Test
    @DisplayName("Should evaluate high risk item when expiry is imminent")
    public void testHighRiskImminentExpiry() {
        PredictionService predictionService = new PredictionService();
        // Chicken: 50kg stock, 30kg demand, 1 day to expiry, 0.25 waste rate, 40kg current prod
        PrologAssessment assessment = predictionService.assessFoodItem("Chicken", 50.0, 30.0, 1, 0.25, 40.0);

        assertNotNull(assessment);
        assertEquals("HIGH", assessment.getRiskLevel());
        assertTrue(assessment.getRiskPercentage() >= 80.0);
        assertFalse(assessment.getReasons().isEmpty());
        assertTrue(assessment.getRecommendedProduction() < 40.0);
    }

    @Test
    @DisplayName("Should evaluate low risk item with safe shelf life")
    public void testLowRiskSafeShelfLife() {
        PredictionService predictionService = new PredictionService();
        // Rice: 20kg stock, 30kg demand, 60 days to expiry, 0.02 waste rate, 30kg current prod
        PrologAssessment assessment = predictionService.assessFoodItem("Rice", 20.0, 30.0, 60, 0.02, 30.0);

        assertNotNull(assessment);
        assertEquals("LOW", assessment.getRiskLevel());
        assertTrue(assessment.getRiskPercentage() <= 40.0);
    }

    @Test
    @DisplayName("Should populate explanation output fields: item, risk, reason, recommendation")
    public void testExplanationOutputFields() {
        PredictionService predictionService = new PredictionService();
        PrologAssessment assessment = predictionService.assessFoodItem("Fresh Milk", 40.0, 24.0, 1, 0.08, 26.0);

        assertNotNull(assessment);
        assertEquals("Fresh Milk", assessment.getItem());
        assertEquals("HIGH", assessment.getRisk());
        assertNotNull(assessment.getReason());
        assertFalse(assessment.getReason().trim().isEmpty());
        assertNotNull(assessment.getRecommendation());
        assertFalse(assessment.getRecommendation().trim().isEmpty());
    }

    @Test
    @DisplayName("Should generate valid ApiResponse wrappers")
    public void testApiResponseStructure() {
        ApiResponse<String> successResp = ApiResponse.success("Operation completed", "TestData");
        assertTrue(successResp.isSuccess());
        assertEquals("Operation completed", successResp.getMessage());
        assertEquals("TestData", successResp.getData());
        assertNotNull(successResp.getTimestamp());

        ApiResponse<Void> errorResp = ApiResponse.error("Something went wrong");
        assertFalse(errorResp.isSuccess());
        assertEquals("Something went wrong", errorResp.getMessage());
        assertNull(errorResp.getData());
    }
}
