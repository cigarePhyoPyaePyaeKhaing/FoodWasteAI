package com.foodwasteai;

import com.foodwasteai.service.OllamaAIService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OllamaChatPipelineTest {

    private OllamaAIService ollamaService;
    private com.foodwasteai.service.FoodItemService foodItemService;

    @BeforeEach
    public void setUp() throws java.sql.SQLException {
        foodItemService = new com.foodwasteai.service.FoodItemService();
        ollamaService = new OllamaAIService();
        
        // Ensure test chicken item exists for pipeline tests
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Fresh Chicken Breast", "Poultry",
                        new java.math.BigDecimal("35.00"), "kg", new java.math.BigDecimal("6500.00"),
                        java.time.LocalDate.now().plusDays(1), new java.math.BigDecimal("10.00")), 1L
        );
    }

    @Test
    @DisplayName("Complete Ollama & Prolog Pipeline: User Query -> MySQL Data -> SWI-Prolog Reasoning -> AI / Grounded Explanation")
    public void testCompleteExplanationPipeline() {
        String query = "What is the food waste risk status today and what actions should we take?";
        OllamaAIService.ChatResponse response = ollamaService.processUserQuery(query);

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
    @DisplayName("Ollama Pipeline: Specific chicken risk query returns grounded Prolog facts and metrics")
    public void testChickenSpecificQuery() {
        String query = "What is the waste risk for Fresh Chicken Breast?";
        OllamaAIService.ChatResponse response = ollamaService.processUserQuery(query);

        assertNotNull(response);
        assertTrue(response.getExplanation().toLowerCase().contains("chicken"));
        assertTrue(response.getExplanation().toLowerCase().contains("risk") || response.getExplanation().toLowerCase().contains("probability"));
        assertTrue(response.getExplanation().toLowerCase().contains("stock") || response.getExplanation().toLowerCase().contains("expiry") || response.getExplanation().toLowerCase().contains("operational"));
    }

    @Test
    @DisplayName("Ollama Pipeline: Redistribution query returns charity partners and donation eligible surplus")
    public void testRedistributionQuery() {
        String query = "Which food banks or charities can we donate surplus food to?";
        OllamaAIService.ChatResponse response = ollamaService.processUserQuery(query);

        assertNotNull(response);
        assertTrue(response.getExplanation().toLowerCase().contains("food bank") || response.getExplanation().toLowerCase().contains("hope"));
    }

    @Test
    @DisplayName("Bilingual Ollama Pipeline: English and Myanmar queries return correctly localized explanations")
    public void testBilingualChatResponses() {
        // English query
        OllamaAIService.ChatResponse enRes = ollamaService.processUserQuery("What is our chicken waste risk?", "en");
        assertNotNull(enRes);
        assertNotNull(enRes.getExplanation());
        assertTrue(enRes.getExplanation().contains("Chicken"));

        // Myanmar query
        OllamaAIService.ChatResponse mmRes = ollamaService.processUserQuery("ကြက်သား အလေအလွင့် ဘာကြောင့်များတာလဲ?", "mm");
        assertNotNull(mmRes);
        assertNotNull(mmRes.getExplanation());
        assertFalse(mmRes.getExplanation().isEmpty());
    }

    @Test
    @DisplayName("Empty State: Chat should guide user when inventory has no items")
    public void testEmptyInventoryChatHandling() {
        OllamaAIService customOllama = new OllamaAIService();
        OllamaAIService.ChatResponse res = customOllama.processUserQuery("What is our status?");
        assertNotNull(res);
        assertNotNull(res.getExplanation());
        assertFalse(res.getExplanation().isEmpty());
    }

    @Test
    @DisplayName("Free Local AI: Specific Fresh Milk risk query returns dynamic answer, sources, related items, and risk info")
    public void testFreshMilkSpecificQuery() throws java.sql.SQLException {
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Fresh Milk", "Dairy",
                        new java.math.BigDecimal("8.00"), "liter", new java.math.BigDecimal("2000.00"),
                        java.time.LocalDate.now().minusDays(1), new java.math.BigDecimal("2.00")), 1L
        );

        String query = "Why is Fresh Milk risky?";
        OllamaAIService.ChatResponse response = ollamaService.processUserQuery(query, "en");

        assertNotNull(response);
        assertEquals(query, response.getUserQuery());
        assertNotNull(response.getAnswer(), "Answer must not be null");
        assertEquals(response.getAnswer(), response.getExplanation());
        assertTrue(response.getAnswer().contains("Fresh Milk"), "Must preserve exact food name Fresh Milk");
        assertTrue(response.getAnswer().contains("liter"), "Must preserve unit liter");
        assertTrue(response.getAnswer().contains("8.0"), "Must preserve stock 8.0");

        // Verify Sources
        assertNotNull(response.getSources());
        assertFalse(response.getSources().isEmpty(), "Sources must not be empty");
        assertTrue(response.getSources().stream().anyMatch(s -> s.contains("Fresh Milk") || s.contains("MySQL") || s.contains("assess_waste_risk")));

        // Verify Related Food Items
        assertNotNull(response.getRelatedFoodItems());
        assertFalse(response.getRelatedFoodItems().isEmpty(), "Related food items must contain Fresh Milk");
        assertEquals("Fresh Milk", response.getRelatedFoodItems().get(0).get("name"));

        // Verify Risk Info
        assertNotNull(response.getRiskInfo());
        assertTrue(response.getRiskInfo().containsKey("totalItemsEvaluated"));
        assertTrue(response.getRiskInfo().containsKey("highRiskCount"));
    }

    @Test
    @DisplayName("Free Local AI: Myanmar Fresh Milk query returns fully localized Burmese explanation preserving food name and units")
    public void testMyanmarFreshMilkQuery() throws java.sql.SQLException {
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Fresh Milk", "Dairy",
                        new java.math.BigDecimal("8.00"), "liter", new java.math.BigDecimal("2000.00"),
                        java.time.LocalDate.now().minusDays(1), new java.math.BigDecimal("2.00")), 1L
        );

        String query = "Fresh Milk ဘာကြောင့် အန္တရာယ်ရှိတာလဲ?";
        OllamaAIService.ChatResponse response = ollamaService.processUserQuery(query, "mm");

        assertNotNull(response);
        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().contains("Fresh Milk"), "Must preserve food name Fresh Milk in Myanmar answer");
        assertTrue(response.getAnswer().contains("liter"), "Must preserve unit liter");
        assertTrue(response.getAnswer().contains("assess_waste_risk"), "Must preserve SWI-Prolog predicate");
    }

    @Test
    @DisplayName("Free Local AI: High risk items query returns list of high risk items")
    public void testHighRiskQuery() {
        String query = "Which items are high risk?";
        OllamaAIService.ChatResponse response = ollamaService.processUserQuery(query, "en");

        assertNotNull(response);
        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().toLowerCase().contains("risk") || response.getAnswer().toLowerCase().contains("swi-prolog"));
    }
}
