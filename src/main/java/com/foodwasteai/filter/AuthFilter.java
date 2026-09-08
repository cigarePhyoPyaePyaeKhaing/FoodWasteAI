package com.foodwasteai.filter;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Filter ensuring that only authenticated sessions can access protected REST APIs
 * and protected application views.
 */
@WebFilter(filterName = "AuthFilter", urlPatterns = {"/*"})
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String path = req.getRequestURI();
        String contextPath = req.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }

        // 1. Allow public static assets and auth/health endpoints
        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 2. Check authenticated session
        HttpSession session = req.getSession(false);
        boolean authenticated = session != null && session.getAttribute("user_id") instanceof Long
                && (Long) session.getAttribute("user_id") > 0;
        if (authenticated) {
            try {
                authenticated = new com.foodwasteai.service.UserService()
                        .findById((Long) session.getAttribute("user_id"))
                        .filter(com.foodwasteai.model.User::isActive).isPresent();
                if (!authenticated) session.invalidate();
            } catch (java.sql.SQLException | IllegalStateException exception) {
                resp.setStatus(503);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().print("{\"success\":false,\"message\":\"Unable to complete the request. Please try again.\"}");
                return;
            }
        }

        if (!authenticated) {
            // Protected API calls receive JSON 401 Unauthorized
            if (path.startsWith("/api/")) {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.setContentType("application/json;charset=UTF-8");
                resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
                resp.getWriter().print("{\"success\":false,\"error\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Please sign in to continue.\"}");
                resp.getWriter().flush();
                return;
            }

            // Protected HTML pages redirect to /login.html
            resp.sendRedirect("/login.html");
            return;
        }

        resp.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    private boolean isPublicPath(String path) {
        if (path.equals("/login.html") || path.equals("/login") ||
            path.equals("/register.html") || path.equals("/register")) {
            return true;
        }
        if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) {
            return true;
        }
        if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/img/") || path.startsWith("/fonts/") || path.equals("/favicon.ico")) {
            return true;
        }
        return false;
    }
}
