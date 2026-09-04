package com.foodwasteai;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.Recommendation;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.PredictionService;
import com.foodwasteai.service.RecommendationService;
import com.foodwasteai.service.RedistributionService;
import com.foodwasteai.util.ExpiryStatusResolver;
import com.foodwasteai.util.ValidationUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test suite verifying data fact consistency, validation rules,
 * category/unit preservation on edit, and shared days-remaining calculations.
 */
public class FoodItemConsistencyAndValidationTest {

    private FoodItemService foodItemService;
    private PredictionService predictionService;
    private RedistributionService redistributionService;

    @BeforeEach
    public void setUp() {
        foodItemService = new FoodItemService();
        predictionService = new PredictionService();
        redistributionService = new RedistributionService();
        FoodItemService.clearMemoryStore();
    }

    @Test
    @DisplayName("ValidationUtils accepts all canonical categories and units")
    public void testCanonicalCategoriesAndUnitsAccepted() {
        LocalDate expiry = ExpiryStatusResolver.getToday().plusDays(10);

        String[] validCategories = {"Poultry", "Produce", "Seafood", "Dairy", "Grains", "Bakery", "Other", "POULTRY", "seafood", "DAIRY"};
        String[] validUnits = {"kg", "g", "liter", "liters", "ml", "pcs", "pieces", "loaves", "units", "portions", "cans", "packs", "pack"};

        for (String cat : validCategories) {
            FoodItem item = new FoodItem(null, "Test Item", cat, BigDecimal.valueOf(10), "kg", BigDecimal.valueOf(1000), expiry);
            assertDoesNotThrow(() -> ValidationUtils.validateFoodItem(item), "Should accept valid category: " + cat);
        }

        for (String unit : validUnits) {
            FoodItem item = new FoodItem(null, "Test Item", "Produce", BigDecimal.valueOf(10), unit, BigDecimal.valueOf(1000), expiry);
            assertDoesNotThrow(() -> ValidationUtils.validateFoodItem(item), "Should accept valid unit: " + unit);
        }
    }

    @Test
    @DisplayName("ValidationUtils rejects invalid categories and invalid units")
    public void testInvalidCategoriesAndUnitsRejected() {
        LocalDate expiry = ExpiryStatusResolver.getToday().plusDays(10);

        FoodItem badCatItem = new FoodItem(null, "Invalid Item", "RandomNonExistentCategory", BigDecimal.valueOf(10), "kg", BigDecimal.valueOf(1000), expiry);
        IllegalArgumentException catEx = assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateFoodItem(badCatItem));
        assertTrue(catEx.getMessage().contains("Invalid food category"), "Error message should mention invalid category");

        FoodItem badUnitItem = new FoodItem(null, "Invalid Item", "Produce", BigDecimal.valueOf(10), "invalid_unit_xyz", BigDecimal.valueOf(1000), expiry);
        IllegalArgumentException unitEx = assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateFoodItem(badUnitItem));
        assertTrue(unitEx.getMessage().contains("Invalid measurement unit"), "Error message should mention invalid unit");
    }

    @Test
    @DisplayName("Fish item preserves Seafood category and Fresh Milk preserves liter unit across creation and update")
    public void testFishAndMilkCategoryAndUnitPreservation() throws Exception {
        LocalDate expiryFish = ExpiryStatusResolver.getToday().plusDays(5);
        FoodItem fish = new FoodItem(null, "Whole Sea Bass Fish " + System.currentTimeMillis(), "Seafood", BigDecimal.valueOf(8.5), "kg", BigDecimal.valueOf(12000), expiryFish);
        FoodItem savedFish = foodItemService.createFoodItem(fish, 1L);
        assertNotNull(savedFish.getId());
        assertEquals("Seafood", savedFish.getCategory());
        assertEquals("kg", savedFish.getUnit());

        // Update quantity and price (omitting or providing Seafood/kg)
        savedFish.setQuantity(BigDecimal.valueOf(12.0));
        savedFish.setPricePerUnit(BigDecimal.valueOf(13000));
        boolean updatedFish = foodItemService.updateFoodItem(savedFish, 1L);
        assertTrue(updatedFish);

        Optional<FoodItem> reloadedFish = foodItemService.getFoodItemById(savedFish.getId());
        assertTrue(reloadedFish.isPresent());
        assertEquals("Seafood", reloadedFish.get().getCategory(), "Fish category must remain Seafood and NOT Poultry");
        assertEquals("kg", reloadedFish.get().getUnit());
        assertEquals(0, reloadedFish.get().getQuantity().compareTo(BigDecimal.valueOf(12.0)));

        // Test Fresh Milk (Dairy, liter)
        LocalDate expiryMilk = ExpiryStatusResolver.getToday().plusDays(3);
        FoodItem milk = new FoodItem(null, "Fresh Pasteurized Milk " + System.currentTimeMillis(), "Dairy", BigDecimal.valueOf(15.0), "liter", BigDecimal.valueOf(2500), expiryMilk);
        FoodItem savedMilk = foodItemService.createFoodItem(milk, 1L);
        assertNotNull(savedMilk.getId());
        assertEquals("Dairy", savedMilk.getCategory());
        assertEquals("liter", savedMilk.getUnit(), "Milk unit must be liter and NOT kg");

        savedMilk.setQuantity(BigDecimal.valueOf(20.0));
        boolean updatedMilk = foodItemService.updateFoodItem(savedMilk, 1L);
        assertTrue(updatedMilk);

        Optional<FoodItem> reloadedMilk = foodItemService.getFoodItemById(savedMilk.getId());
        assertTrue(reloadedMilk.isPresent());
        assertEquals("Dairy", reloadedMilk.get().getCategory());
        assertEquals("liter", reloadedMilk.get().getUnit(), "Milk unit must remain liter and NOT kg after update");
    }

    @Test
    @DisplayName("Current days-remaining is exactly consistent across FoodItem, Prediction, and Redistribution without forecast day shift")
    public void testDaysRemainingSharedConsistency() throws Exception {
        LocalDate today = ExpiryStatusResolver.getToday();

        // 1. Expiry +1 day (e.g. Sep 3 from Sep 2)
        LocalDate exp1 = today.plusDays(1);
        FoodItem fish = new FoodItem(null, "Fish Expiry 1 Day " + System.currentTimeMillis(), "Seafood", BigDecimal.valueOf(10.0), "kg", BigDecimal.valueOf(8000), exp1);
        FoodItem savedFish = foodItemService.createFoodItem(fish, 1L);
        savedFish.updateComputedExpiryFields();
        assertEquals(1, savedFish.getExpiryDaysRemaining());
        assertEquals(1, savedFish.getCurrentDaysRemaining());

        // 2. Expiry +5 days (e.g. Sep 7 from Sep 2)
        LocalDate exp5 = today.plusDays(5);
        FoodItem bread = new FoodItem(null, "Bread Expiry 5 Days " + System.currentTimeMillis(), "Bakery", BigDecimal.valueOf(15.0), "pcs", BigDecimal.valueOf(1500), exp5);
        FoodItem savedBread = foodItemService.createFoodItem(bread, 1L);
        savedBread.updateComputedExpiryFields();
        assertEquals(5, savedBread.getExpiryDaysRemaining());
        assertEquals(5, savedBread.getCurrentDaysRemaining());

        // 3. Expiry +6 days (e.g. Sep 8 from Sep 2)
        LocalDate exp6 = today.plusDays(6);
        FoodItem milk = new FoodItem(null, "Milk Expiry 6 Days " + System.currentTimeMillis(), "Dairy", BigDecimal.valueOf(20.0), "liter", BigDecimal.valueOf(2500), exp6);
        FoodItem savedMilk = foodItemService.createFoodItem(milk, 1L);
        savedMilk.updateComputedExpiryFields();
        assertEquals(6, savedMilk.getExpiryDaysRemaining());
        assertEquals(6, savedMilk.getCurrentDaysRemaining());

        // 4. Expiry +15 days (e.g. Sep 17 from Sep 2)
        LocalDate exp15 = today.plusDays(15);
        FoodItem chicken = new FoodItem(null, "Chicken Expiry 15 Days " + System.currentTimeMillis(), "Poultry", BigDecimal.valueOf(20.0), "kg", BigDecimal.valueOf(6500), exp15);
        FoodItem savedChicken = foodItemService.createFoodItem(chicken, 1L);
        savedChicken.updateComputedExpiryFields();
        assertEquals(15, savedChicken.getExpiryDaysRemaining());
        assertEquals(15, savedChicken.getCurrentDaysRemaining());

        // 5. Prediction assessment DTO verification
        Optional<PrologAssessment> predChicken = predictionService.assessFoodItem(savedChicken);
        assertTrue(predChicken.isPresent());
        assertEquals(15, predChicken.get().getCurrentDaysRemaining(), "Chicken current days remaining must be 15, not 14");
        assertEquals("Poultry", predChicken.get().getCategory());
        assertEquals("kg", predChicken.get().getUnit());

        Optional<PrologAssessment> predFish = predictionService.assessFoodItem(savedFish);
        assertTrue(predFish.isPresent());
        assertEquals(1, predFish.get().getCurrentDaysRemaining(), "Fish current days remaining must be 1, not 0");
        assertEquals("Seafood", predFish.get().getCategory());

        // 6. 7-Day Forecast report item verification: currentDaysRemaining must remain 15 for chicken and 1 for fish
        Map<String, Object> forecastReport = predictionService.assessInventory(List.of(savedFish, savedBread, savedMilk, savedChicken));
        @SuppressWarnings("unchecked")
        List<PrologAssessment> items = (List<PrologAssessment>) forecastReport.get("items");
        assertNotNull(items);

        for (PrologAssessment a : items) {
            if (savedChicken.getId().equals(a.getFoodItemId())) {
                assertEquals(15, a.getCurrentDaysRemaining(), "Forecast assessment must preserve currentDaysRemaining = 15 for Chicken");
                assertEquals("Poultry", a.getCategory());
                assertEquals("kg", a.getUnit());
            }
            if (savedFish.getId().equals(a.getFoodItemId())) {
                assertEquals(1, a.getCurrentDaysRemaining(), "Forecast assessment must preserve currentDaysRemaining = 1 for Fish");
                assertEquals("Seafood", a.getCategory());
            }
        }

        // 7. RedistributionService candidate evaluation
        Map<String, Object> candidateMap = redistributionService.evaluateRedistributionCandidates();
        assertNotNull(candidateMap);
        @SuppressWarnings("unchecked")
        List<RedistributionService.CandidateItem> redistCandidates = (List<RedistributionService.CandidateItem>) candidateMap.get("redistributionCandidates");
        @SuppressWarnings("unchecked")
        List<RedistributionService.CandidateItem> priorityCandidates = (List<RedistributionService.CandidateItem>) candidateMap.get("priorityCandidates");

        // Chicken (+15 days) has surplus and 15 days -> must be DONATION_RECOMMENDED, NOT PRIORITY_DONATION
        boolean chickenInRedist = redistCandidates.stream().anyMatch(c -> c.getFoodItemId().equals(savedChicken.getId()) && c.getExpiryDays() == 15 && "DONATION_RECOMMENDED".equals(c.getStatus()));
        boolean chickenInPriority = priorityCandidates.stream().anyMatch(c -> c.getFoodItemId().equals(savedChicken.getId()));

        assertTrue(chickenInRedist, "Chicken with 15 days must be in DONATION_RECOMMENDED");
        assertFalse(chickenInPriority, "Chicken with 15 days must NOT be in PRIORITY_DONATION");
        // 8. Recommendation and Redistribution Quantity Consistency for Fish
        RecommendationService recommendationService = new RecommendationService();
        List<Recommendation> recs = recommendationService.generateRecommendationsFromProlog();
        Optional<Recommendation> fishRecOpt = recs.stream()
                .filter(r -> savedFish.getId().equals(r.getFoodItemId()) && r.getCategory() == Recommendation.Category.REDISTRIBUTION)
                .findFirst();

        Optional<RedistributionService.CandidateItem> fishCandOpt = priorityCandidates.stream()
                .filter(c -> savedFish.getId().equals(c.getFoodItemId()))
                .findFirst();

        assertTrue(fishCandOpt.isPresent(), "Fish (+1 day) must be evaluated as a candidate in priority donation");
        RedistributionService.CandidateItem fishCand = fishCandOpt.get();
        assertEquals(1.5, fishCand.getProjectedSurplus(), 0.01, "Fish (10kg stock) projected surplus in redistribution must be 1.5 kg (15%)");
        assertEquals(1.5, fishCand.getSuggestedDonationQuantity(), 0.01, "Fish suggested donation quantity must be 1.5 kg");
        assertEquals(8.5, fishCand.getExpectedDemand(), 0.01, "Fish expected demand must be 8.5 kg (85%)");

        if (fishRecOpt.isPresent()) {
            Recommendation fishRec = fishRecOpt.get();
            assertTrue(fishRec.getDescription().contains("1.5 kg"), "Recommendation description must state 1.5 kg surplus matching redistribution. Actual: " + fishRec.getDescription());
        }

        // Test 100 kg scenario (producing exact 15.0 kg surplus and 85.0 kg expected demand)
        FoodItem fish100 = new FoodItem(null, "Bulk Fish 100kg " + System.currentTimeMillis(), "Seafood", BigDecimal.valueOf(100.0), "kg", BigDecimal.valueOf(8000), exp1);
        FoodItem savedFish100 = foodItemService.createFoodItem(fish100, 1L);
        Optional<PrologAssessment> assess100Opt = predictionService.assessFoodItem(savedFish100);
        assertTrue(assess100Opt.isPresent());
        PrologAssessment assess100 = assess100Opt.get();
        assertEquals(85.0, assess100.getExpectedDemand(), 0.01, "Bulk Fish 100kg expected demand must be 85.0 kg");
        assertEquals(15.0, assess100.getProjectedSurplus(), 0.01, "Bulk Fish 100kg projected surplus must be 15.0 kg");
        assertEquals(15.0, assess100.getSuggestedDonationQuantity(), 0.01, "Bulk Fish 100kg suggested donation quantity must be 15.0 kg");
    }

    @Test
    @DisplayName("PredictionService unit breakdown formatting preserves distinct measurement units safely")
    public void testUnitBreakdownFormatting() {
        Map<String, Double> mixed = Map.of(
                "kg", 15.5,
                "liter", 20.0,
                "pcs", 12.0
        );

        String formatted = PredictionService.formatUnitBreakdownString(mixed);
        assertTrue(formatted.contains("15.5 kg") || formatted.contains("15.5kg"));
        assertTrue(formatted.contains("20.0 liter") || formatted.contains("20.0liter"));
        assertTrue(formatted.contains("12 pcs"));
        assertTrue(formatted.contains("•"));
    }

    @Test
    @DisplayName("Zero-stock items (quantity = 0) evaluate to OUT_OF_STOCK, are non-eligible for donation, and receive no active recommendations")
    public void testZeroStockItemNonActionable() throws Exception {
        LocalDate exp1 = ExpiryStatusResolver.getToday().plusDays(1);
        FoodItem zeroStockFish = new FoodItem(null, "Zero Stock Fish " + System.currentTimeMillis(), "Seafood", BigDecimal.ZERO, "kg", BigDecimal.valueOf(8000), exp1);
        FoodItem saved = foodItemService.createFoodItem(zeroStockFish, 1L);
        assertNotNull(saved.getId());

        // 1. Prolog assessment must report OUT_OF_STOCK and eligible = false
        Optional<PrologAssessment> assessOpt = predictionService.assessFoodItem(saved);
        assertTrue(assessOpt.isPresent());
        PrologAssessment assess = assessOpt.get();
        assertEquals("OUT_OF_STOCK", assess.getRedistributionStatus());
        assertEquals("NONE", assess.getRedistributionPriority());
        assertFalse(assess.isRedistributionEligible());
        assertEquals(0.0, assess.getSuggestedDonationQuantity(), 0.01);
        assertEquals(0.0, assess.getProjectedSurplus(), 0.01);

        // 2. RedistributionService candidate evaluation must omit zero-stock items from actionable candidates
        Map<String, Object> candidateReport = redistributionService.evaluateRedistributionCandidates();
        @SuppressWarnings("unchecked")
        List<RedistributionService.CandidateItem> priorityCand = (List<RedistributionService.CandidateItem>) candidateReport.get("priorityCandidates");
        @SuppressWarnings("unchecked")
        List<RedistributionService.CandidateItem> recCand = (List<RedistributionService.CandidateItem>) candidateReport.get("redistributionCandidates");

        boolean inPriority = priorityCand != null && priorityCand.stream().anyMatch(c -> saved.getId().equals(c.getFoodItemId()));
        boolean inRec = recCand != null && recCand.stream().anyMatch(c -> saved.getId().equals(c.getFoodItemId()));
        assertFalse(inPriority, "Zero-stock item must not be listed as a priority donation candidate");
        assertFalse(inRec, "Zero-stock item must not be listed as a recommended donation candidate");

        // 3. RecommendationService must not generate active recommendations for zero-stock items
        RecommendationService recService = new RecommendationService();
        List<Recommendation> recs = recService.generateRecommendationsFromProlog();
        boolean hasRecForZeroStock = recs.stream().anyMatch(r -> saved.getId().equals(r.getFoodItemId()));
        assertFalse(hasRecForZeroStock, "Zero-stock item must not generate active mitigation or donation recommendations");

        // 4. Scheduling dispatch on zero-stock item must be rejected
        com.foodwasteai.model.Redistribution dispatch = new com.foodwasteai.model.Redistribution();
        dispatch.setFoodItemId(saved.getId());
        dispatch.setRecipientId(1L);
        dispatch.setQuantity(BigDecimal.valueOf(5.0));
        assertThrows(IllegalArgumentException.class, () -> redistributionService.scheduleDispatch(dispatch, 1L),
                "Dispatching zero-stock item must throw IllegalArgumentException");
    }

    @Test
    @DisplayName("Redistribution recipients maintain valid partner types without unverified charity labeling")
    public void testRedistributionPartnerDirectoryTypes() throws Exception {
        List<com.foodwasteai.model.RedistributionRecipient> recipients = redistributionService.getAllRecipients();
        assertFalse(recipients.isEmpty(), "Recipient directory must contain seeded partner organizations");

        for (com.foodwasteai.model.RedistributionRecipient r : recipients) {
            assertNotNull(r.getName());
            assertNotNull(r.getOrganizationType());
            String type = r.getOrganizationType().toUpperCase().replace(" ", "_");
            assertTrue(
                    type.contains("FOOD_BANK") || type.contains("SHELTER") ||
                    type.contains("SOUP_KITCHEN") || type.contains("ANIMAL") ||
                    type.contains("RESCUE") || type.contains("COMPOST") ||
                    type.contains("CHARITY"),
                    "Partner type must be recognized: " + r.getOrganizationType()
            );
        }
    }
}
