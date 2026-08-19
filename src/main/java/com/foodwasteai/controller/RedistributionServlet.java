package com.foodwasteai.controller;

import com.foodwasteai.model.Redistribution;
import com.foodwasteai.model.RedistributionRecipient;
import com.foodwasteai.service.RedistributionService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST API Controller for Surplus Food Redistribution and Charity Dispatches.
 * Endpoints:
 *   GET    /api/redistribution
 *   GET    /api/redistribution/recipients
 *   POST   /api/redistribution
 *   PUT    /api/redistribution/{id}
 */
@WebServlet(name = "RedistributionServlet", urlPatterns = {"/api/redistribution", "/api/redistribution/*"})
public class RedistributionServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private final RedistributionService redistributionService = new RedistributionService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String path = req.getPathInfo();
            if (path != null && path.contains("recipients")) {
                List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();
                sendSuccess(resp, recipients);
                return;
            }
            if (path != null && path.contains("stats")) {
                Map<String, Object> stats = redistributionService.getRedistributionStats();
                sendSuccess(resp, stats);
                return;
            }

            List<Redistribution> dispatches = redistributionService.getAllDispatches();
            sendSuccess(resp, dispatches);
        } catch (Exception e) {
            logger.error("Error in RedistributionServlet GET: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to retrieve redistribution dispatches: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Redistribution dispatch = parseJsonBody(req, Redistribution.class);
            if (dispatch == null) {
                sendBadRequest(resp, "Invalid JSON payload for redistribution dispatch");
                return;
            }

            Redistribution saved = redistributionService.scheduleDispatch(dispatch, 1L);
            sendCreated(resp, "Surplus food dispatch scheduled successfully", saved);
        } catch (IllegalArgumentException e) {
            sendBadRequest(resp, e.getMessage());
        } catch (Exception e) {
            logger.error("Error in RedistributionServlet POST: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to schedule redistribution dispatch: " + e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Long id = parseIdFromPath(req);
            if (id == null) {
                sendBadRequest(resp, "Dispatch ID is required in URL path");
                return;
            }

            Redistribution payload = parseJsonBody(req, Redistribution.class);
            Redistribution.Status newStatus = payload != null && payload.getStatus() != null ?
                    payload.getStatus() : Redistribution.Status.COLLECTED;

            boolean updated = redistributionService.updateDispatchStatus(id, newStatus);
            if (updated) {
                sendSuccess(resp, "Redistribution dispatch #" + id + " marked as " + newStatus, null);
            } else {
                sendNotFound(resp, "Dispatch #" + id + " not found");
            }
        } catch (Exception e) {
            logger.error("Error in RedistributionServlet PUT: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to update redistribution dispatch: " + e.getMessage());
        }
    }
}
