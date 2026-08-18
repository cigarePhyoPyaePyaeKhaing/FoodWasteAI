package com.foodwasteai.controller;

import com.foodwasteai.config.AppConfig;
import com.foodwasteai.prolog.PrologService;
import com.foodwasteai.service.GeminiExplanationService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API Controller for the Gemini Chat & Explainable AI Pipeline.
 * Architecture:
 *   User -> Gemini Chat -> Java Backend -> MySQL Data -> SWI-Prolog Reasoning -> Gemini Explanation -> Smart Recommendation
 * Endpoints:
 *   POST /api/chat
 *   GET  /api/chat/status
 */
@WebServlet(name = "ChatServlet", urlPatterns = {"/api/chat", "/api/chat/*"})
public class ChatServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private final GeminiExplanationService geminiService = new GeminiExplanationService();

    public static class ChatRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String message;
        private String language; // "en" or "mm"

        public ChatRequest() {}

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("prologEngineAvailable", PrologService.isPrologAvailable());
            status.put("geminiModel", AppConfig.getGeminiModel());
            status.put("geminiApiKeyConfigured", !AppConfig.getGeminiApiKey().isEmpty());
            status.put("supportedLanguages", new String[]{"en", "mm"});
            status.put("pipeline", "User -> Gemini Chat -> Java Backend -> MySQL Data -> SWI-Prolog Reasoning -> Gemini Explanation -> Smart Recommendation");
            sendSuccess(resp, status);
        } catch (Exception e) {
            sendServerError(resp, "Failed to check chat status: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            ChatRequest requestPayload = parseJsonBody(req, ChatRequest.class);
            String message = requestPayload != null ? requestPayload.getMessage() : "";
            String language = requestPayload != null ? requestPayload.getLanguage() : null;

            if (language == null || language.trim().isEmpty()) {
                String acceptLang = req.getHeader("Accept-Language");
                if (acceptLang != null && (acceptLang.contains("my") || acceptLang.contains("mm"))) {
                    language = "mm";
                } else {
                    language = "en";
                }
            }

            GeminiExplanationService.ChatResponse chatResponse = geminiService.processUserQuery(message, language);
            sendSuccess(resp, chatResponse);
        } catch (Exception e) {
            logger.error("Error in ChatServlet POST: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to process chat explanation: " + e.getMessage());
        }
    }
}
