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
        boolean authenticated = session != null && session.getAttribute("user_id") != null;

        if (!authenticated) {
            // Protected API calls receive JSON 401 Unauthorized
            if (path.startsWith("/api/")) {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.setContentType("application/json;charset=UTF-8");
                resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
                resp.getWriter().print("{\"success\":false,\"message\":\"Authentication required. Please log in.\"}");
                resp.getWriter().flush();
                return;
            }

            // Protected HTML pages redirect to /login.html
            resp.sendRedirect("/login.html");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isPublicPath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path) || "/index.html".equals(path)) {
            return true;
        }
        if (path.equals("/login.html") || path.equals("/login") ||
            path.equals("/register.html") || path.equals("/register")) {
            return true;
        }
        if (path.startsWith("/api/auth/") || path.equals("/api/health") || path.equals("/api/version") || path.equals("/logout")) {
            return true;
        }
        if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/img/") ||
            path.endsWith(".ico") || path.endsWith(".svg") || path.endsWith(".png") ||
            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".css") ||
            path.endsWith(".js") || path.endsWith(".woff2") || path.endsWith(".woff") || path.endsWith(".ttf")) {
            return true;
        }
        return false;
    }
}
