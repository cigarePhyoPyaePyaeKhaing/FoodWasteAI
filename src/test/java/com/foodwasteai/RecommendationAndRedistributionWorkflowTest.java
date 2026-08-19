package com.foodwasteai;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.FoodItemDao;
import com.foodwasteai.dao.PredictionDao;
import com.foodwasteai.dao.RecommendationDao;
import com.foodwasteai.dao.RedistributionDao;
import com.foodwasteai.model.*;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.PredictionService;
import com.foodwasteai.service.RecommendationService;
import com.foodwasteai.service.RedistributionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class RecommendationAndRedistributionWorkflowTest {

    private FoodItemService foodItemService;
    private PredictionService predictionService;
    private RecommendationService recommendationService;
    private RedistributionService redistributionService;
    private PredictionDao predictionDao;

    @BeforeEach
    public void setUp() throws Exception {
        foodItemService = new FoodItemService();
        predictionService = new PredictionService();
        recommendationService = new RecommendationService(new RecommendationDao(), predictionService, foodItemService);
        redistributionService = new RedistributionService(new RedistributionDao(), foodItemService);
        predictionDao = new PredictionDao();
    }

    @Test
    @DisplayName("Data Flow: food_items -> SWI-Prolog prediction -> prediction_items -> recommendations table")
    public void testCompleteDataFlowPersistence() throws SQLException {
        // 1. Ensure inventory food items exist
        List<FoodItem> items = foodItemService.getAllFoodItems();
        assertFalse(items.isEmpty(), "food_items must contain inventory records");

        // 2. Execute SWI-Prolog batch evaluation
        Map<String, Object> predReport = predictionService.assessAllInventory();
        assertNotNull(predReport);
        assertTrue(predReport.containsKey("overallRiskScore"));

        // 3. Verify prediction_items persisted in database if DB is available
        if (DatabaseConfig.isAvailable()) {
            Optional<Prediction> latestPred = predictionDao.findLatestPrediction();
            assertTrue(latestPred.isPresent(), "Prediction record must be saved in predictions table");
            List<PredictionItem> pItems = predictionDao.findItemsByPredictionId(latestPred.get().getId());
            assertFalse(pItems.isEmpty(), "Prediction items must be saved in prediction_items table");
        }

        // 4. Generate recommendations from prediction items
        List<Recommendation> recs = recommendationService.generateRecommendationsFromProlog();
        assertNotNull(recs);
        assertFalse(recs.isEmpty(), "Generated recommendations must not be empty");

        // Verify recommendations in database
        List<Recommendation> dbRecs = recommendationService.getAllRecommendations();
        assertFalse(dbRecs.isEmpty(), "Recommendations must be retrievable via service");
    }

    @Test
    @DisplayName("Verify HIGH, MEDIUM, LOW Risk Generated Directives Exact Rules")
    public void testRiskLevelRecommendationRules() throws SQLException {
        List<Recommendation> recs = recommendationService.generateRecommendationsFromProlog();

        // 1. Verify HIGH Risk Directives (Fresh Milk or Fresh Fish)
        // Must contain: "Reduce next production batch", "Redistribute excess inventory", "Prioritize usage today"
        boolean highReduceBatch = recs.stream().anyMatch(r ->
                r.getRiskLevel() == Recommendation.RiskLevel.HIGH &&
                r.getTitle().toLowerCase().contains("reduce next production batch"));
        boolean highRedistribute = recs.stream().anyMatch(r ->
                r.getRiskLevel() == Recommendation.RiskLevel.HIGH &&
                r.getTitle().toLowerCase().contains("redistribute excess inventory"));
        boolean highPrioritizeUsage = recs.stream().anyMatch(r ->
                r.getRiskLevel() == Recommendation.RiskLevel.HIGH &&
                r.getTitle().toLowerCase().contains("prioritize usage today"));

        assertTrue(highReduceBatch, "HIGH risk items must generate 'Reduce next production batch' recommendation");
        assertTrue(highRedistribute, "HIGH risk items must generate 'Redistribute excess inventory' recommendation");
        assertTrue(highPrioritizeUsage, "HIGH risk items must generate 'Prioritize usage today' recommendation");

        // 2. Verify MEDIUM Risk Directives (Vegetables)
        // Must contain: "Monitor stock", "Adjust preparation quantity", "Promote usage"
        boolean medMonitor = recs.stream().anyMatch(r ->
                r.getRiskLevel() == Recommendation.RiskLevel.MEDIUM &&
                r.getTitle().toLowerCase().contains("monitor stock"));
        boolean medAdjustPrep = recs.stream().anyMatch(r ->
                r.getRiskLevel() == Recommendation.RiskLevel.MEDIUM &&
                r.getTitle().toLowerCase().contains("adjust preparation quantity"));
        boolean medPromote = recs.stream().anyMatch(r ->
                r.getRiskLevel() == Recommendation.RiskLevel.MEDIUM &&
                r.getTitle().toLowerCase().contains("promote usage"));

        assertTrue(medMonitor, "MEDIUM risk items must generate 'Monitor stock' recommendation");
        assertTrue(medAdjustPrep, "MEDIUM risk items must generate 'Adjust preparation quantity' recommendation");
        assertTrue(medPromote, "MEDIUM risk items must generate 'Promote usage' recommendation");

        // 3. Verify LOW Risk Directives (Rice, Frozen Chicken)
        // Must contain: "Maintain normal operation"
        boolean lowNormalOp = recs.stream().anyMatch(r ->
                r.getRiskLevel() == Recommendation.RiskLevel.LOW &&
                r.getTitle().toLowerCase().contains("maintain normal operation"));

        assertTrue(lowNormalOp, "LOW risk items must generate 'Maintain normal operation' recommendation");
    }

    @Test
    @DisplayName("Redistribution Workflow: High Risk Item -> Recommendation -> Request -> Recipient Selection -> Record")
    public void testCompleteRedistributionWorkflow() throws SQLException {
        // 1. Available Recipients
        List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();
        assertFalse(recipients.isEmpty(), "Available recipients directory must not be empty");
        RedistributionRecipient partner = recipients.get(0);
        assertNotNull(partner.getName());
        assertNotNull(partner.getOrganizationType());

        // 2. Create High Risk Item for Redistribution
        FoodItem highRiskItem = foodItemService.createFoodItem(
                new FoodItem(null, "Test Fresh Milk " + System.currentTimeMillis(), "Dairy", new BigDecimal("40.00"), "kg", new BigDecimal("5000.00"), LocalDate.now().plusDays(1), new BigDecimal("8.00")), 1L
        );
        BigDecimal initialQty = highRiskItem.getQuantity();

        // 3. Create Redistribution Request with selected Recipient
        Redistribution dispatch = new Redistribution();
        dispatch.setFoodItemId(highRiskItem.getId());
        dispatch.setRecipientId(partner.getId());
        dispatch.setQuantity(new BigDecimal("10.00"));
        dispatch.setUnit(highRiskItem.getUnit() != null ? highRiskItem.getUnit() : "kg");
        dispatch.setPickupTime(LocalDateTime.now().plusDays(1).withHour(15).withMinute(0));
        dispatch.setNotes("Scheduled donation from AI High Risk recommendation");

        Redistribution saved = redistributionService.scheduleDispatch(dispatch, 1L);
        assertNotNull(saved.getId());
        assertEquals(partner.getName(), saved.getRecipientName());

        // 4. Verify Inventory stock deduction
        Optional<FoodItem> afterOpt = foodItemService.getFoodItemById(highRiskItem.getId());
        assertTrue(afterOpt.isPresent());
        assertEquals(0, initialQty.subtract(new BigDecimal("10.00")).compareTo(afterOpt.get().getQuantity()));

        // 5. Update Status: PENDING -> COMPLETED
        boolean updatedCompleted = redistributionService.updateDispatchStatus(saved.getId(), Redistribution.Status.COMPLETED);
        assertTrue(updatedCompleted, "Status update to COMPLETED should succeed");

        // 6. Verify Display Stats: quantity redistributed, money saved, waste reduction impact
        Map<String, Object> stats = redistributionService.getRedistributionStats();
        assertNotNull(stats);
        assertTrue(stats.containsKey("quantityRedistributedKg"));
        assertTrue(stats.containsKey("estimatedMoneySaved"));
        assertTrue(stats.containsKey("wasteReductionImpactKg"));
        assertTrue(new BigDecimal(stats.get("quantityRedistributedKg").toString()).compareTo(BigDecimal.ZERO) > 0);
        assertTrue(new BigDecimal(stats.get("estimatedMoneySaved").toString()).compareTo(BigDecimal.ZERO) > 0);
        assertTrue(new BigDecimal(stats.get("wasteReductionImpactKg").toString()).compareTo(BigDecimal.ZERO) > 0);
    }
}
