package com.foodwasteai.controller;

import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.service.PredictionService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * REST API Controller for SWI-Prolog Explainable AI Predictions & Expert Reasoning.
 * Endpoints:
 *   GET    /api/prediction
 *   GET    /api/prediction/{foodId}
 *   POST   /api/prediction/evaluate
 */
@WebServlet(name = "PredictionServlet", urlPatterns = {"/api/prediction", "/api/prediction/*"})
public class PredictionServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private final PredictionService predictionService = new PredictionService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Long foodId = parseIdFromPath(req);
            if (foodId != null) {
                Optional<PrologAssessment> assessmentOpt = predictionService.assessFoodItemById(foodId);
                if (assessmentOpt.isPresent()) {
                    sendSuccess(resp, assessmentOpt.get());
                } else {
                    sendNotFound(resp, "Food item #" + foodId + " not found for prediction assessment");
                }
                return;
            }

            // Return full inventory prediction evaluation
            Map<String, Object> report = predictionService.assessAllInventory();
            sendSuccess(resp, report);
        } catch (Exception e) {
            logger.error("Error in PredictionServlet GET: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to run Prolog waste prediction: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            // Run fresh evaluation
            Map<String, Object> report = predictionService.assessAllInventory();
            sendSuccess(resp, "Evaluated inventory via SWI-Prolog expert reasoning system", report);
        } catch (Exception e) {
            logger.error("Error in PredictionServlet POST: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to evaluate predictions: " + e.getMessage());
        }
    }
}
