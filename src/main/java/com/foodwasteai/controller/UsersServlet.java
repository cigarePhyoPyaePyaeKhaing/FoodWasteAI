package com.foodwasteai.controller;

import com.foodwasteai.model.User;
import com.foodwasteai.service.AuthService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API Controller for User Account Management (Admin Protected).
 * Endpoints:
 *   GET  /api/users
 *   POST /api/users
 */
@WebServlet(name = "UsersServlet", urlPatterns = {"/api/users", "/api/users/*"})
public class UsersServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private final AuthService authService = new AuthService();

    public static class CreateUserRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String username;
        private String email;
        private String password;
        private String fullName;
        private String role; // ADMIN, STAFF

        public CreateUserRequest() {}

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }

    private boolean checkAdminPermission(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        User currentUser = (User) req.getAttribute("currentUser");
        if (currentUser == null) {
            String token = extractToken(req);
            currentUser = authService.validateToken(token).orElse(null);
        }
        if (currentUser == null) {
            sendUnauthorized(resp, "Authentication required to access user management");
            return false;
        }
        if (currentUser.getRole() != User.Role.ADMIN) {
            sendError(resp, HttpServletResponse.SC_FORBIDDEN, "Forbidden: ADMIN privileges required");
            return false;
        }
        return true;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!checkAdminPermission(req, resp)) {
            return;
        }

        try {
            List<User> users = authService.getAllUsers();
            List<Map<String, Object>> dtos = new ArrayList<>();
            for (User u : users) {
                Map<String, Object> dto = new LinkedHashMap<>();
                dto.put("id", u.getId());
                dto.put("username", u.getUsername());
                dto.put("email", u.getEmail());
                dto.put("fullName", u.getFullName());
                dto.put("role", u.getRole() != null ? u.getRole().name() : "STAFF");
                dto.put("active", u.isActive());
                dto.put("createdAt", u.getCreatedAt());
                dtos.add(dto);
            }
            sendSuccess(resp, dtos);
        } catch (Exception e) {
            logger.error("Error in UsersServlet GET: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to retrieve user accounts: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!checkAdminPermission(req, resp)) {
            return;
        }

        try {
            CreateUserRequest payload = parseJsonBody(req, CreateUserRequest.class);
            if (payload == null || payload.getUsername() == null || payload.getPassword() == null) {
                sendBadRequest(resp, "Username and password are required");
                return;
            }

            User.Role role = User.Role.STAFF;
            if (payload.getRole() != null && payload.getRole().equalsIgnoreCase("ADMIN")) {
                role = User.Role.ADMIN;
            }

            User saved = authService.registerUser(
                    payload.getUsername().trim(),
                    payload.getEmail() != null ? payload.getEmail().trim() : (payload.getUsername() + "@foodwaste.ai"),
                    payload.getPassword(),
                    payload.getFullName() != null ? payload.getFullName().trim() : payload.getUsername(),
                    role
            );

            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", saved.getId());
            dto.put("username", saved.getUsername());
            dto.put("fullName", saved.getFullName());
            dto.put("role", saved.getRole().name());

            sendCreated(resp, "User account created successfully", dto);
        } catch (IllegalArgumentException e) {
            sendBadRequest(resp, e.getMessage());
        } catch (Exception e) {
            logger.error("Error in UsersServlet POST: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to create user account: " + e.getMessage());
        }
    }
}
