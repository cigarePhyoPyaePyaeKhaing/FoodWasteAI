package com.foodwasteai.controller;

import com.foodwasteai.model.ApiResponse;
import com.google.gson.*;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Base Servlet providing JSON serialization/deserialization with Java 8 Time support,
 * standard HTTP response envelopes, and error handling.
 */
public abstract class BaseServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDate.class, new JsonSerializer<LocalDate>() {
                @Override
                public JsonElement serialize(LocalDate src, Type typeOfSrc, JsonSerializationContext context) {
                    return new JsonPrimitive(src.format(DateTimeFormatter.ISO_LOCAL_DATE));
                }
            })
            .registerTypeAdapter(LocalDate.class, new JsonDeserializer<LocalDate>() {
                @Override
                public LocalDate deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                    return LocalDate.parse(json.getAsString().substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
                }
            })
            .registerTypeAdapter(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
                @Override
                public JsonElement serialize(LocalDateTime src, Type typeOfSrc, JsonSerializationContext context) {
                    return new JsonPrimitive(src.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                }
            })
            .registerTypeAdapter(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
                @Override
                public LocalDateTime deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                    String str = json.getAsString();
                    if (str.length() == 10) {
                        return LocalDate.parse(str, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
                    }
                    return LocalDateTime.parse(str.replace(" ", "T"), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                }
            })
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
        request.setCharacterEncoding("UTF-8");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        String body = sb.toString().trim();
        if (body.isEmpty()) {
            return null;
        }
        return gson.fromJson(body, clazz);
    }

    protected Long parseIdFromPath(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            return null;
        }
        try {
            String[] parts = pathInfo.split("/");
            if (parts.length >= 2 && !parts[1].trim().isEmpty()) {
                return Long.parseLong(parts[1].trim());
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }
}
