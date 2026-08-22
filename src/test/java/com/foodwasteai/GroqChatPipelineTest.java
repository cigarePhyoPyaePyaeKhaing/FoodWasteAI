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
        assertTrue(response.getSources().stream().anyMatch(s -> s.contains("Fresh Milk") || s.contains("Inventory") || s.contains("Expiry") || s.contains("Waste")));

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
    @DisplayName("Conversational AI: Greetings return friendly welcome without operational data overhead")
    public void testGreetingResponses() {
        // English Greeting
        GroqAIService.ChatResponse enRes = groqService.processUserQuery("hi", "en");
        assertNotNull(enRes);
        assertNotNull(enRes.getAnswer());
        assertTrue(enRes.getAnswer().contains("FoodWaste AI Assistant"));
        assertTrue(enRes.getSources().isEmpty(), "Greeting should not include technical data sources");
        assertTrue(enRes.getSmartRecommendations().isEmpty(), "Greeting should not include smart directives");

        // Myanmar Greeting
        GroqAIService.ChatResponse mmRes = groqService.processUserQuery("မင်္ဂလာပါ", "mm");
        assertNotNull(mmRes);
        assertNotNull(mmRes.getAnswer());
        assertTrue(mmRes.getAnswer().contains("FoodWaste AI Assistant") || mmRes.getAnswer().contains("မင်္ဂလာပါ"));
        assertTrue(mmRes.getSources().isEmpty(), "Greeting should not include technical data sources");
        assertTrue(mmRes.getSmartRecommendations().isEmpty(), "Greeting should not include smart directives");
    }

    @Test
    @DisplayName("Conversational AI: Casual conversation (how do you do, thanks, who are you, what can you do)")
    public void testCasualConversation() {
        // Casual chat: how do you do
        GroqAIService.ChatResponse casualRes = groqService.processUserQuery("how do you do?", "en");
        assertNotNull(casualRes);
        assertNotNull(casualRes.getAnswer());
        assertTrue(casualRes.getAnswer().toLowerCase().contains("well") || casualRes.getAnswer().toLowerCase().contains("help"));
        assertTrue(casualRes.getSources().isEmpty(), "Casual chat should not include technical data sources");
        assertTrue(casualRes.getSmartRecommendations().isEmpty(), "Casual chat should not include smart directives");

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

        // Turn 2: Ask about Fresh Chicken Breast explicitly (must switch context to chicken and not contain fresh milk)
        GroqAIService.ChatResponse res2 = groqService.processUserQuery("What is the waste risk for Fresh Chicken Breast?", "en", sessionId);
        assertNotNull(res2);
        assertTrue(res2.getAnswer().contains("Fresh Chicken Breast"));
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

    @Test
    @DisplayName("Conversational AI: Expanded casual queries ('what you doing', 'hehe', 'hekoo', 'i am boring') return natural chat without data leaks")
    public void testCasualChatExpandedPhrases() {
        String[] casualInputs = {
            "what you doing", "what are you doing", "hehe", "haha", "hekoo", "i am boring", "i am bored", "how are you doing"
        };

        for (String input : casualInputs) {
            GroqAIService.ChatResponse res = groqService.processUserQuery(input, "en");
            assertNotNull(res, "Response for '" + input + "' must not be null");
            assertNotNull(res.getAnswer(), "Answer for '" + input + "' must not be null");
            assertFalse(res.getAnswer().isEmpty(), "Answer for '" + input + "' must not be empty");

            // Verify no data sources or inventory/summary leakage
            assertTrue(res.getSources().isEmpty(), "Casual input '" + input + "' must not have sources");
            assertTrue(res.getSmartRecommendations().isEmpty(), "Casual input '" + input + "' must not have smart recommendations");
            assertTrue(res.getRelatedFoodItems().isEmpty(), "Casual input '" + input + "' must not have food cards");
            assertFalse(res.getAnswer().contains("Data Sources:"), "Answer for '" + input + "' must not contain Data Sources");
            assertFalse(res.getAnswer().contains("Daily Intelligence Summary"), "Answer for '" + input + "' must not contain Daily Intelligence Summary");
        }
    }

    @Test
    @DisplayName("Context Isolation & Response Validation: Switching to Fresh Chicken Breast never mentions previous fresh milk")
    public void testStrictFoodEntityResponseValidation() throws java.sql.SQLException {
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Fresh Milk", "Dairy",
                        new java.math.BigDecimal("8.00"), "liter", new java.math.BigDecimal("2000.00"),
                        java.time.LocalDate.now().minusDays(1), new java.math.BigDecimal("2.00")), 1L
        );

        String sessionId = "strict_validation_session_" + System.currentTimeMillis();

        // Step 1: Query fresh milk
        GroqAIService.ChatResponse milkRes = groqService.processUserQuery("Why is fresh milk risky?", "en", sessionId);
        assertNotNull(milkRes);
        assertTrue(milkRes.getAnswer().toLowerCase().contains("milk"));

        // Step 2: Query Fresh Chicken Breast
        GroqAIService.ChatResponse chickenRes = groqService.processUserQuery("What is the waste risk for Fresh Chicken Breast?", "en", sessionId);
        assertNotNull(chickenRes);
        assertTrue(chickenRes.getAnswer().contains("Fresh Chicken Breast"), "Must analyze Fresh Chicken Breast");
        assertFalse(chickenRes.getAnswer().toLowerCase().contains("fresh milk"), "Fresh Chicken Breast response must NOT contain fresh milk");
        assertEquals("Fresh Chicken Breast", chickenRes.getRelatedFoodItems().get(0).get("name"));
    }

    @Test
    @DisplayName("Unknown Food Handling: Querying unrecorded food returns clear not-recorded message without false risk/high-risk list")
    public void testUnknownFoodHandling() {
        GroqAIService.ChatResponse res = groqService.processUserQuery("Is lobster risky?", "en");
        assertNotNull(res);
        assertNotNull(res.getAnswer());
        // New message: "I can't find **Lobster** in the current inventory."
        assertTrue(
            res.getAnswer().toLowerCase().contains("can't find") ||
            res.getAnswer().toLowerCase().contains("inventory") ||
            res.getAnswer().toLowerCase().contains("not currently recorded"),
            "Must state item is not found in inventory"
        );
        assertTrue(res.getAnswer().contains("Lobster"), "Must mention Lobster");
        assertFalse(res.getAnswer().toLowerCase().contains("fresh milk"), "Must not mention fresh milk");
        assertFalse(res.getAnswer().contains("Priority High-Risk Kitchen Items"), "Must not return high-risk list");
        assertTrue(res.getSources().isEmpty());
        assertTrue(res.getRelatedFoodItems().isEmpty());
    }

    @Test
    @DisplayName("User Verification 5-Step Sequence: Fresh Milk -> Chicken -> Context follow-up -> Unknown Item -> High Risk Query")
    public void testUserVerificationSequence() throws java.sql.SQLException {
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Fresh Milk", "Dairy",
                        new java.math.BigDecimal("8.00"), "liter", new java.math.BigDecimal("2000.00"),
                        java.time.LocalDate.now().minusDays(1), new java.math.BigDecimal("2.00")), 1L
        );

        String sessionId = "user_verification_seq_" + System.currentTimeMillis();

        // 1. Why is fresh milk risky? -> fresh milk
        GroqAIService.ChatResponse res1 = groqService.processUserQuery("Why is fresh milk risky?", "en", sessionId);
        assertNotNull(res1);
        assertTrue(res1.getAnswer().toLowerCase().contains("milk"));

        // 2. What is the waste risk for Fresh Chicken Breast? -> Fresh Chicken Breast (must not contain fresh milk)
        GroqAIService.ChatResponse res2 = groqService.processUserQuery("What is the waste risk for Fresh Chicken Breast?", "en", sessionId);
        assertNotNull(res2);
        assertTrue(res2.getAnswer().contains("Fresh Chicken Breast"));
        assertFalse(res2.getAnswer().toLowerCase().contains("fresh milk"));

        // 3. what should I do with it? -> Fresh Chicken Breast recommendation
        GroqAIService.ChatResponse res3 = groqService.processUserQuery("what should I do with it?", "en", sessionId);
        assertNotNull(res3);
        assertTrue(res3.getAnswer().contains("Fresh Chicken Breast") || res3.getAnswer().toLowerCase().contains("chicken"));

        // 4. Is unrecorded food risky? -> not found in inventory
        GroqAIService.ChatResponse res4 = groqService.processUserQuery("Is dragonfruit risky?", "en", sessionId);
        assertNotNull(res4);
        assertTrue(
            res4.getAnswer().toLowerCase().contains("can't find") ||
            res4.getAnswer().toLowerCase().contains("inventory") ||
            res4.getAnswer().toLowerCase().contains("not currently recorded"),
            "Unknown food must indicate item is not in inventory"
        );
        assertFalse(res4.getAnswer().toLowerCase().contains("fresh milk"));
        assertFalse(res4.getAnswer().contains("Priority High-Risk Kitchen Items"));


        // 5. Which food items are high risk? -> Global high risk list
        GroqAIService.ChatResponse res5 = groqService.processUserQuery("Which food items are high risk?", "en", sessionId);
        assertNotNull(res5);
        assertTrue(res5.getAnswer().contains("High-Risk") || res5.getAnswer().toLowerCase().contains("risk"));
    }

    // ===================================================================
    // FULL PRODUCTION TEST MATRIX (A - N)
    // ===================================================================

    @Test
    @DisplayName("A. Greeting: Returns greeting only — no directives, no sources, responseType=GREETING")
    public void testA_Greeting() {
        GroqAIService.ChatResponse res = groqService.processUserQuery("hello");
        assertNotNull(res);
        assertFalse(res.getAnswer().isEmpty());
        assertFalse(res.getAnswer().toLowerCase().contains("daily intelligence summary"));
        assertFalse(res.getAnswer().toLowerCase().contains("high-risk"));
        assertTrue(res.getSmartRecommendations().isEmpty());
        assertTrue(res.getSources().isEmpty());
        assertEquals("GREETING", res.getResponseType());
    }

    @Test
    @DisplayName("B. Casual hehe: Returns casual, no food data, responseType=CASUAL_CHAT")
    public void testB_CasualHehe() {
        GroqAIService.ChatResponse res = groqService.processUserQuery("hehe");
        assertNotNull(res);
        assertFalse(res.getAnswer().isEmpty());
        assertFalse(res.getAnswer().toLowerCase().contains("intelligence summary"));
        assertTrue(res.getSmartRecommendations().isEmpty());
        assertTrue(res.getSources().isEmpty());
        assertEquals("CASUAL_CHAT", res.getResponseType());
    }

    @Test
    @DisplayName("B2. Casual 'i am bored': No summary triggered, responseType=CASUAL_CHAT")
    public void testB2_CasualBored() {
        GroqAIService.ChatResponse res = groqService.processUserQuery("i am bored");
        assertNotNull(res);
        assertFalse(res.getAnswer().toLowerCase().contains("intelligence summary"));
        assertTrue(res.getSmartRecommendations().isEmpty());
        assertEquals("CASUAL_CHAT", res.getResponseType());
    }

    @Test
    @DisplayName("C. Identity 'who are you': mentions assistant, no food data, responseType=CASUAL_CHAT")
    public void testC_Identity() {
        GroqAIService.ChatResponse res = groqService.processUserQuery("who are you");
        assertNotNull(res);
        assertTrue(res.getAnswer().toLowerCase().contains("foodwaste") || res.getAnswer().toLowerCase().contains("assistant"));
        assertTrue(res.getSmartRecommendations().isEmpty());
        assertEquals("CASUAL_CHAT", res.getResponseType());
    }

    @Test
    @DisplayName("D. Fresh Milk specific: prologSummary non-null, responseType=SPECIFIC_FOOD")
    public void testD_FreshMilkSpecific() throws java.sql.SQLException {
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Fresh Milk", "Dairy",
                        new java.math.BigDecimal("8.00"), "liter", new java.math.BigDecimal("2000.00"),
                        java.time.LocalDate.now().minusDays(1), new java.math.BigDecimal("2.00")), 1L
        );
        GroqAIService.ChatResponse res = groqService.processUserQuery("Why is Fresh Milk risky?", "en");
        assertNotNull(res);
        assertTrue(res.getAnswer().contains("Fresh Milk") || res.getAnswer().toLowerCase().contains("milk"));
        assertNotNull(res.getPrologSummary());
        assertEquals("SPECIFIC_FOOD", res.getResponseType());
    }

    @Test
    @DisplayName("E. Chicken specific query: answer contains Chicken not Fresh Milk, responseType=SPECIFIC_FOOD")
    public void testE_ChickenSpecificIsolated() {
        GroqAIService.ChatResponse res = groqService.processUserQuery("What is the waste risk for Fresh Chicken Breast?", "en");
        assertNotNull(res);
        assertTrue(res.getAnswer().toLowerCase().contains("chicken"));
        assertFalse(res.getAnswer().toLowerCase().contains("fresh milk"));
        assertEquals("SPECIFIC_FOOD", res.getResponseType());
    }

    @Test
    @DisplayName("F. Unknown food lobster: not found message, no high-risk list, responseType=UNKNOWN_FOOD")
    public void testF_UnknownFood() {
        GroqAIService.ChatResponse res = groqService.processUserQuery("Is lobster risky?", "en");
        assertNotNull(res);
        assertTrue(
            res.getAnswer().toLowerCase().contains("inventory") ||
            res.getAnswer().toLowerCase().contains("can't find") ||
            res.getAnswer().toLowerCase().contains("not currently recorded") ||
            res.getAnswer().toLowerCase().contains("add it"),
            "Unknown food answer must guide user to add item to inventory"
        );
        assertFalse(res.getAnswer().contains("Priority High-Risk Kitchen Items"));
        assertTrue(res.getSources().isEmpty());
        assertEquals("UNKNOWN_FOOD", res.getResponseType());
    }

    @Test
    @DisplayName("G. Global high risk list: prologSummary non-null, answer not empty")
    public void testG_GlobalHighRiskList() {
        GroqAIService.ChatResponse res = groqService.processUserQuery("Which food items are high risk?", "en");
        assertNotNull(res);
        assertFalse(res.getAnswer().isEmpty());
        assertNotNull(res.getPrologSummary());
    }

    @Test
    @DisplayName("H. Context food switch Milk→Chicken→it: 'it' resolves to Chicken not Milk")
    public void testH_ContextFoodSwitch() throws java.sql.SQLException {
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Fresh Milk", "Dairy",
                        new java.math.BigDecimal("8.00"), "liter", new java.math.BigDecimal("2000.00"),
                        java.time.LocalDate.now().minusDays(1), new java.math.BigDecimal("2.00")), 1L
        );
        String sessionId = "ctx_switch_" + System.currentTimeMillis();
        GroqAIService.ChatResponse r1 = groqService.processUserQuery("Why is fresh milk risky?", "en", sessionId);
        assertNotNull(r1);
        assertTrue(r1.getAnswer().toLowerCase().contains("milk"));

        GroqAIService.ChatResponse r2 = groqService.processUserQuery("What is the waste risk for Fresh Chicken Breast?", "en", sessionId);
        assertNotNull(r2);
        assertTrue(r2.getAnswer().toLowerCase().contains("chicken"));
        assertFalse(r2.getAnswer().toLowerCase().contains("fresh milk"));

        GroqAIService.ChatResponse r3 = groqService.processUserQuery("what should I do with it?", "en", sessionId);
        assertNotNull(r3);
        assertFalse(r3.getAnswer().toLowerCase().contains("fresh milk"), "Follow-up must not revert to fresh milk");
    }

    @Test
    @DisplayName("I. Myanmar casual 'နေကောင်းလား': No directives, responseType=CASUAL_CHAT")
    public void testI_MyanmarCasual() {
        GroqAIService.ChatResponse res = groqService.processUserQuery("နေကောင်းလား", "mm");
        assertNotNull(res);
        assertFalse(res.getAnswer().isEmpty());
        assertTrue(res.getSmartRecommendations().isEmpty());
        assertEquals("CASUAL_CHAT", res.getResponseType());
    }

    @Test
    @DisplayName("J. English response: no Myanmar script leakage when lang=en")
    public void testJ_EnglishNoMyanmarLeakage() throws java.sql.SQLException {
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Fresh Milk", "Dairy",
                        new java.math.BigDecimal("8.00"), "liter", new java.math.BigDecimal("2000.00"),
                        java.time.LocalDate.now().minusDays(1), new java.math.BigDecimal("2.00")), 1L
        );
        GroqAIService.ChatResponse res = groqService.processUserQuery("Why is fresh milk risky?", "en");
        assertNotNull(res);
        boolean hasMyanmarScript = res.getAnswer().chars().anyMatch(c -> c >= 0x1000 && c <= 0x109F);
        assertFalse(hasMyanmarScript, "English response must not leak Myanmar script");
    }

    @Test
    @DisplayName("K. Sources deduplication: no duplicate entries in sources list")
    public void testK_SourcesNoDuplication() throws java.sql.SQLException {
        foodItemService.createFoodItem(
                new com.foodwasteai.model.FoodItem(null, "Fresh Milk", "Dairy",
                        new java.math.BigDecimal("8.00"), "liter", new java.math.BigDecimal("2000.00"),
                        java.time.LocalDate.now().minusDays(1), new java.math.BigDecimal("2.00")), 1L
        );
        GroqAIService.ChatResponse res = groqService.processUserQuery("Why is fresh milk risky?", "en");
        assertNotNull(res);
        java.util.Set<String> unique = new java.util.HashSet<>(res.getSources());
        assertEquals(unique.size(), res.getSources().size(), "Sources must not contain duplicates");
    }

    @Test
    @DisplayName("L. ResponseType set on all paths — greeting/casual/unknown food/operational")
    public void testL_ResponseTypeSetOnAllPaths() {
        assertEquals("GREETING", groqService.processUserQuery("hi").getResponseType());
        assertEquals("CASUAL_CHAT", groqService.processUserQuery("haha").getResponseType());
        assertEquals("CASUAL_CHAT", groqService.processUserQuery("thank you").getResponseType());
        assertEquals("CASUAL_CHAT", groqService.processUserQuery("who are you").getResponseType());
        assertEquals("CASUAL_CHAT", groqService.processUserQuery("what can you do").getResponseType());
        assertEquals("UNKNOWN_FOOD", groqService.processUserQuery("Is unicornfish risky?", "en").getResponseType());
        GroqAIService.ChatResponse op = groqService.processUserQuery("Give me today's food waste summary.");
        assertNotNull(op.getResponseType());
        assertNotEquals("CASUAL_CHAT", op.getResponseType());
    }

    @Test
    @DisplayName("M. prologSummary non-null for all paths (casual=empty map, operational=real data)")
    public void testM_PrologSummaryNonNull() {
        GroqAIService.ChatResponse casual = groqService.processUserQuery("hello");
        assertNotNull(casual.getPrologSummary(), "Casual response prologSummary must be initialized (empty map)");

        GroqAIService.ChatResponse op = groqService.processUserQuery("What is our food waste risk today?");
        assertNotNull(op.getPrologSummary());
        assertTrue(op.getPrologSummary().containsKey("overallRiskScore"), "Operational prologSummary must contain overallRiskScore");
    }

    @Test
    @DisplayName("N. Out-of-domain weather query: polite redirect, no food analysis triggered")
    public void testN_OutOfDomain() {
        GroqAIService.ChatResponse res = groqService.processUserQuery("What is the weather like today?", "en");
        assertNotNull(res);
        assertFalse(res.getAnswer().isEmpty());
        assertFalse(res.getAnswer().contains("Daily Intelligence Summary"));
        assertFalse(res.getAnswer().contains("Priority High-Risk"));
    }
}

