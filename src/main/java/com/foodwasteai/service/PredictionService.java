package com.foodwasteai.service;

import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.prolog.PrologService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service orchestrating AI waste prediction and expert system rule evaluation.
 * Architecture: Controller -> Service -> Prolog Service -> SWI-Prolog -> Result -> Java -> JSON
 */
public class PredictionService {
    private static final Logger logger = LoggerFactory.getLogger(PredictionService.class);
    private final PrologService prologService;

    public PredictionService() {
        this.prologService = new PrologService();
    }

    public PredictionService(PrologService prologService) {
        this.prologService = prologService;
    }

    /**
     * Assesses a food item for waste risk, production recommendations, and redistribution suitability.
     */
    public PrologAssessment assessFoodItem(String foodName, double stock, double expectedDemand,
                                          int expiryDays, double histWasteRate, double currentProduction) {
        logger.info("Evaluating item '{}' via PrologService (stock={}, demand={}, expiryDays={})",
                foodName, stock, expectedDemand, expiryDays);
        return prologService.assessFoodItem(foodName, stock, expectedDemand, expiryDays, histWasteRate, currentProduction);
    }
}
