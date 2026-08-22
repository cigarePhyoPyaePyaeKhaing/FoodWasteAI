package com.foodwasteai.service;

/**
 * Backward-compatible adapter delegating to GroqAIService.
 */
public class GeminiExplanationService extends GroqAIService {
    public GeminiExplanationService() {
        super();
    }

    public GeminiExplanationService(PredictionService predictionService, FoodItemService foodItemService,
                                  RecommendationService recommendationService, RedistributionService redistributionService) {
        super(predictionService, foodItemService, recommendationService, redistributionService);
    }
}
