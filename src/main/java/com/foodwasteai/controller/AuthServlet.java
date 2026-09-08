package com.foodwasteai.controller;

import com.foodwasteai.model.ApiResponse;
import com.foodwasteai.model.User;
import com.foodwasteai.service.UserService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Controller handling user registration, login, authenticated session creation,
 * logout, and current user identity queries.
 */
@WebServlet(name = "AuthServlet", urlPatterns = {"/api/auth/*", "/logout"})
public class AuthServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private final UserService userService;

    public AuthServlet() {
        this.userService = new UserService();
    }

    public AuthServlet(UserService userService) {
        this.userService = userService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = req.getPathInfo();
        String servletPath = req.getServletPath();

        if ("/logout".equals(servletPath) || "/logout".equals(path)) {
            handleLogout(req, resp, true);
            return;
        }

        if (path == null || "/me".equals(path)) {
            handleGetCurrentUser(req, resp);
            return;
        }

        sendNotFound(resp, "Endpoint not found");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = req.getPathInfo();
        String servletPath = req.getServletPath();

        if ("/logout".equals(servletPath) || "/logout".equals(path)) {
            handleLogout(req, resp, false);
            return;
        }

        if ("/register".equals(path)) {
            handleRegister(req, resp);
            return;
        }

        if ("/login".equals(path)) {
            handleLogin(req, resp);
            return;
        }

        sendNotFound(resp, "Endpoint not found");
    }

    private void handleRegister(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            JsonObject json = parseJsonBody(req);
            if (json == null) {
                sendBadRequest(resp, "Invalid JSON request payload");
                return;
            }

            String email = getJsonString(json, "email");
            String password = getJsonString(json, "password");
            String confirmPassword = getJsonString(json, "confirmPassword");

            if (confirmPassword == null) {
                confirmPassword = getJsonString(json, "confirm_password");
            }

            User user = userService.register(getJsonString(json, "fullName"), email, password, confirmPassword);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", user.getId());
            data.put("email", user.getEmail());
            data.put("fullName", user.getFullName());
            data.put("role", user.getRole().name());

            sendJson(resp, HttpServletResponse.SC_CREATED, ApiResponse.success("Account created successfully", data));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            int status = (msg != null && msg.toLowerCase().contains("already exists"))
                    ? HttpServletResponse.SC_CONFLICT
                    : HttpServletResponse.SC_BAD_REQUEST;
            sendJson(resp, status, ApiResponse.error(msg));
        } catch (SQLException e) {
            logger.error("Database error during registration: {}", e.getMessage(), e);
            sendJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ApiResponse.error("Unable to complete registration. Please try again."));
        }
    }

    private void handleLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            JsonObject json = parseJsonBody(req);
            if (json == null) {
                sendBadRequest(resp, "Invalid JSON request payload");
                return;
            }

            String email = getJsonString(json, "email");
            if (email == null) {
                email = getJsonString(json, "username");
            }
            String password = getJsonString(json, "password");

            if (email == null || email.trim().isEmpty() || password == null || password.isEmpty()) {
                sendUnauthorized(resp, "Invalid email or password");
                return;
            }

            Optional<User> authUser = userService.authenticate(email, password);
            if (authUser.isEmpty()) {
                sendUnauthorized(resp, "Invalid email or password");
                return;
            }

            User user = authUser.get();

            // Create new authenticated HTTP session
            HttpSession oldSession = req.getSession(false);
            if (oldSession != null) {
                oldSession.invalidate();
            }

            HttpSession session = req.getSession(true);
            session.setAttribute("user_id", user.getId());
            session.setAttribute("user_email", user.getEmail());
            session.setAttribute("user_name", user.getFullName());
            session.setAttribute("user_role", user.getRole().name());
            session.setMaxInactiveInterval(24 * 60 * 60); // 24 hours

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", user.getId());
            data.put("email", user.getEmail());
            data.put("fullName", user.getFullName());
            data.put("role", user.getRole().name());
            data.put("redirect", "/dashboard.html");

            sendSuccess(resp, "Login successful", data);
        } catch (SQLException e) {
            logger.error("Database error during login: {}", e.getMessage(), e);
            sendJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ApiResponse.error("Unable to process login. Please try again."));
        }
    }

    private void handleLogout(HttpServletRequest req, HttpServletResponse resp, boolean isGet) throws IOException {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        String accept = req.getHeader("Accept");
        if (isGet || (accept != null && accept.contains("text/html"))) {
            resp.sendRedirect("/login.html");
        } else {
            sendSuccess(resp, "Logged out successfully", null);
        }
    }

    private void handleGetCurrentUser(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("user_id") == null) {
            sendUnauthorized(resp, "Authentication required. Please log in.");
            return;
        }

        Long userId = (Long) session.getAttribute("user_id");
        String email = (String) session.getAttribute("user_email");
        String name = (String) session.getAttribute("user_name");
        String role = (String) session.getAttribute("user_role");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", userId);
        data.put("email", email);
        data.put("fullName", name);
        data.put("role", role);

        sendSuccess(resp, data);
    }

    private JsonObject parseJsonBody(HttpServletRequest req) {
        try (BufferedReader reader = req.getReader()) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            if (sb.length() == 0) return null;
            return JsonParser.parseString(sb.toString()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private String getJsonString(JsonObject json, String memberName) {
        if (json.has(memberName) && !json.get(memberName).isJsonNull()) {
            return json.get(memberName).getAsString();
        }
        return null;
    }
}
