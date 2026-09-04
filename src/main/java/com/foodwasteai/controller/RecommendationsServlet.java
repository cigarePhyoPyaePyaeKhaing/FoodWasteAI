package com.foodwasteai.controller;

import com.foodwasteai.model.Recommendation;
import com.foodwasteai.service.RecommendationService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * REST API Controller for AI Actionable Mitigation Directives.
 * Endpoints:
 *   GET    /api/recommendations
 *   GET    /api/recommendations/{id}
 *   PUT    /api/recommendations/{id}
 *   POST   /api/recommendations/generate
 */
@WebServlet(name = "RecommendationsServlet", urlPatterns = {"/api/recommendations", "/api/recommendations/*"})
public class RecommendationsServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private final RecommendationService recommendationService = new RecommendationService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String categoryParam = req.getParameter("category");
            String statusParam = req.getParameter("status");

            List<Recommendation> list;
            if (categoryParam != null && !categoryParam.trim().isEmpty() && !categoryParam.equalsIgnoreCase("ALL")) {
                Recommendation.Category cat = Recommendation.Category.valueOf(categoryParam.trim().toUpperCase());
                list = recommendationService.getRecommendationsByCategory(cat);
            } else if (statusParam != null && !statusParam.trim().isEmpty()) {
                Recommendation.Status st = Recommendation.Status.valueOf(statusParam.trim().toUpperCase());
                list = recommendationService.getRecommendationsByStatus(st);
            } else {
                list = recommendationService.getAllRecommendations();
            }

            sendSuccess(resp, list);
        } catch (IllegalArgumentException e) {
            sendBadRequest(resp, "Invalid category or status parameter");
        } catch (Exception e) {
            logger.error("Error in RecommendationsServlet GET: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to retrieve recommendations: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String path = req.getPathInfo();
            if (path != null && path.contains("generate")) {
                List<Recommendation> generated = recommendationService.generateRecommendationsFromProlog();
                sendSuccess(resp, "Generated fresh recommendations from SWI-Prolog expert reasoning", generated);
                return;
            }
            sendBadRequest(resp, "Invalid endpoint path. Use POST /api/recommendations/generate");
        } catch (Exception e) {
            logger.error("Error in RecommendationsServlet POST: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to generate recommendations: " + e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Long id = parseIdFromPath(req);
            if (id == null) {
                sendBadRequest(resp, "Recommendation ID is required in URL path");
                return;
            }

            Recommendation payload = parseJsonBody(req, Recommendation.class);
            Recommendation.Status newStatus = payload != null && payload.getStatus() != null ?
                    payload.getStatus() : Recommendation.Status.ACCEPTED;

            boolean updated = recommendationService.updateRecommendationStatus(id, newStatus);
            if (updated) {
                sendSuccess(resp, "Recommendation #" + id + " marked as " + newStatus, null);
            } else {
                sendNotFound(resp, "Recommendation #" + id + " not found");
            }
        } catch (Exception e) {
            logger.error("Error in RecommendationsServlet PUT: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to update recommendation: " + e.getMessage());
        }
    }
}
