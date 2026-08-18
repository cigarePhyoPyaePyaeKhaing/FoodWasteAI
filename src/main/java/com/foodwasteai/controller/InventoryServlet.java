package com.foodwasteai.controller;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.service.FoodItemService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST API Controller for Food Inventory CRUD operations.
 * Endpoints:
 *   GET    /api/inventory
 *   GET    /api/inventory/{id}
 *   POST   /api/inventory
 *   PUT    /api/inventory/{id}
 *   DELETE /api/inventory/{id}
 */
@WebServlet(name = "InventoryServlet", urlPatterns = {"/api/inventory", "/api/inventory/*"})
public class InventoryServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private final FoodItemService foodItemService = new FoodItemService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Long id = parseIdFromPath(req);
            if (id != null) {
                Optional<FoodItem> itemOpt = foodItemService.getFoodItemById(id);
                if (itemOpt.isPresent()) {
                    sendSuccess(resp, itemOpt.get());
                } else {
                    sendNotFound(resp, "Food item #" + id + " not found");
                }
                return;
            }

            // Query Filters
            String category = req.getParameter("category");
            String search = req.getParameter("q");
            String nearExpiry = req.getParameter("nearExpiry");
            String lowStock = req.getParameter("lowStock");

            List<FoodItem> items;
            if ("true".equalsIgnoreCase(nearExpiry)) {
                items = foodItemService.getNearExpiryItems(2);
            } else if ("true".equalsIgnoreCase(lowStock)) {
                items = foodItemService.getLowStockItems();
            } else if (category != null && !category.trim().isEmpty()) {
                items = foodItemService.getFoodItemsByCategory(category.trim());
            } else {
                items = foodItemService.getAllFoodItems();
            }

            // Optional keyword search filter
            if (search != null && !search.trim().isEmpty()) {
                String q = search.trim().toLowerCase();
                items = items.stream()
                        .filter(item -> item.getName().toLowerCase().contains(q) ||
                                        item.getCategory().toLowerCase().contains(q))
                        .collect(Collectors.toList());
            }

            sendSuccess(resp, items);
        } catch (Exception e) {
            logger.error("Error in InventoryServlet GET: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to retrieve inventory: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            FoodItem item = parseJsonBody(req, FoodItem.class);
            if (item == null) {
                sendBadRequest(resp, "Invalid JSON payload for food item");
                return;
            }

            FoodItem saved = foodItemService.createFoodItem(item, 1L);
            sendCreated(resp, "Food item created successfully", saved);
        } catch (IllegalArgumentException e) {
            sendBadRequest(resp, e.getMessage());
        } catch (Exception e) {
            logger.error("Error in InventoryServlet POST: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to create food item: " + e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Long pathId = parseIdFromPath(req);
            FoodItem item = parseJsonBody(req, FoodItem.class);
            if (item == null) {
                sendBadRequest(resp, "Invalid JSON payload for food item update");
                return;
            }
            if (pathId != null) {
                item.setId(pathId);
            }
            if (item.getId() == null) {
                sendBadRequest(resp, "Food item ID is required for update");
                return;
            }

            boolean updated = foodItemService.updateFoodItem(item, 1L);
            if (updated) {
                sendSuccess(resp, "Food item updated successfully", item);
            } else {
                sendNotFound(resp, "Food item #" + item.getId() + " not found");
            }
        } catch (IllegalArgumentException e) {
            sendBadRequest(resp, e.getMessage());
        } catch (Exception e) {
            logger.error("Error in InventoryServlet PUT: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to update food item: " + e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Long id = parseIdFromPath(req);
            if (id == null) {
                sendBadRequest(resp, "Food item ID is required in URL path (e.g. /api/inventory/1)");
                return;
            }

            boolean deleted = foodItemService.deleteFoodItem(id);
            if (deleted) {
                sendSuccess(resp, "Food item #" + id + " deleted successfully", null);
            } else {
                sendNotFound(resp, "Food item #" + id + " not found or already deleted");
            }
        } catch (Exception e) {
            logger.error("Error in InventoryServlet DELETE: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to delete food item: " + e.getMessage());
        }
    }
}
