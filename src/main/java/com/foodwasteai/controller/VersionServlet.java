package com.foodwasteai.controller;

import com.foodwasteai.config.AppConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Version and build verification endpoint.
 * GET /api/version
 */
@WebServlet(name = "VersionServlet", urlPatterns = {"/api/version"})
public class VersionServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    public static final String BUILD_COMMIT = "37fa554 - fix(auth): restore production DB config and prevent login redirect loops";
    public static final String BUILD_VERSION = "1.0.0-PROD";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> versionInfo = new LinkedHashMap<>();
        versionInfo.put("commit", BUILD_COMMIT);
        versionInfo.put("version", BUILD_VERSION);
        versionInfo.put("buildTime", Instant.now().toString());
        versionInfo.put("environment", AppConfig.getAppEnv());
        versionInfo.put("databaseHost", AppConfig.getDbHost());
        versionInfo.put("databasePort", AppConfig.getDbPort());
        versionInfo.put("databaseName", AppConfig.getDbName());
        versionInfo.put("protectedPageArchitecture", "WEB-INF/protected + ProtectedPageServlet");
        versionInfo.put("status", "ACTIVE");

        sendSuccess(resp, "Version and build diagnostic information", versionInfo);
    }
}
