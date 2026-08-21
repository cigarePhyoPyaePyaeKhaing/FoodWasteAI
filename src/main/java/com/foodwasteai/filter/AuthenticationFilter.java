package com.foodwasteai.filter;

import com.foodwasteai.model.ApiResponse;
import com.foodwasteai.model.User;
import com.foodwasteai.service.AuthService;
import com.google.gson.Gson;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

/**
 * Security & Role-Based Authorization Filter.
 * Enforces session verification and role permissions on all protected pages and API endpoints.
 */
@WebFilter(filterName = "AuthenticationFilter", urlPatterns = {"/*"})
public class AuthenticationFilter implements Filter {
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationFilter.class);
    private final AuthService authService = new AuthService();
    private final Gson gson = new Gson();

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String uri = httpRequest.getRequestURI();
        String contextPath = httpRequest.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;

        // 1. Allow public static web assets and public endpoints
        if (isPublicRoute(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 2. Extract token from Authorization header, Cookie, or parameter
        String token = extractToken(httpRequest);
        Optional<User> userOpt = authService.validateToken(token);

        // 3. Handle Protected HTML Pages
        if (isProtectedHtmlPage(path)) {
            applyNoCacheHeaders(httpResponse);

            if (userOpt.isEmpty()) {
                logger.warn("Unauthenticated direct access attempt to protected page: {}", path);
                String accept = httpRequest.getHeader("Accept");
                if (accept != null && accept.contains("application/json") && !accept.contains("text/html")) {
                    sendUnauthorizedError(httpResponse, "Authentication required to access " + path);
                } else {
                    httpResponse.sendRedirect(contextPath + "/index.html");
                }
                return;
            }

            User user = userOpt.get();

            // Check ADMIN-only pages (e.g. /users.html)
            if (isAdminOnlyPage(path) && user.getRole() != User.Role.ADMIN) {
                logger.warn("Forbidden page access attempt by user '{}' (role: {}) to admin page: {}",
                        user.getUsername(), user.getRole(), path);
                httpResponse.sendRedirect(contextPath + "/dashboard.html");
                return;
            }

            httpRequest.setAttribute("currentUser", user);
            chain.doFilter(request, response);
            return;
        }

        // 4. Handle Protected API Endpoints (/api/*)
        if (path.startsWith("/api/")) {
            applyNoCacheHeaders(httpResponse);

            if (userOpt.isEmpty()) {
                logger.warn("Unauthenticated access attempt to API endpoint: {}", path);
                sendUnauthorizedError(httpResponse, "Authentication required to access " + path);
                return;
            }

            User user = userOpt.get();

            // Check Admin-only API endpoints (/api/users/*)
            if (path.startsWith("/api/users")) {
                if (user.getRole() != User.Role.ADMIN) {
                    logger.warn("Forbidden API access attempt by user '{}' (role: {}) to admin endpoint: {}",
                            user.getUsername(), user.getRole(), path);
                    sendForbiddenError(httpResponse, "Forbidden: ADMIN privileges required");
                    return;
                }
            }

            httpRequest.setAttribute("currentUser", user);
            chain.doFilter(request, response);
            return;
        }

        // Default: allow remaining requests
        chain.doFilter(request, response);
    }

    /**
     * Checks if route is completely public.
     * Public routes: /, /index.html, /index.htm, /api/health, /api/auth/login, and static resources (CSS, JS, images, fonts).
     */
    private boolean isPublicRoute(String path) {
        if (path == null || path.isEmpty() || "/".equals(path) || "/index.html".equals(path) || "/index.htm".equals(path)) {
            return true;
        }

        // Public APIs
        if ("/api/health".equals(path) || "/api/auth/login".equals(path)) {
            return true;
        }

        // Static CSS / JS / Images / Fonts
        if (path.startsWith("/css/") || path.startsWith("/js/")) {
            return true;
        }

        // Static asset file extensions
        String lower = path.toLowerCase();
        return lower.endsWith(".css") ||
               lower.endsWith(".js") ||
               lower.endsWith(".mjs") ||
               lower.endsWith(".png") ||
               lower.endsWith(".jpg") ||
               lower.endsWith(".jpeg") ||
               lower.endsWith(".svg") ||
               lower.endsWith(".gif") ||
               lower.endsWith(".ico") ||
               lower.endsWith(".webp") ||
               lower.endsWith(".woff") ||
               lower.endsWith(".woff2") ||
               lower.endsWith(".ttf") ||
               lower.endsWith(".otf") ||
               lower.endsWith(".eot") ||
               lower.endsWith(".map");
    }

    /**
     * Checks if route is a protected HTML page.
     */
    private boolean isProtectedHtmlPage(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        if (lower.equals("/index.html") || lower.equals("/index.htm") || lower.equals("/")) {
            return false;
        }
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    /**
     * Checks if page requires ADMIN role.
     */
    private boolean isAdminOnlyPage(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.equals("/users.html") || lower.endsWith("/users.html");
    }

    /**
     * Extracts token from Authorization header (Bearer ...), Cookies, or Query Param.
     */
    private String extractToken(HttpServletRequest req) {
        String authHeader = req.getHeader("Authorization");
        if (authHeader != null && !authHeader.trim().isEmpty()) {
            return authHeader;
        }

        if (req.getCookies() != null) {
            for (Cookie c : req.getCookies()) {
                if ("foodwaste_session".equals(c.getName()) || "token".equals(c.getName())) {
                    return c.getValue();
                }
            }
        }

        return req.getParameter("token");
    }

    private void applyNoCacheHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }

    private void sendUnauthorizedError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(gson.toJson(ApiResponse.error(message)));
    }

    private void sendForbiddenError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(gson.toJson(ApiResponse.error(message)));
    }

    @Override
    public void destroy() {}
}
