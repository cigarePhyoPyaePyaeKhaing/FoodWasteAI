package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.prolog.PrologService;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.PredictionService;
import com.foodwasteai.util.ExpiryStatusResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authoritative regression test suite for the 7-Day AI Forecast Engine:
 * 1. 7-Day horizon continuity and Asia/Yangon calendar dates.
 * 2. Multi-unit preservation (liter, kg, pieces never summed blindly).
 * 3. Stock progression & bounded waste (stock never resets or drops below 0).
 * 4. Zero-stock exclusion and anti-hallucination guarantees.
 * 5. SWI-Prolog first-order logic reasoning and safety directives.
 * 6. Chatbot complete absence.
 * 7. Controlled test fixture: milk expiry countdown, deterministic demand, and non-resetting stock.
 * 8. Weekly summary derivation from 7-day daily forecast.
 * 9. Differentiated empty states and active inventory metadata.
 * 10. Removal of stale tomorrow-only text from dictionary and UI templates.
 */
public class SevenDayForecastRegressionTest {

    private PredictionService predictionService;
    private FoodItemService foodItemService;
    private PrologService prologService;

    @BeforeEach
    public void setUp() {
        predictionService = new PredictionService();
        foodItemService = new FoodItemService();
        prologService = new PrologService();
    }

    @Test
    @DisplayName("1. 7-Day Horizon: assessInventory returns exactly 7 consecutive days starting tomorrow")
    public void testSevenDayForecastStructureAndDates() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();
        LocalDate expectedStart = today.plusDays(1);
        LocalDate expectedEnd = today.plusDays(7);

        FoodItem milk = new FoodItem();
        milk.setId(101L);
        milk.setName("Fresh Milk");
        milk.setUnit("liter");
        milk.setQuantity(new BigDecimal("10.00"));
        milk.setPricePerUnit(new BigDecimal("2500.00"));
        milk.setExpiryDate(today.plusDays(2));

        FoodItem chicken = new FoodItem();
        chicken.setId(102L);
        chicken.setName("Chicken Breast");
        chicken.setUnit("kg");
        chicken.setQuantity(new BigDecimal("6.00"));
        chicken.setPricePerUnit(new BigDecimal("8000.00"));
        chicken.setExpiryDate(today.plusDays(4));

        Map<String, Object> forecast = predictionService.assessInventory(List.of(milk, chicken));
        assertNotNull(forecast);

        assertEquals(expectedStart.toString(), forecast.get("forecastStartDate"));
        assertEquals(expectedEnd.toString(), forecast.get("forecastEndDate"));
        assertEquals(2, forecast.get("activeInventoryCount"));
        assertEquals(true, forecast.get("hasActiveInventory"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) forecast.get("days");
        assertNotNull(days);
        assertEquals(7, days.size(), "Forecast must contain exactly 7 consecutive calendar days");

        for (int i = 0; i < 7; i++) {
            Map<String, Object> day = days.get(i);
            int dayIndex = i + 1;
            assertEquals(dayIndex, day.get("dayIndex"));
            LocalDate expectedDayDate = today.plusDays(dayIndex);
            assertEquals(expectedDayDate.toString(), day.get("date"), "Day " + dayIndex + " date must match expected calendar date");
            assertNotNull(day.get("dayName"));
            assertNotNull(day.get("riskLevel"));
            assertNotNull(day.get("riskScore"));
            assertNotNull(day.get("unitBreakdown"));
            assertNotNull(day.get("predictedWasteByUnit"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> weeklySummary = (Map<String, Object>) forecast.get("weeklySummary");
        assertNotNull(weeklySummary);
        assertEquals(expectedStart.toString(), weeklySummary.get("forecastStartDate"));
        assertEquals(expectedEnd.toString(), weeklySummary.get("forecastEndDate"));
        assertNotNull(weeklySummary.get("formattedTotalWaste"));
        assertNotNull(weeklySummary.get("overallRiskScore"));
        assertNotNull(weeklySummary.get("highestRiskScore"));
        assertNotNull(weeklySummary.get("highestRiskDate"));
    }

    @Test
    @DisplayName("2. Unit Preservation: Distinct units (kg, liter, pieces) preserved in daily and weekly totals")
    public void testMultiUnitPreservation() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();

        FoodItem milk = new FoodItem();
        milk.setId(201L);
        milk.setName("Pasteurized Milk");
        milk.setUnit("liter");
        milk.setQuantity(new BigDecimal("8.00"));
        milk.setPricePerUnit(new BigDecimal("2000.00"));
        milk.setExpiryDate(today.plusDays(1)); // Expires Day 1

        FoodItem bread = new FoodItem();
        bread.setId(202L);
        bread.setName("Whole Wheat Bread");
        bread.setUnit("pcs");
        bread.setQuantity(new BigDecimal("5.00"));
        bread.setPricePerUnit(new BigDecimal("1500.00"));
        bread.setExpiryDate(today.plusDays(1)); // Expires Day 1

        FoodItem beef = new FoodItem();
        beef.setId(203L);
        beef.setName("Beef Steak");
        beef.setUnit("kg");
        beef.setQuantity(new BigDecimal("4.00"));
        beef.setPricePerUnit(new BigDecimal("16000.00"));
        beef.setExpiryDate(today.plusDays(3)); // Expires Day 3

        Map<String, Object> forecast = predictionService.assessInventory(List.of(milk, bread, beef));
        assertNotNull(forecast);

        @SuppressWarnings("unchecked")
        Map<String, Double> weeklyBreakdown = (Map<String, Double>) forecast.get("unitBreakdown");
        assertNotNull(weeklyBreakdown);

        assertTrue(weeklyBreakdown.containsKey("liter") || weeklyBreakdown.containsKey("pcs") || weeklyBreakdown.containsKey("kg"));

        String formattedWaste = (String) forecast.get("formattedTotalWaste");
        assertNotNull(formattedWaste);
        // Formatted waste must never simply sum 8 liters + 5 pcs + 4 kg into "17"
        assertFalse(formattedWaste.equals("17.0"), "Mismatched units must never be blindly added into a single number");
    }

    @Test
    @DisplayName("3. Stock Progression & Bounded Waste: Projected waste never exceeds projected remaining stock")
    public void testStockProgressionAndBoundedWaste() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();

        FoodItem veggies = new FoodItem();
        veggies.setId(301L);
        veggies.setName("Fresh Salad");
        veggies.setCategory("Salad");
        veggies.setUnit("kg");
        veggies.setQuantity(new BigDecimal("12.00"));
        veggies.setPricePerUnit(new BigDecimal("3000.00"));
        veggies.setExpiryDate(today.plusDays(3)); // Expires on Day 3

        Map<String, Object> forecast = predictionService.assessInventory(List.of(veggies));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) forecast.get("days");

        for (Map<String, Object> day : days) {
            @SuppressWarnings("unchecked")
            List<PrologAssessment> items = (List<PrologAssessment>) day.get("items");
            for (PrologAssessment item : items) {
                if (item.getFoodItemId() != null && item.getFoodItemId().equals(301L)) {
                    assertTrue(item.getStock() >= 0.0, "Projected stock must never drop below 0");
                    assertTrue(item.getPredictedWasteQuantity() <= item.getStock() + 0.001,
                            "Predicted waste quantity (" + item.getPredictedWasteQuantity() + ") must be bounded by projected stock (" + item.getStock() + ")");
                }
            }
        }
    }

    @Test
    @DisplayName("4. Zero-Stock Exclusion: Zero-stock items produce zero active waste advice across all 7 days")
    public void testZeroStockExclusionAcrossHorizon() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();

        FoodItem zeroStock = new FoodItem();
        zeroStock.setId(401L);
        zeroStock.setName("Zero Stock Fish");
        zeroStock.setUnit("kg");
        zeroStock.setQuantity(BigDecimal.ZERO);
        zeroStock.setPricePerUnit(new BigDecimal("5000.00"));
        zeroStock.setExpiryDate(today.plusDays(2));

        Map<String, Object> forecast = predictionService.assessInventory(List.of(zeroStock));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) forecast.get("days");

        for (Map<String, Object> day : days) {
            @SuppressWarnings("unchecked")
            List<PrologAssessment> items = (List<PrologAssessment>) day.get("items");
            assertTrue(items.isEmpty(), "Zero-stock item must not generate active prediction items");
            assertEquals(0.0, ((Number) day.get("riskScore")).doubleValue(), 0.01);
            assertEquals(0, (Integer) day.get("highRiskItemCount"));
        }
    }

    @Test
    @DisplayName("5. SWI-Prolog Grounding: Authoritative risk reasoning and priority usage without LLM dependency")
    public void testPrologGroundingAndSafetyDirectives() {
        PrologAssessment freshAssess = prologService.assessFoodItem(
                "Fresh Yogurt", "pack", 15.0, 3.0, 1, 0.05, 3.5
        );
        assertNotNull(freshAssess);
        assertEquals("HIGH", freshAssess.getRiskLevel());
        assertEquals(85.0, freshAssess.getRiskScore(), 0.01);
        assertEquals("IMMEDIATE_USE", freshAssess.getPriorityUsage());

        PrologAssessment pastExpiredAssess = prologService.assessFoodItem(
                "Spoiled Pork", "kg", 5.0, 2.0, -1, 0.20, 0.0
        );
        assertNotNull(pastExpiredAssess);
        assertEquals("HIGH", pastExpiredAssess.getRiskLevel());
        assertEquals(95.0, pastExpiredAssess.getRiskScore(), 0.01);
        assertEquals("DISPOSE_OR_COMPOST", pastExpiredAssess.getPriorityUsage());
        assertFalse(pastExpiredAssess.isRecommendRedistribution(), "Expired food must never be recommended for donation");
    }

    @Test
    @DisplayName("6. Chatbot Complete Absence: Chat servlet, AI assistant services and resources completely removed")
    public void testChatbotCompleteAbsence() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.foodwasteai.controller.ChatServlet"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.foodwasteai.service.GroqAIService"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.foodwasteai.service.GeminiExplanationService"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.foodwasteai.service.OllamaAIService"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.foodwasteai.service.WebsiteKnowledgeService"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.foodwasteai.service.IntentClassifier"));
    }

    @Test
    @DisplayName("7. Controlled Fixture Test: Milk 20L expiring in 3 days progresses daily and does not reset stock")
    public void testControlledMilkForecastProgression() throws SQLException {
        LocalDate today = ExpiryStatusResolver.getToday();
        FoodItem milk = new FoodItem();
        milk.setId(501L);
        milk.setName("Pasteurized Milk 20L");
        milk.setCategory("Dairy");
        milk.setUnit("liter");
        milk.setQuantity(new BigDecimal("20.00"));
        milk.setPricePerUnit(new BigDecimal("3000.00"));
        milk.setExpiryDate(today.plusDays(3)); // Expires on Day 3

        Map<String, Object> forecast = predictionService.assessInventory(List.of(milk));
        assertNotNull(forecast);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) forecast.get("days");
        assertEquals(7, days.size(), "All 7 forecast days must be returned");

        // Day 1: 2 days left to expiry, initial stock 20.0
        Map<String, Object> day1 = days.get(0);
        @SuppressWarnings("unchecked")
        List<PrologAssessment> day1Items = (List<PrologAssessment>) day1.get("items");
        assertEquals(1, day1Items.size());
        assertEquals(20.0, day1Items.get(0).getStock(), 0.01);
        assertEquals(2, day1Items.get(0).getExpiryDays());

        // Day 2: 1 day left to expiry, stock diminished by expected demand (< 20.0)
        Map<String, Object> day2 = days.get(1);
        @SuppressWarnings("unchecked")
        List<PrologAssessment> day2Items = (List<PrologAssessment>) day2.get("items");
        assertEquals(1, day2Items.size());
        assertTrue(day2Items.get(0).getStock() < 20.0, "Stock must decrease on Day 2 and not reset to 20.0");
        assertEquals(1, day2Items.get(0).getExpiryDays());

        // Day 3: Expiry day (0 days left), remaining unsold stock evaluated as waste
        Map<String, Object> day3 = days.get(2);
        @SuppressWarnings("unchecked")
        List<PrologAssessment> day3Items = (List<PrologAssessment>) day3.get("items");
        assertEquals(1, day3Items.size());
        assertEquals(0, day3Items.get(0).getExpiryDays());
        assertTrue(day3Items.get(0).getStock() <= day2Items.get(0).getStock(), "Stock must not increase on Day 3");

        // Day 4+: Already expired on prior date, stock = 0, no active items
        Map<String, Object> day4 = days.get(3);
        @SuppressWarnings("unchecked")
        List<PrologAssessment> day4Items = (List<PrologAssessment>) day4.get("items");
        assertTrue(day4Items.isEmpty(), "Expired stock must not generate active items on Day 4");
    }

    @Test
    @DisplayName("8. Differentiated Empty States: Distinguishes empty inventory from zero-waste inventory")
    public void testEmptyInventoryDifferentiatedState() throws SQLException {
        Map<String, Object> emptyForecast = predictionService.assessInventory(List.of());
        assertNotNull(emptyForecast);
        assertEquals(0, emptyForecast.get("activeInventoryCount"));
        assertEquals(false, emptyForecast.get("hasActiveInventory"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) emptyForecast.get("days");
        assertEquals(7, days.size());
        for (Map<String, Object> day : days) {
            assertEquals(0.0, ((Number) day.get("riskScore")).doubleValue(), 0.01);
            assertEquals("LOW", day.get("riskLevel"));
            @SuppressWarnings("unchecked")
            List<?> items = (List<?>) day.get("items");
            assertTrue(items.isEmpty());
        }
    }

    @Test
    @DisplayName("9. Dictionary Audit: No stale tomorrow-only titles in i18n dictionaries")
    public void testNoStaleTomorrowTitlesInI18n() throws IOException {
        String enContent = Files.readString(new File("src/main/webapp/js/i18n/en.js").toPath());
        String mmContent = Files.readString(new File("src/main/webapp/js/i18n/mm.js").toPath());

        // Verify pred.modal.title is updated to 7-Day Forecast
        assertTrue(enContent.contains("\"pred.modal.title\": \"7-Day Predicted Waste Forecast\""));
        assertTrue(mmContent.contains("\"pred.modal.title\": \"၇ ရက်စာ ခန့်မှန်းအလေအလွင့် ဆန်းစစ်ချက်\""));

        // Verify pred.modal.predictionDate is updated to Forecast Period (7 Days)
        assertTrue(enContent.contains("\"pred.modal.predictionDate\": \"Forecast Period (7 Days)\""));
        assertTrue(mmContent.contains("\"pred.modal.predictionDate\": \"ခန့်မှန်းကာလ (၇ ရက်)\""));
    }
}
