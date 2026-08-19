package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.Recommendation;
import com.foodwasteai.model.Redistribution;
import com.foodwasteai.model.RedistributionRecipient;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class RecommendationRedistributionTest {

    private RecommendationService recommendationService;
    private RedistributionService redistributionService;
    private FoodItemService foodItemService;

    @BeforeEach
    public void setUp() {
        foodItemService = new FoodItemService();
        PredictionService predictionService = new PredictionService();
        recommendationService = new RecommendationService(new com.foodwasteai.dao.RecommendationDao(), predictionService, foodItemService);
        redistributionService = new RedistributionService(new com.foodwasteai.dao.RedistributionDao(), foodItemService);
    }

    @Test
    @DisplayName("Should retrieve and filter recommendations by category and status")
    public void testRecommendationRetrieval() throws SQLException {
        recommendationService.generateRecommendationsFromProlog();
        List<Recommendation> all = recommendationService.getAllRecommendations();
        assertFalse(all.isEmpty(), "Recommendations should not be empty");

        List<Recommendation> urgent = recommendationService.getRecommendationsByCategory(Recommendation.Category.URGENT);
        assertFalse(urgent.isEmpty());
        assertEquals(Recommendation.Category.URGENT, urgent.get(0).getCategory());

        // Test status update (Accept / Dismiss)
        Long recId = all.get(0).getId();
        boolean accepted = recommendationService.updateRecommendationStatus(recId, Recommendation.Status.ACCEPTED);
        assertTrue(accepted);

        List<Recommendation> acceptedList = recommendationService.getRecommendationsByStatus(Recommendation.Status.ACCEPTED);
        assertTrue(acceptedList.stream().anyMatch(r -> r.getId().equals(recId)));
    }

    @Test
    @DisplayName("Should generate fresh recommendations from Prolog reasoning")
    public void testGenerateFromProlog() throws SQLException {
        List<Recommendation> generated = recommendationService.generateRecommendationsFromProlog();
        assertNotNull(generated);
        assertFalse(generated.isEmpty());
        assertTrue(generated.stream().anyMatch(r -> r.getCategory() == Recommendation.Category.URGENT));
    }

    @Test
    @DisplayName("Should schedule surplus redistribution and deduct inventory stock")
    public void testRedistributionWorkflow() throws SQLException {
        List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();
        assertFalse(recipients.isEmpty(), "Recipients list should not be empty");

        FoodItem targetItem = foodItemService.createFoodItem(
                new FoodItem(null, "Test Redist Item " + System.currentTimeMillis(), "Dairy", new BigDecimal("30.00"), "kg", new BigDecimal("5000.00"), LocalDate.now().plusDays(2), new BigDecimal("5.00")), 1L
        );
        Long itemId = targetItem.getId();
        BigDecimal initialQty = targetItem.getQuantity();

        Redistribution dispatch = new Redistribution();
        dispatch.setFoodItemId(itemId);
        dispatch.setRecipientId(recipients.get(0).getId());
        dispatch.setQuantity(new BigDecimal("5.00"));
        dispatch.setUnit(targetItem.getUnit() != null ? targetItem.getUnit() : "kg");
        dispatch.setPickupTime(LocalDateTime.now().plusDays(1));
        dispatch.setNotes("Donation test");

        Redistribution scheduled = redistributionService.scheduleDispatch(dispatch, 1L);
        assertNotNull(scheduled.getId());

        // Stock deduction check
        Optional<FoodItem> itemAfter = foodItemService.getFoodItemById(itemId);
        assertTrue(itemAfter.isPresent());
        assertEquals(0, initialQty.subtract(new BigDecimal("5.00")).compareTo(itemAfter.get().getQuantity()));

        // Mark collected
        boolean collected = redistributionService.updateDispatchStatus(scheduled.getId(), Redistribution.Status.COLLECTED);
        assertTrue(collected);
    }
}
