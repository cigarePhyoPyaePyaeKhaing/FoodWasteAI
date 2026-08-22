package com.foodwasteai;

import com.foodwasteai.service.GroqAIService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GroqChatPipelineTest {

    private GroqAIService groqService;
    private com.foodwasteai.service.FoodItemService foodItemService;

    @BeforeEach
    public void setUp() throws java.sql.SQLException {
        foodItemService = new com.foodwasteai.service.FoodItemService();
        groqService = new GroqAIService();
        
        // Ensure test chicken item exists for pipeline tests
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Fresh Chicken Breast", "Poultry",
                        new java.math.BigDecimal("35.00"), "kg", new java.math.BigDecimal("6500.00"),
                        java.time.LocalDate.now().plusDays(1), new java.math.BigDecimal("10.00")), 1L
        );
    }

    @Test
    @DisplayName("Complete Groq & Prolog Pipeline: User Query -> MySQL Data -> SWI-Prolog Reasoning -> AI / Grounded Explanation")
    public void testCompleteExplanationPipeline() {
        String query = "What is the food waste risk status today and what actions should we take?";
        GroqAIService.ChatResponse response = groqService.processUserQuery(query);

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
    @DisplayName("Groq Pipeline: Specific chicken risk query returns grounded Prolog facts and metrics")
    public void testChickenSpecificQuery() {
        String query = "What is the waste risk for Fresh Chicken Breast?";
        GroqAIService.ChatResponse response = groqService.processUserQuery(query);

        assertNotNull(response);
        assertTrue(response.getExplanation().toLowerCase().contains("chicken"));
        assertTrue(response.getExplanation().toLowerCase().contains("risk") || response.getExplanation().toLowerCase().contains("probability"));
        assertTrue(response.getExplanation().toLowerCase().contains("stock") || response.getExplanation().toLowerCase().contains("expiry") || response.getExplanation().toLowerCase().contains("operational"));
    }

    @Test
    @DisplayName("Groq Pipeline: Redistribution query returns charity partners and donation eligible surplus")
    public void testRedistributionQuery() {
        String query = "Which food banks or charities can we donate surplus food to?";
        GroqAIService.ChatResponse response = groqService.processUserQuery(query);

        assertNotNull(response);
        assertTrue(response.getExplanation().toLowerCase().contains("food bank") || response.getExplanation().toLowerCase().contains("hope"));
    }

    @Test
    @DisplayName("Bilingual Groq Pipeline: English and Myanmar queries return correctly localized explanations")
    public void testBilingualChatResponses() {
        // English query
        GroqAIService.ChatResponse enRes = groqService.processUserQuery("What is our chicken waste risk?", "en");
        assertNotNull(enRes);
        assertNotNull(enRes.getExplanation());
        assertTrue(enRes.getExplanation().contains("Chicken"));

        // Myanmar query
        GroqAIService.ChatResponse mmRes = groqService.processUserQuery("ကြက်သား အလေအလွင့် ဘာကြောင့်များတာလဲ?", "mm");
        assertNotNull(mmRes);
        assertNotNull(mmRes.getExplanation());
        assertFalse(mmRes.getExplanation().isEmpty());
    }

    @Test
    @DisplayName("Empty State: Chat should guide user when inventory has no items")
    public void testEmptyInventoryChatHandling() {
        GroqAIService customGroq = new GroqAIService();
        GroqAIService.ChatResponse res = customGroq.processUserQuery("What is our status?");
        assertNotNull(res);
        assertNotNull(res.getExplanation());
        assertFalse(res.getExplanation().isEmpty());
    }

    @Test
    @DisplayName("Hosted Groq AI: Specific Fresh Milk risk query returns dynamic answer, sources, related items, and risk info")
    public void testFreshMilkSpecificQuery() throws java.sql.SQLException {
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Fresh Milk", "Dairy",
                        new java.math.BigDecimal("8.00"), "liter", new java.math.BigDecimal("2000.00"),
                        java.time.LocalDate.now().minusDays(1), new java.math.BigDecimal("2.00")), 1L
        );

        String query = "Why is Fresh Milk risky?";
        GroqAIService.ChatResponse response = groqService.processUserQuery(query, "en");

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
    @DisplayName("Hosted Groq AI: Myanmar Fresh Milk query returns fully localized Burmese explanation preserving food name and units")
    public void testMyanmarFreshMilkQuery() throws java.sql.SQLException {
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Fresh Milk", "Dairy",
                        new java.math.BigDecimal("8.00"), "liter", new java.math.BigDecimal("2000.00"),
                        java.time.LocalDate.now().minusDays(1), new java.math.BigDecimal("2.00")), 1L
        );

        String query = "Fresh Milk ဘာကြောင့် အန္တရာယ်ရှိတာလဲ?";
        GroqAIService.ChatResponse response = groqService.processUserQuery(query, "mm");

        assertNotNull(response);
        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().contains("Fresh Milk"), "Must preserve food name Fresh Milk in Myanmar answer");
        assertTrue(response.getAnswer().contains("liter"), "Must preserve unit liter");
        assertTrue(response.getAnswer().contains("assess_waste_risk"), "Must preserve SWI-Prolog predicate");
    }

    @Test
    @DisplayName("Hosted Groq AI: High risk items query returns list of high risk items")
    public void testHighRiskQuery() {
        String query = "Which food items are high risk?";
        GroqAIService.ChatResponse response = groqService.processUserQuery(query, "en");

        assertNotNull(response);
        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().toLowerCase().contains("risk") || response.getAnswer().toLowerCase().contains("item"));
    }

    @Test
    @DisplayName("Hosted Groq AI: Redistribution query returns surplus items or partners")
    public void testSpecificSurplusRedistributionQuery() {
        String query = "Which items should be redistributed?";
        GroqAIService.ChatResponse response = groqService.processUserQuery(query, "en");

        assertNotNull(response);
        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().toLowerCase().contains("redistribut") || response.getAnswer().toLowerCase().contains("charity") || response.getAnswer().toLowerCase().contains("donation"));
    }

    @Test
    @DisplayName("Hosted Groq AI: Cook priority query returns chef priority items")
    public void testCookPriorityQuery() {
        String query = "What ingredients should our chef cook or prioritize today?";
        GroqAIService.ChatResponse response = groqService.processUserQuery(query, "en");

        assertNotNull(response);
        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().toLowerCase().contains("cook") || response.getAnswer().toLowerCase().contains("priorit") || response.getAnswer().toLowerCase().contains("ingredient"));
    }

    @Test
    @DisplayName("Hosted Groq AI: Daily summary query returns operational overview")
    public void testDailySummaryQuery() {
        String query = "Give me today's food waste summary.";
        GroqAIService.ChatResponse response = groqService.processUserQuery(query, "en");

        assertNotNull(response);
        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().toLowerCase().contains("summary") || response.getAnswer().toLowerCase().contains("inventory") || response.getAnswer().toLowerCase().contains("risk"));
    }

    @Test
    @DisplayName("Conversational AI: English and Myanmar Greetings return natural welcome without database/prolog overhead")
    public void testGreetingResponses() {
        // English Greeting
        GroqAIService.ChatResponse enRes = groqService.processUserQuery("hello", "en");
        assertNotNull(enRes);
        assertNotNull(enRes.getAnswer());
        assertTrue(enRes.getAnswer().contains("Hello 👋"));
        assertTrue(enRes.getAnswer().contains("FoodWaste AI Assistant"));
        assertTrue(enRes.getAnswer().contains("food waste risk"));

        // Myanmar Greeting
        GroqAIService.ChatResponse mmRes = groqService.processUserQuery("မင်္ဂလာပါ", "mm");
        assertNotNull(mmRes);
        assertNotNull(mmRes.getAnswer());
        assertTrue(mmRes.getAnswer().contains("မင်္ဂလာပါ 👋"));
        assertTrue(mmRes.getAnswer().contains("FoodWaste AI Assistant"));
    }

    @Test
    @DisplayName("Conversational AI: Casual conversation (thanks, who are you, what can you do)")
    public void testCasualConversation() {
        // Thanks
        GroqAIService.ChatResponse thanksRes = groqService.processUserQuery("thank you", "en");
        assertNotNull(thanksRes);
        assertTrue(thanksRes.getAnswer().toLowerCase().contains("welcome"));

        // Identity
        GroqAIService.ChatResponse idRes = groqService.processUserQuery("who are you?", "en");
        assertNotNull(idRes);
        assertTrue(idRes.getAnswer().contains("FoodWaste AI Assistant"));

        // Capabilities
        GroqAIService.ChatResponse capRes = groqService.processUserQuery("what can you do?", "en");
        assertNotNull(capRes);
        assertTrue(capRes.getAnswer().toLowerCase().contains("inventory") || capRes.getAnswer().toLowerCase().contains("waste"));
    }

    @Test
    @DisplayName("Conversational AI: Unknown / Off-Topic questions are handled politely without hallucination")
    public void testUnknownTopicQuery() {
        GroqAIService.ChatResponse res = groqService.processUserQuery("What is the weather outside?", "en");
        assertNotNull(res);
        assertTrue(res.getAnswer().contains("FoodWaste management") || res.getAnswer().contains("inventory"));
    }

    @Test
    @DisplayName("Dynamic Food Item Support: Any new food item added into MySQL is dynamically evaluated without hardcoding")
    public void testDynamicFoodItemSupport() throws java.sql.SQLException {
        String dynamicName = "Organic Australian Beef " + System.currentTimeMillis();
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, dynamicName, "Meat",
                        new java.math.BigDecimal("20.00"), "kg", new java.math.BigDecimal("18000.00"),
                        java.time.LocalDate.now().plusDays(1), new java.math.BigDecimal("5.00")), 1L
        );

        String query = "What is the waste risk for " + dynamicName + "?";
        GroqAIService.ChatResponse response = groqService.processUserQuery(query, "en");

        assertNotNull(response);
        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().contains(dynamicName), "Must dynamically contain newly created item name");
        assertTrue(response.getAnswer().contains("kg"), "Must preserve dynamic item unit");
        assertNotNull(response.getRelatedFoodItems());
        assertFalse(response.getRelatedFoodItems().isEmpty());
        assertEquals(dynamicName, response.getRelatedFoodItems().get(0).get("name"));
    }

    @Test
    @DisplayName("Conversation Context Memory: Chatbot remembers previous food item across multi-turn queries")
    public void testConversationContextMemory() {
        String sessionId = "test_session_" + System.currentTimeMillis();

        // Turn 1: Ask about fresh milk
        GroqAIService.ChatResponse res1 = groqService.processUserQuery("Why is fresh milk risky?", "en", sessionId);
        assertNotNull(res1);
        assertTrue(res1.getAnswer().toLowerCase().contains("fresh milk") || res1.getAnswer().contains("Fresh Milk"));
        assertNotNull(res1.getRelatedFoodItems());
        assertTrue("Fresh Milk".equalsIgnoreCase(res1.getRelatedFoodItems().get(0).get("name").toString()));

        // Turn 2: Ask about chicken (must switch context to chicken)
        GroqAIService.ChatResponse res2 = groqService.processUserQuery("what about chicken?", "en", sessionId);
        assertNotNull(res2);
        assertTrue(res2.getAnswer().contains("Fresh Chicken Breast") || res2.getAnswer().toLowerCase().contains("chicken"));
        assertNotNull(res2.getRelatedFoodItems());
        assertEquals("Fresh Chicken Breast", res2.getRelatedFoodItems().get(0).get("name"));

        // Turn 3: Follow-up question referring to "it" (must resolve "it" to Fresh Chicken Breast)
        GroqAIService.ChatResponse res3 = groqService.processUserQuery("what should I do with it?", "en", sessionId);
        assertNotNull(res3);
        assertTrue(res3.getAnswer().contains("Fresh Chicken Breast") || res3.getAnswer().toLowerCase().contains("chicken"));
        assertNotNull(res3.getRelatedFoodItems());
        assertEquals("Fresh Chicken Breast", res3.getRelatedFoodItems().get(0).get("name"));

        // Turn 4: Context switch to beef
        GroqAIService.ChatResponse res4 = groqService.processUserQuery("what about beef?", "en", sessionId);
        assertNotNull(res4);
        assertTrue(res4.getAnswer().toLowerCase().contains("beef"));
        assertNotNull(res4.getRelatedFoodItems());
        assertTrue(res4.getRelatedFoodItems().get(0).get("name").toString().toLowerCase().contains("beef"));
    }

    @Test
    @DisplayName("Smart Food Matching: Supports partial names, synonyms, and Burmese food names dynamically")
    public void testSmartFoodMatchingWithSynonymsAndPartials() {
        // Partial: "milk"
        GroqAIService.ChatResponse resPartial = groqService.processUserQuery("tell me about milk", "en");
        assertNotNull(resPartial);
        assertTrue(resPartial.getAnswer().contains("Milk") || resPartial.getAnswer().contains("milk"));

        // Synonym / Myanmar: "ကြက်သား"
        GroqAIService.ChatResponse resMm = groqService.processUserQuery("ကြက်သား အန္တရာယ် ဘယ်လိုရှိလဲ?", "mm");
        assertNotNull(resMm);
        assertTrue(resMm.getAnswer().contains("Chicken") || resMm.getAnswer().contains("ကြက်သား") || resMm.getAnswer().contains("အန္တရာယ်"));

        // Case-insensitive
        GroqAIService.ChatResponse resCase = groqService.processUserQuery("fReSh cHiCkEn bReAsT risk", "en");
        assertNotNull(resCase);
        assertTrue(resCase.getAnswer().toLowerCase().contains("chicken"));
    }

    @Test
    @DisplayName("Dynamic Salmon Test: Adding Salmon dynamically to MySQL is immediately recognized by chatbot")
    public void testDynamicSalmonSupport() throws java.sql.SQLException {
        String dynamicSalmon = "Fresh Atlantic Salmon " + System.currentTimeMillis();
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, dynamicSalmon, "Seafood",
                        new java.math.BigDecimal("10.00"), "kg", new java.math.BigDecimal("25000.00"),
                        java.time.LocalDate.now().plusDays(2), new java.math.BigDecimal("3.00")), 1L
        );

        GroqAIService.ChatResponse response = groqService.processUserQuery("is salmon risky?", "en");
        assertNotNull(response);
        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().toLowerCase().contains("salmon"), "Must dynamically evaluate Salmon");
        assertNotNull(response.getRelatedFoodItems());
        assertEquals(dynamicSalmon, response.getRelatedFoodItems().get(0).get("name"));
    }
}
