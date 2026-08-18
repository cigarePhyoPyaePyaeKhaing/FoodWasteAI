package com.foodwasteai;

import com.foodwasteai.config.AppConfig;
import com.foodwasteai.controller.HealthCheckServlet;
import com.foodwasteai.controller.InventoryServlet;
import com.foodwasteai.controller.SalesServlet;
import com.foodwasteai.controller.WasteServlet;
import com.foodwasteai.filter.CorsFilter;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Main Entry Point for FoodWaste AI standalone application.
 * Launches Embedded Tomcat, binds to PORT (or default 8088),
 * serves static frontend assets, and mounts REST endpoints.
 */
public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        int port = AppConfig.getPort();
        logger.info("Starting FoodWaste AI application on port {}", port);

        Tomcat tomcat = new Tomcat();
        String baseDir = new File(System.getProperty("java.io.tmpdir"), "tomcat." + port).getAbsolutePath();
        tomcat.setBaseDir(baseDir);
        tomcat.setPort(port);
        tomcat.getConnector().setPort(port);

        // Define docbase: check src/main/webapp first for local dev, or fallback to current dir
        File webappDir = new File("src/main/webapp");
        String docBase = webappDir.exists() ? webappDir.getAbsolutePath() : new File(".").getAbsolutePath();
        logger.info("Serving webapp from docBase: {}", docBase);

        Context ctx = tomcat.addContext("", docBase);

        // Add DefaultServlet for static file serving (HTML, CSS, JS, Images)
        Tomcat.addServlet(ctx, "default", new DefaultServlet());
        ctx.addServletMappingDecoded("/", "default");

        // Add welcome files
        ctx.addWelcomeFile("index.html");

        // Register CORS Filter
        FilterDef corsFilterDef = new FilterDef();
        corsFilterDef.setFilterName("CorsFilter");
        corsFilterDef.setFilterClass(CorsFilter.class.getName());
        ctx.addFilterDef(corsFilterDef);

        FilterMap corsFilterMap = new FilterMap();
        corsFilterMap.setFilterName("CorsFilter");
        corsFilterMap.addURLPattern("/*");
        ctx.addFilterMap(corsFilterMap);

        // Register Security Authentication Filter
        FilterDef authFilterDef = new FilterDef();
        authFilterDef.setFilterName("AuthenticationFilter");
        authFilterDef.setFilterClass(com.foodwasteai.filter.AuthenticationFilter.class.getName());
        ctx.addFilterDef(authFilterDef);

        FilterMap authFilterMap = new FilterMap();
        authFilterMap.setFilterName("AuthenticationFilter");
        authFilterMap.addURLPattern("/api/*");
        ctx.addFilterMap(authFilterMap);

        // Register Core API Servlets
        registerServlets(ctx);

        // Handle Graceful Shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down FoodWaste AI Tomcat server...");
            try {
                tomcat.stop();
                tomcat.destroy();
            } catch (LifecycleException e) {
                logger.error("Error during server shutdown: {}", e.getMessage(), e);
            }
        }));

        try {
            tomcat.start();
            printBanner(port);
            tomcat.getServer().await();
        } catch (LifecycleException e) {
            logger.error("Fatal error starting Tomcat: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void registerServlets(Context ctx) {
        // Health Check Servlet
        Tomcat.addServlet(ctx, "HealthCheckServlet", new HealthCheckServlet());
        ctx.addServletMappingDecoded("/api/health", "HealthCheckServlet");

        // Inventory Servlet
        Tomcat.addServlet(ctx, "InventoryServlet", new InventoryServlet());
        ctx.addServletMappingDecoded("/api/inventory", "InventoryServlet");
        ctx.addServletMappingDecoded("/api/inventory/*", "InventoryServlet");

        // Sales Servlet
        Tomcat.addServlet(ctx, "SalesServlet", new SalesServlet());
        ctx.addServletMappingDecoded("/api/sales", "SalesServlet");
        ctx.addServletMappingDecoded("/api/sales/*", "SalesServlet");

        // Waste Servlet
        Tomcat.addServlet(ctx, "WasteServlet", new WasteServlet());
        ctx.addServletMappingDecoded("/api/waste", "WasteServlet");
        ctx.addServletMappingDecoded("/api/waste/*", "WasteServlet");

        // Prediction & AI Reasoning Servlet
        Tomcat.addServlet(ctx, "PredictionServlet", new com.foodwasteai.controller.PredictionServlet());
        ctx.addServletMappingDecoded("/api/prediction", "PredictionServlet");
        ctx.addServletMappingDecoded("/api/prediction/*", "PredictionServlet");

        // Recommendations Servlet
        Tomcat.addServlet(ctx, "RecommendationsServlet", new com.foodwasteai.controller.RecommendationsServlet());
        ctx.addServletMappingDecoded("/api/recommendations", "RecommendationsServlet");
        ctx.addServletMappingDecoded("/api/recommendations/*", "RecommendationsServlet");

        // Redistribution Servlet
        Tomcat.addServlet(ctx, "RedistributionServlet", new com.foodwasteai.controller.RedistributionServlet());
        ctx.addServletMappingDecoded("/api/redistribution", "RedistributionServlet");
        ctx.addServletMappingDecoded("/api/redistribution/*", "RedistributionServlet");

        // Gemini Chat & Explainable AI Servlet
        Tomcat.addServlet(ctx, "ChatServlet", new com.foodwasteai.controller.ChatServlet());
        ctx.addServletMappingDecoded("/api/chat", "ChatServlet");
        ctx.addServletMappingDecoded("/api/chat/*", "ChatServlet");

        // Auth Servlet
        Tomcat.addServlet(ctx, "AuthServlet", new com.foodwasteai.controller.AuthServlet());
        ctx.addServletMappingDecoded("/api/auth", "AuthServlet");
        ctx.addServletMappingDecoded("/api/auth/*", "AuthServlet");

        // Users Servlet (Admin Protected)
        Tomcat.addServlet(ctx, "UsersServlet", new com.foodwasteai.controller.UsersServlet());
        ctx.addServletMappingDecoded("/api/users", "UsersServlet");
        ctx.addServletMappingDecoded("/api/users/*", "UsersServlet");

        logger.info("Servlets registered: /api/health, /api/auth/*, /api/users/*, /api/inventory/*, /api/sales/*, /api/waste/*, /api/prediction/*, /api/recommendations/*, /api/redistribution/*, /api/chat/*");
    }

    private static void printBanner(int port) {
        System.out.println("==================================================================");
        System.out.println("  FoodWaste AI Server is LIVE!");
        System.out.println("  Tagline: Predict -> Prevent -> Redistribute -> Reduce Waste");
        System.out.println("  URL: http://localhost:" + port);
        System.out.println("  Health Check: http://localhost:" + port + "/api/health");
        System.out.println("  Environment: " + AppConfig.getAppEnv());
        System.out.println("==================================================================");
    }
}
