package com.foodwasteai;

import com.foodwasteai.config.AppConfig;
import com.foodwasteai.controller.*;
import com.foodwasteai.filter.AuthenticationFilter;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Main Entry Point for FoodWaste AI standalone application.
 * Launches Embedded Tomcat, binds to PORT (or default 8088),
 * serves static frontend assets with correct MIME types, and mounts REST endpoints.
 */
public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        int port = AppConfig.getPort();
        logger.info("Starting FoodWaste AI application on port {}", port);

        Tomcat tomcat = createServer(port);

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

    public static Tomcat createServer(int port) {
        Tomcat tomcat = new Tomcat();
        String baseDir = new File(System.getProperty("java.io.tmpdir"), "tomcat." + port).getAbsolutePath();
        tomcat.setBaseDir(baseDir);
        tomcat.setPort(port);
        tomcat.getConnector().setPort(port);

        File docBaseDir = resolveDocBase();
        String docBase = docBaseDir.getAbsolutePath();
        logger.info("Serving webapp from docBase: {}", docBase);

        Context ctx = tomcat.addContext("", docBase);

        // Add DefaultServlet for static file serving (HTML, CSS, JS, Images, Fonts)
        Tomcat.addServlet(ctx, "default", new DefaultServlet());
        ctx.addServletMappingDecoded("/", "default");

        // Welcome files
        ctx.addWelcomeFile("index.html");
        ctx.addWelcomeFile("index.htm");

        // Explicitly register standard web MIME mappings so browsers apply styles and scripts correctly
        configureMimeMappings(ctx);

        // Register CORS Filter
        FilterDef corsFilterDef = new FilterDef();
        corsFilterDef.setFilterName("CorsFilter");
        corsFilterDef.setFilterClass(CorsFilter.class.getName());
        ctx.addFilterDef(corsFilterDef);

        FilterMap corsFilterMap = new FilterMap();
        corsFilterMap.setFilterName("CorsFilter");
        corsFilterMap.addURLPattern("/*");
        ctx.addFilterMap(corsFilterMap);

        // Register Security Authentication Filter (scoped strictly to /api/*)
        FilterDef authFilterDef = new FilterDef();
        authFilterDef.setFilterName("AuthenticationFilter");
        authFilterDef.setFilterClass(AuthenticationFilter.class.getName());
        ctx.addFilterDef(authFilterDef);

        FilterMap authFilterMap = new FilterMap();
        authFilterMap.setFilterName("AuthenticationFilter");
        authFilterMap.addURLPattern("/api/*");
        ctx.addFilterMap(authFilterMap);

        // Register Core API Servlets
        registerServlets(ctx);

        return tomcat;
    }

    public static void configureMimeMappings(Context ctx) {
        // CSS Stylesheets (critical for modern browser strict MIME checking)
        ctx.addMimeMapping("css", "text/css;charset=UTF-8");

        // JavaScript
        ctx.addMimeMapping("js", "application/javascript;charset=UTF-8");
        ctx.addMimeMapping("mjs", "application/javascript;charset=UTF-8");

        // Data / JSON / XML
        ctx.addMimeMapping("json", "application/json;charset=UTF-8");
        ctx.addMimeMapping("map", "application/json;charset=UTF-8");
        ctx.addMimeMapping("xml", "application/xml;charset=UTF-8");

        // HTML & Plain Text
        ctx.addMimeMapping("html", "text/html;charset=UTF-8");
        ctx.addMimeMapping("htm", "text/html;charset=UTF-8");
        ctx.addMimeMapping("txt", "text/plain;charset=UTF-8");

        // Images
        ctx.addMimeMapping("svg", "image/svg+xml");
        ctx.addMimeMapping("png", "image/png");
        ctx.addMimeMapping("jpg", "image/jpeg");
        ctx.addMimeMapping("jpeg", "image/jpeg");
        ctx.addMimeMapping("gif", "image/gif");
        ctx.addMimeMapping("ico", "image/x-icon");
        ctx.addMimeMapping("webp", "image/webp");

        // Fonts
        ctx.addMimeMapping("woff", "font/woff");
        ctx.addMimeMapping("woff2", "font/woff2");
        ctx.addMimeMapping("ttf", "font/ttf");
        ctx.addMimeMapping("otf", "font/otf");
        ctx.addMimeMapping("eot", "application/vnd.ms-fontobject");
    }

    public static File resolveDocBase() {
        // 1. Check src/main/webapp (local dev or standard container build)
        File f = new File("src/main/webapp");
        if (f.exists() && f.isDirectory() && new File(f, "index.html").exists()) {
            return f;
        }

        // 2. Check ./webapp
        f = new File("webapp");
        if (f.exists() && f.isDirectory() && new File(f, "index.html").exists()) {
            return f;
        }

        // 3. Check target/classes/static
        f = new File("target/classes/static");
        if (f.exists() && f.isDirectory() && new File(f, "index.html").exists()) {
            return f;
        }

        // 4. Check current directory
        f = new File(".");
        if (new File(f, "index.html").exists()) {
            return f;
        }

        // 5. Unpack classpath static assets if running in standalone JAR without local filesystem sources
        return extractStaticResourcesToTemp();
    }

    private static File extractStaticResourcesToTemp() {
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "foodwasteai-webapp-" + System.currentTimeMillis());
            tempDir.mkdirs();

            List<String> staticFiles = Arrays.asList(
                "index.html",
                "dashboard.html",
                "inventory.html",
                "prediction.html",
                "recommendations.html",
                "redistribution.html",
                "reports.html",
                "sales.html",
                "settings.html",
                "users.html",
                "waste.html",
                "css/variables.css",
                "css/components.css",
                "css/styles.css",
                "js/api.js",
                "js/auth.js",
                "js/dashboard.js",
                "js/inventory.js",
                "js/prediction.js",
                "js/recommendations.js",
                "js/redistribution.js",
                "js/reports.js",
                "js/sales.js",
                "js/users.js",
                "js/waste.js",
                "js/ai-assistant.js",
                "js/i18n/en.js",
                "js/i18n/mm.js",
                "js/i18n/language.js"
            );

            ClassLoader cl = App.class.getClassLoader();
            for (String relativePath : staticFiles) {
                InputStream is = cl.getResourceAsStream("static/" + relativePath);
                if (is != null) {
                    File dest = new File(tempDir, relativePath);
                    File parent = dest.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (is; OutputStream os = new FileOutputStream(dest)) {
                        is.transferTo(os);
                    }
                }
            }
            return tempDir;
        } catch (Exception e) {
            logger.warn("Failed to extract classpath static resources: {}", e.getMessage());
            return new File(".");
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
        Tomcat.addServlet(ctx, "PredictionServlet", new PredictionServlet());
        ctx.addServletMappingDecoded("/api/prediction", "PredictionServlet");
        ctx.addServletMappingDecoded("/api/prediction/*", "PredictionServlet");

        // Recommendations Servlet
        Tomcat.addServlet(ctx, "RecommendationsServlet", new RecommendationsServlet());
        ctx.addServletMappingDecoded("/api/recommendations", "RecommendationsServlet");
        ctx.addServletMappingDecoded("/api/recommendations/*", "RecommendationsServlet");

        // Redistribution Servlet
        Tomcat.addServlet(ctx, "RedistributionServlet", new RedistributionServlet());
        ctx.addServletMappingDecoded("/api/redistribution", "RedistributionServlet");
        ctx.addServletMappingDecoded("/api/redistribution/*", "RedistributionServlet");

        // Gemini Chat & Explainable AI Servlet
        Tomcat.addServlet(ctx, "ChatServlet", new ChatServlet());
        ctx.addServletMappingDecoded("/api/chat", "ChatServlet");
        ctx.addServletMappingDecoded("/api/chat/*", "ChatServlet");

        // Auth Servlet
        Tomcat.addServlet(ctx, "AuthServlet", new AuthServlet());
        ctx.addServletMappingDecoded("/api/auth", "AuthServlet");
        ctx.addServletMappingDecoded("/api/auth/*", "AuthServlet");

        // Users Servlet (Admin Protected)
        Tomcat.addServlet(ctx, "UsersServlet", new UsersServlet());
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
