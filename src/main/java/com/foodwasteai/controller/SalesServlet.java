package com.foodwasteai.controller;

import com.foodwasteai.model.Sale;
import com.foodwasteai.service.SalesService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * REST API Controller for Sales recording and demand analytics.
 * Endpoints:
 *   GET    /api/sales
 *   GET    /api/sales/{id}
 *   POST   /api/sales
 *   DELETE /api/sales/{id}
 */
@WebServlet(name = "SalesServlet", urlPatterns = {"/api/sales", "/api/sales/*"})
public class SalesServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private final SalesService salesService = new SalesService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Long id = parseIdFromPath(req);
            if (id != null) {
                Optional<Sale> saleOpt = salesService.getSaleById(id);
                if (saleOpt.isPresent()) {
                    sendSuccess(resp, saleOpt.get());
                } else {
                    sendNotFound(resp, "Sale record #" + id + " not found");
                }
                return;
            }

            String foodItemIdParam = req.getParameter("foodItemId");
            String startDateParam = req.getParameter("startDate");
            String endDateParam = req.getParameter("endDate");

            List<Sale> sales;
            if (foodItemIdParam != null && !foodItemIdParam.trim().isEmpty()) {
                Long foodId = Long.parseLong(foodItemIdParam.trim());
                sales = salesService.getSalesByFoodItemId(foodId);
            } else if (startDateParam != null && endDateParam != null) {
                LocalDate start = LocalDate.parse(startDateParam);
                LocalDate end = LocalDate.parse(endDateParam);
                sales = salesService.getSalesByDateRange(start, end);
            } else {
                sales = salesService.getAllSales();
            }

            sendSuccess(resp, sales);
        } catch (Exception e) {
            logger.error("Error in SalesServlet GET: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to retrieve sales records: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Sale sale = parseJsonBody(req, Sale.class);
            if (sale == null) {
                sendBadRequest(resp, "Invalid JSON payload for sale record");
                return;
            }

            Sale saved = salesService.recordSale(sale, 1L);
            sendCreated(resp, "Sale recorded successfully and stock updated", saved);
        } catch (IllegalArgumentException e) {
            sendBadRequest(resp, e.getMessage());
        } catch (Exception e) {
            logger.error("Error in SalesServlet POST: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to record sale: " + e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Long id = parseIdFromPath(req);
            if (id == null) {
                sendBadRequest(resp, "Sale ID is required in URL path");
                return;
            }

            boolean deleted = salesService.deleteSale(id);
            if (deleted) {
                sendSuccess(resp, "Sale record #" + id + " deleted successfully", null);
            } else {
                sendNotFound(resp, "Sale record #" + id + " not found or already deleted");
            }
        } catch (Exception e) {
            logger.error("Error in SalesServlet DELETE: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to delete sale: " + e.getMessage());
        }
    }
}
