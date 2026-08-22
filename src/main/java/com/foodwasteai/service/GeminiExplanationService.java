package com.foodwasteai.service;

/**
 * Backward-compatible adapter delegating to OllamaAIService.
 */
public class GeminiExplanationService extends OllamaAIService {
    public GeminiExplanationService() {
        super();
    }

    public GeminiExplanationService(PredictionService predictionService, FoodItemService foodItemService,
                                  RecommendationService recommendationService, RedistributionService redistributionService) {
        super(predictionService, foodItemService, recommendationService, redistributionService);
    }
}
