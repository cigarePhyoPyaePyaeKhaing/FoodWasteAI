package com.foodwasteai.controller;

import com.foodwasteai.config.AppConfig;
import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.prolog.PrologService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check and system diagnostic endpoint.
 * GET /api/health
 */
@WebServlet(name = "HealthCheckServlet", urlPatterns = {"/api/health"})
public class HealthCheckServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("service", "FoodWaste AI - Backend API");
        health.put("version", "1.0.0");
        health.put("timestamp", Instant.now().toString());
        health.put("environment", AppConfig.getAppEnv());

        // Database status
        Map<String, Object> dbStatus = new LinkedHashMap<>();
        boolean dbConnected = DatabaseConfig.isAvailable();
        dbStatus.put("connected", dbConnected);
        dbStatus.put("host", AppConfig.getDbHost());
        dbStatus.put("port", AppConfig.getDbPort());
        dbStatus.put("database", AppConfig.getDbName());
        health.put("database", dbStatus);

        // Prolog status
        Map<String, Object> prologStatus = new LinkedHashMap<>();
        boolean prologInstalled = PrologService.isPrologAvailable();
        prologStatus.put("available", prologInstalled);
        prologStatus.put("binaryPath", AppConfig.getSwiplPath());
        prologStatus.put("mode", prologInstalled ? "SWI_PROLOG_ENGINE" : "DEVELOPMENT_FALLBACK");
        health.put("prolog", prologStatus);

        sendSuccess(resp, "FoodWaste AI Service is operational", health);
    }
}
