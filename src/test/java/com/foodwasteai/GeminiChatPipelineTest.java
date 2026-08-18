package com.foodwasteai;

import com.foodwasteai.service.GeminiExplanationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GeminiChatPipelineTest {

    private GeminiExplanationService geminiService;

    @BeforeEach
    public void setUp() {
        geminiService = new GeminiExplanationService();
    }

    @Test
    @DisplayName("Complete Pipeline: User Query -> MySQL Data -> SWI-Prolog Reasoning -> Gemini Explanation -> Smart Recommendations")
    public void testCompleteExplanationPipeline() {
        String query = "What is the food waste risk status today and what actions should we take?";
        GeminiExplanationService.ChatResponse response = geminiService.processUserQuery(query);

        assertNotNull(response);
        assertEquals(query, response.getUserQuery());
        assertNotNull(response.getExplanation(), "Explanation must not be null");
        assertFalse(response.getExplanation().isEmpty(), "Explanation must not be empty");
        assertNotNull(response.getSourceEngine());

        // Verify that Prolog reasoning was attached
        assertNotNull(response.getPrologSummary());
        assertTrue(response.getPrologSummary().containsKey("overallRiskScore"));

        // Verify Smart Recommendations were derived
        assertFalse(response.getSmartRecommendations().isEmpty(), "Smart recommendations must not be empty");
        boolean hasAction = response.getSmartRecommendations().stream()
                .anyMatch(a -> a.getActionType().equals("REDUCE_PRODUCTION") || a.getActionType().equals("SCHEDULE_DONATION"));
        assertTrue(hasAction, "Must contain actionable mitigation action");
    }

    @Test
    @DisplayName("Pipeline: Specific chicken risk query returns grounded Prolog facts")
    public void testChickenSpecificQuery() {
        String query = "What is the waste risk for Fresh Chicken Breast?";
        GeminiExplanationService.ChatResponse response = geminiService.processUserQuery(query);

        assertNotNull(response);
        assertTrue(response.getExplanation().toLowerCase().contains("chicken"));
        assertTrue(response.getExplanation().toLowerCase().contains("high") || response.getExplanation().contains("82%"));
        assertTrue(response.getExplanation().toLowerCase().contains("stock") || response.getExplanation().toLowerCase().contains("expiry"));
    }

    @Test
    @DisplayName("Pipeline: Redistribution query returns charity partners and donation eligible surplus")
    public void testRedistributionQuery() {
        String query = "Which food banks or charities can we donate surplus food to?";
        GeminiExplanationService.ChatResponse response = geminiService.processUserQuery(query);

        assertNotNull(response);
        assertTrue(response.getExplanation().toLowerCase().contains("food bank") || response.getExplanation().toLowerCase().contains("hope"));
    }
}
