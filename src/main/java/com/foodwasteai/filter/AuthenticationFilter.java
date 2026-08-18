package com.foodwasteai.filter;

import com.foodwasteai.model.ApiResponse;
import com.foodwasteai.model.User;
import com.foodwasteai.service.AuthService;
import com.google.gson.Gson;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

/**
 * Security & Role-Based Authorization Filter.
 * Enforces session verification and role permissions on protected API endpoints.
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
        String method = httpRequest.getMethod();

        // 1. Allow static web assets and public API endpoints
        if (isPublicEndpoint(uri, method)) {
            chain.doFilter(request, response);
            return;
        }

        // 2. Validate token from Authorization Header or request parameter
        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || authHeader.trim().isEmpty()) {
            authHeader = httpRequest.getParameter("token");
        }

        Optional<User> userOpt = authService.validateToken(authHeader);

        // 3. Enforce authentication on protected API endpoints
        if (uri.startsWith("/api/")) {
            // Check Admin-only endpoints
            if (uri.startsWith("/api/users")) {
                if (userOpt.isEmpty()) {
                    sendUnauthorizedError(httpResponse, "Authentication required to access user management");
                    return;
                }
                if (userOpt.get().getRole() != User.Role.ADMIN) {
                    sendForbiddenError(httpResponse, "Forbidden: ADMIN privileges required");
                    return;
                }
            }

            // Attach user to request attribute if present
            userOpt.ifPresent(user -> httpRequest.setAttribute("currentUser", user));
        }

        chain.doFilter(request, response);
    }

    private boolean isPublicEndpoint(String uri, String method) {
        // Static assets
        if (!uri.startsWith("/api/")) {
            return true;
        }

        // Health check & Login
        if (uri.equals("/api/health") || uri.startsWith("/api/auth/login")) {
            return true;
        }

        // Chat & General Read APIs
        if (uri.startsWith("/api/chat") || uri.startsWith("/api/prediction")) {
            return true;
        }

        if ("GET".equalsIgnoreCase(method)) {
            return uri.startsWith("/api/inventory") ||
                   uri.startsWith("/api/sales") ||
                   uri.startsWith("/api/waste") ||
                   uri.startsWith("/api/recommendations") ||
                   uri.startsWith("/api/redistribution");
        }

        // Safe operations
        return false;
    }

    private void sendUnauthorizedError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(gson.toJson(ApiResponse.error(message)));
    }

    private void sendForbiddenError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(gson.toJson(ApiResponse.error(message)));
    }

    @Override
    public void destroy() {}
}
