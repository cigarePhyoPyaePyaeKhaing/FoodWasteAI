package com.foodwasteai.service;

/**
 * Backward-compatible adapter delegating to GroqAIService.
 */
public class OllamaAIService extends GroqAIService {
    public OllamaAIService() {
        super();
    }

    public OllamaAIService(PredictionService predictionService, FoodItemService foodItemService,
                           RecommendationService recommendationService, RedistributionService redistributionService) {
        super(predictionService, foodItemService, recommendationService, redistributionService);
    }
}
