package com.foodwasteai.controller;

import com.foodwasteai.model.User;
import com.foodwasteai.service.AuthService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Controller serving authenticated HTML pages located under /WEB-INF/protected/.
 * Physical placement under /WEB-INF/ prevents any direct static access or bypass.
 */
@WebServlet(name = "ProtectedPageServlet", urlPatterns = {
        "/dashboard.html", "/dashboard",
        "/inventory.html", "/inventory",
        "/sales.html", "/sales",
        "/waste.html", "/waste",
        "/prediction.html", "/prediction",
        "/recommendations.html", "/recommendations",
        "/redistribution.html", "/redistribution",
        "/reports.html", "/reports",
        "/settings.html", "/settings",
        "/users.html", "/users"
})
public class ProtectedPageServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(ProtectedPageServlet.class);
    private final AuthService authService = new AuthService();

    private static final Map<String, String> ROUTE_TO_FILE = Map.ofEntries(
            Map.entry("/dashboard.html", "dashboard.html"),
            Map.entry("/dashboard", "dashboard.html"),
            Map.entry("/inventory.html", "inventory.html"),
            Map.entry("/inventory", "inventory.html"),
            Map.entry("/sales.html", "sales.html"),
            Map.entry("/sales", "sales.html"),
            Map.entry("/waste.html", "waste.html"),
            Map.entry("/waste", "waste.html"),
            Map.entry("/prediction.html", "prediction.html"),
            Map.entry("/prediction", "prediction.html"),
            Map.entry("/recommendations.html", "recommendations.html"),
            Map.entry("/recommendations", "recommendations.html"),
            Map.entry("/redistribution.html", "redistribution.html"),
            Map.entry("/redistribution", "redistribution.html"),
            Map.entry("/reports.html", "reports.html"),
            Map.entry("/reports", "reports.html"),
            Map.entry("/settings.html", "settings.html"),
            Map.entry("/settings", "settings.html"),
            Map.entry("/users.html", "users.html"),
            Map.entry("/users", "users.html")
    );

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String servletPath = req.getServletPath();
        String targetFile = ROUTE_TO_FILE.get(servletPath);

        if (targetFile == null) {
            String pathInfo = req.getPathInfo();
            if (pathInfo != null) {
                targetFile = ROUTE_TO_FILE.get(pathInfo);
            }
        }

        if (targetFile == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Page not found");
            return;
        }

        // Apply strict no-cache headers
        applyNoCacheHeaders(resp);

        // Authenticate User
        String token = extractToken(req);
        Optional<User> userOpt = authService.validateToken(token);

        if (userOpt.isEmpty()) {
            logger.warn("Unauthenticated direct access attempt to protected page route: {}", servletPath);
            String accept = req.getHeader("Accept");
            if (accept != null && accept.contains("application/json") && !accept.contains("text/html")) {
                sendUnauthorized(resp, "Authentication required to access " + servletPath);
            } else {
                resp.sendRedirect(req.getContextPath() + "/index.html");
            }
            return;
        }

        User user = userOpt.get();

        // RBAC: Check ADMIN-only pages (e.g. /users.html)
        if ("users.html".equals(targetFile) && user.getRole() != User.Role.ADMIN) {
            logger.warn("Access denied for user '{}' (role: {}) to admin-only page: {}",
                    user.getUsername(), user.getRole(), servletPath);
            resp.sendRedirect(req.getContextPath() + "/dashboard.html");
            return;
        }

        // Serve protected HTML content from /WEB-INF/protected/
        serveProtectedHtml(targetFile, req, resp);
    }

    private void serveProtectedHtml(String fileName, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        byte[] content = loadProtectedFileBytes(fileName);
        if (content == null) {
            logger.error("Protected HTML file not found: WEB-INF/protected/{}", fileName);
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Protected view not found");
            return;
        }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html;charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentLength(content.length);
        resp.getOutputStream().write(content);
        resp.getOutputStream().flush();
    }

    private byte[] loadProtectedFileBytes(String fileName) {
        // 1. Try servlet context resource stream
        try (InputStream is = getServletContext().getResourceAsStream("/WEB-INF/protected/" + fileName)) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (Exception ignored) {}

        // 2. Try classpath resource stream
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("static/WEB-INF/protected/" + fileName)) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (Exception ignored) {}

        // 3. Try local development filesystem paths
        File[] candidatePaths = new File[] {
                new File("src/main/webapp/WEB-INF/protected/" + fileName),
                new File("webapp/WEB-INF/protected/" + fileName),
                new File("target/classes/static/WEB-INF/protected/" + fileName)
        };

        for (File candidate : candidatePaths) {
            if (candidate.exists() && candidate.isFile()) {
                try (FileInputStream fis = new FileInputStream(candidate)) {
                    return fis.readAllBytes();
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    private void applyNoCacheHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }
}
