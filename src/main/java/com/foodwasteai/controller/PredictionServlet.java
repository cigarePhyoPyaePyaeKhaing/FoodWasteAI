package com.foodwasteai.controller;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.PredictionService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API Controller for SWI-Prolog Explainable AI Predictions & Expert Reasoning.
 * Endpoints:
 *   GET    /api/prediction
 *   GET    /api/prediction/{foodId}
 *   GET    /api/prediction?tomorrow=true or /api/prediction/tomorrow
 *   POST   /api/prediction/evaluate
 */
@WebServlet(name = "PredictionServlet", urlPatterns = {"/api/prediction", "/api/prediction/*"})
public class PredictionServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private final PredictionService predictionService = new PredictionService();
    private final FoodItemService foodItemService = new FoodItemService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String pathInfo = req.getPathInfo();
            String tomorrowParam = req.getParameter("tomorrow");
            if ("true".equalsIgnoreCase(tomorrowParam) || (pathInfo != null && pathInfo.equalsIgnoreCase("/tomorrow"))) {
                List<FoodItem> currentInventory = foodItemService.getAllFoodItems();
                List<Map<String, Object>> tomorrowBatches = predictionService.assessTomorrowBatches(currentInventory);
                sendSuccess(resp, tomorrowBatches);
                return;
            }

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

            // Return full inventory prediction evaluation (latest from DB, or fresh if requested)
            String refresh = req.getParameter("refresh");
            String evaluate = req.getParameter("evaluate");
            Map<String, Object> report;
            if ("true".equalsIgnoreCase(refresh) || "true".equalsIgnoreCase(evaluate)) {
                report = predictionService.assessAllInventory();
            } else {
                report = predictionService.getLatestPredictionReport();
            }
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
