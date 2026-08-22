package com.foodwasteai.controller;

import com.foodwasteai.config.AppConfig;
import com.foodwasteai.prolog.PrologService;
import com.foodwasteai.service.GroqAIService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API Controller for the Free Hosted Groq Chat & Explainable AI Pipeline.
 * Architecture:
 *   User -> ChatServlet -> MySQL Live Data -> SWI-Prolog Reasoning -> Groq AI (Llama-3.3-70b-versatile) -> Smart Directives
 * Endpoints:
 *   POST /api/chat
 *   GET  /api/chat/status
 */
@WebServlet(name = "ChatServlet", urlPatterns = {"/api/chat", "/api/chat/*"})
public class ChatServlet extends BaseServlet {
    private static final long serialVersionUID = 1L;
    private final GroqAIService groqService = new GroqAIService();

    public static class ChatRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String message;
        private String query;
        private String prompt;
        private String language; // "en" or "mm"
        private String sessionId;
        private String conversationId;

        public ChatRequest() {}

        public String getMessage() {
            if (message != null && !message.trim().isEmpty()) return message.trim();
            if (query != null && !query.trim().isEmpty()) return query.trim();
            if (prompt != null && !prompt.trim().isEmpty()) return prompt.trim();
            return "";
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public String getSessionId() {
            if (sessionId != null && !sessionId.trim().isEmpty()) return sessionId.trim();
            if (conversationId != null && !conversationId.trim().isEmpty()) return conversationId.trim();
            return null;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getConversationId() {
            return conversationId;
        }

        public void setConversationId(String conversationId) {
            this.conversationId = conversationId;
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("status", "ok");
            status.put("ready", true);
            status.put("supportedLanguages", new String[]{"en", "mm"});
            sendSuccess(resp, status);
        } catch (Exception e) {
            sendServerError(resp, "Service unavailable.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            ChatRequest requestPayload = parseJsonBody(req, ChatRequest.class);
            String message = requestPayload != null ? requestPayload.getMessage() : "";
            String language = requestPayload != null ? requestPayload.getLanguage() : null;
            String sessionId = requestPayload != null ? requestPayload.getSessionId() : null;

            if (sessionId == null || sessionId.trim().isEmpty()) {
                sessionId = req.getSession(true).getId();
            }

            if (language == null || language.trim().isEmpty()) {
                String acceptLang = req.getHeader("Accept-Language");
                if (acceptLang != null && (acceptLang.contains("my") || acceptLang.contains("mm"))) {
                    language = "mm";
                } else {
                    language = "en";
                }
            }

            GroqAIService.ChatResponse chatResponse = groqService.processUserQuery(message, language, sessionId);
            sendSuccess(resp, chatResponse);
        } catch (Exception e) {
            logger.error("Error in ChatServlet POST: {}", e.getMessage(), e);
            sendServerError(resp, "Failed to process chat explanation: " + e.getMessage());
        }
    }
}
