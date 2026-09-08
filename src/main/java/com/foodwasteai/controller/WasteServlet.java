package com.foodwasteai.controller;

import com.foodwasteai.model.WasteRecord;
import com.foodwasteai.service.WasteService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * REST API Controller for Food Waste logging and financial loss analytics.
 * Endpoints:
 *   GET    /api/waste
 *   GET    /api/waste/{id}
 *   POST   /api/waste
 *   DELETE /api/waste/{id}
 */
@WebServlet(name = "WasteServlet", urlPatterns = {"/api/waste", "/api/waste/*"})
public class WasteServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private final WasteService wasteService = new WasteService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Long userId = getAuthenticatedUserId(req);
            Long id = parseIdFromPath(req);
            if (id != null) {
                Optional<WasteRecord> wasteOpt = wasteService.getWasteRecordById(id, userId);
                if (wasteOpt.isPresent()) {
                    sendSuccess(resp, wasteOpt.get());
                } else {
                    sendNotFound(resp, "Waste record #" + id + " not found");
                }
                return;
            }

            String foodItemIdParam = req.getParameter("foodItemId");
            String startDateParam = req.getParameter("startDate");
            String endDateParam = req.getParameter("endDate");

            List<WasteRecord> records;
            if (foodItemIdParam != null && !foodItemIdParam.trim().isEmpty()) {
                Long foodId = Long.parseLong(foodItemIdParam.trim());
                records = wasteService.getWasteByFoodItemId(foodId, userId);
            } else if (startDateParam != null && endDateParam != null) {
                LocalDate start = LocalDate.parse(startDateParam);
                LocalDate end = LocalDate.parse(endDateParam);
                records = wasteService.getWasteByDateRange(start, end, userId);
            } else {
                records = wasteService.getAllWasteRecords(userId);
            }

            sendSuccess(resp, records);
        } catch (Exception e) {
            logger.error("Error in WasteServlet GET: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to retrieve waste records: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            WasteRecord record = parseJsonBody(req, WasteRecord.class);
            if (record == null) {
                sendBadRequest(resp, "Invalid JSON payload for waste record");
                return;
            }

            Long userId = getAuthenticatedUserId(req);
            if (record.getFoodItemId() != null && new com.foodwasteai.service.FoodItemService().getFoodItemById(record.getFoodItemId(), userId).isEmpty()) {
                sendNotFound(resp, "Food item not found"); return;
            }
            WasteRecord saved = wasteService.recordWaste(record, userId);
            sendCreated(resp, "Waste recorded successfully and stock adjusted", saved);
        } catch (IllegalArgumentException e) {
            sendBadRequest(resp, e.getMessage());
        } catch (Exception e) {
            logger.error("Error in WasteServlet POST: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to log waste record: " + e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Long id = parseIdFromPath(req);
            if (id == null) {
                sendBadRequest(resp, "Waste record ID is required in URL path");
                return;
            }

            Long userId = getAuthenticatedUserId(req);
            boolean deleted = wasteService.deleteWasteRecord(id, userId);
            if (deleted) {
                sendSuccess(resp, "Waste record #" + id + " deleted successfully", null);
            } else {
                sendNotFound(resp, "Waste record #" + id + " not found or already deleted");
            }
        } catch (Exception e) {
            logger.error("Error in WasteServlet DELETE: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to delete waste record: " + e.getMessage());
        }
    }
}
