package com.foodwasteai.controller;

import com.foodwasteai.model.ApiResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Base Servlet providing common utilities for JSON request/response handling,
 * error serialization, and standard headers.
 */
public abstract class BaseServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected static final Gson gson = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd HH:mm:ss")
            .serializeNulls()
            .create();

    protected void sendJson(HttpServletResponse response, int statusCode, ApiResponse<?> apiResponse)
            throws IOException {
        response.setStatus(statusCode);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");

        try (PrintWriter writer = response.getWriter()) {
            writer.print(gson.toJson(apiResponse));
            writer.flush();
        }
    }

    protected <T> void sendSuccess(HttpServletResponse response, T data) throws IOException {
        sendJson(response, HttpServletResponse.SC_OK, ApiResponse.success(data));
    }

    protected <T> void sendSuccess(HttpServletResponse response, String message, T data) throws IOException {
        sendJson(response, HttpServletResponse.SC_OK, ApiResponse.success(message, data));
    }

    protected void sendCreated(HttpServletResponse response, String message, Object data) throws IOException {
        sendJson(response, HttpServletResponse.SC_CREATED, ApiResponse.success(message, data));
    }

    protected void sendError(HttpServletResponse response, int statusCode, String message) throws IOException {
        sendJson(response, statusCode, ApiResponse.error(message));
    }

    protected void sendBadRequest(HttpServletResponse response, String message) throws IOException {
        sendError(response, HttpServletResponse.SC_BAD_REQUEST, message);
    }

    protected void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        sendError(response, HttpServletResponse.SC_UNAUTHORIZED, message);
    }

    protected void sendNotFound(HttpServletResponse response, String message) throws IOException {
        sendError(response, HttpServletResponse.SC_NOT_FOUND, message);
    }

    protected void sendServerError(HttpServletResponse response, String message) throws IOException {
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message);
    }

    protected <T> T parseJsonBody(HttpServletRequest request, Class<T> clazz) throws IOException, JsonSyntaxException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        String body = sb.toString().trim();
        if (body.isEmpty()) {
            return null;
        }
        return gson.fromJson(body, clazz);
    }
}
