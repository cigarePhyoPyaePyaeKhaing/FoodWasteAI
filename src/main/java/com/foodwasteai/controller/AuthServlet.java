package com.foodwasteai.controller;

import com.foodwasteai.model.User;
import com.foodwasteai.service.AuthService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST API Controller for User Authentication & Session Management.
 * Endpoints:
 *   POST /api/auth/login
 *   POST /api/auth/logout
 *   GET  /api/auth/me
 */
@WebServlet(name = "AuthServlet", urlPatterns = {"/api/auth", "/api/auth/*"})
public class AuthServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private final AuthService authService = new AuthService();

    public static class LoginRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String username;
        private String password;

        public LoginRequest() {}

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path != null && path.contains("me")) {
            String token = extractToken(req);
            Optional<User> userOpt = authService.validateToken(token);
            if (userOpt.isPresent()) {
                User u = userOpt.get();
                Map<String, Object> userData = new LinkedHashMap<>();
                userData.put("id", u.getId());
                userData.put("username", u.getUsername());
                userData.put("fullName", u.getFullName());
                userData.put("email", u.getEmail());
                userData.put("role", u.getRole().name());
                sendSuccess(resp, userData);
            } else {
                sendUnauthorized(resp, "Session expired or invalid token");
            }
            return;
        }
        sendBadRequest(resp, "Invalid auth endpoint");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path != null && path.contains("login")) {
            LoginRequest login = parseJsonBody(req, LoginRequest.class);
            if (login == null || login.getUsername() == null || login.getPassword() == null) {
                sendBadRequest(resp, "Username and password are required");
                return;
            }

            Optional<AuthService.UserSession> sessionOpt = authService.authenticate(login.getUsername(), login.getPassword());
            if (sessionOpt.isPresent()) {
                AuthService.UserSession session = sessionOpt.get();
                User u = session.getUser();

                // Set HttpOnly session cookie for seamless and secure browser navigation
                String cookieHeader = String.format("foodwaste_session=%s; Path=/; Max-Age=%d; HttpOnly; SameSite=Lax",
                        session.getToken(), AuthService.DEFAULT_SESSION_HOURS * 3600);
                if (req.isSecure()) {
                    cookieHeader += "; Secure";
                }
                resp.addHeader("Set-Cookie", cookieHeader);

                Map<String, Object> userDto = new LinkedHashMap<>();
                userDto.put("id", u.getId());
                userDto.put("username", u.getUsername());
                userDto.put("fullName", u.getFullName());
                userDto.put("email", u.getEmail());
                userDto.put("role", u.getRole().name());

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("token", session.getToken());
                data.put("user", userDto);

                sendSuccess(resp, "Sign in successful", data);
            } else {
                sendUnauthorized(resp, "Invalid username or password");
            }
            return;
        }

        if (path != null && path.contains("logout")) {
            String token = extractToken(req);
            if (token != null) {
                authService.logout(token);
            }
            // Clear session cookie
            String clearCookieHeader = "foodwaste_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax";
            if (req.isSecure()) {
                clearCookieHeader += "; Secure";
            }
            resp.addHeader("Set-Cookie", clearCookieHeader);

            sendSuccess(resp, "Signed out successfully", null);
            return;
        }

        sendBadRequest(resp, "Invalid auth endpoint");
    }

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
}
