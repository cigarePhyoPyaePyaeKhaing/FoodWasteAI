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
        List<Recommendation> all = recommendationService.getAllRecommendations();
        assertFalse(all.isEmpty(), "Recommendations should not be empty");

        List<Recommendation> urgent = recommendationService.getRecommendationsByCategory(Recommendation.Category.URGENT);
        assertFalse(urgent.isEmpty());
        assertEquals(Recommendation.Category.URGENT, urgent.get(0).getCategory());

        // Test status update (Accept / Dismiss)
        boolean accepted = recommendationService.updateRecommendationStatus(1L, Recommendation.Status.ACCEPTED);
        assertTrue(accepted);

        List<Recommendation> acceptedList = recommendationService.getRecommendationsByStatus(Recommendation.Status.ACCEPTED);
        assertTrue(acceptedList.stream().anyMatch(r -> r.getId().equals(1L)));
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

        // Initial bread quantity (item #6 starts at 25 units)
        Optional<FoodItem> breadBefore = foodItemService.getFoodItemById(6L);
        assertTrue(breadBefore.isPresent());
        BigDecimal initialQty = breadBefore.get().getQuantity();

        Redistribution dispatch = new Redistribution();
        dispatch.setFoodItemId(6L);
        dispatch.setRecipientId(recipients.get(0).getId());
        dispatch.setQuantity(new BigDecimal("5.00"));
        dispatch.setUnit("units");
        dispatch.setPickupTime(LocalDateTime.now().plusDays(1));
        dispatch.setNotes("Donation test");

        Redistribution scheduled = redistributionService.scheduleDispatch(dispatch, 1L);
        assertNotNull(scheduled.getId());

        // Stock deduction check
        Optional<FoodItem> breadAfter = foodItemService.getFoodItemById(6L);
        assertTrue(breadAfter.isPresent());
        assertEquals(0, initialQty.subtract(new BigDecimal("5.00")).compareTo(breadAfter.get().getQuantity()));

        // Mark collected
        boolean collected = redistributionService.updateDispatchStatus(scheduled.getId(), Redistribution.Status.COLLECTED);
        assertTrue(collected);
    }
}
