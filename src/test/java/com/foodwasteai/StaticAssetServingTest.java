package com.foodwasteai;

import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test verifying that Embedded Tomcat serves all
 * static HTML, CSS, JavaScript, and i18n resources with HTTP 200 and correct MIME types without authentication.
 */
public class StaticAssetServingTest {
    private static Tomcat tomcat;
    private static int port;
    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    public static void setUp() throws Exception {
        // Find a random free port
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        baseUrl = "http://localhost:" + port;
        tomcat = App.createServer(port);
        tomcat.start();

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private HttpResponse<String> fetch(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void testRootReturnsDashboardHtml() throws Exception {
        HttpResponse<String> response = fetch("/");
        assertEquals(200, response.statusCode(), "Root path / must return HTTP 200");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("text/html"), "Root path must return text/html Content-Type");
        assertTrue(response.body().contains("FoodWaste AI"), "Root response must contain page brand");
        assertTrue(response.body().contains("Dashboard"), "Root response must contain Dashboard");
    }

    @Test
    public void testIndexHtmlReturns200AndTextHtml() throws Exception {
        HttpResponse<String> response = fetch("/index.html");
        assertEquals(200, response.statusCode(), "Page /index.html must return HTTP 200");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("text/html"), "Page /index.html must have text/html Content-Type, got: " + contentType);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/dashboard.html",
        "/inventory.html",
        "/redistribution.html",
        "/reports.html",
        "/sales.html",
        "/settings.html",
        "/waste.html"
    })
    public void testHtmlPagesServeDirectlyWithoutAuth(String page) throws Exception {
        HttpResponse<String> response = fetch(page);
        assertEquals(200, response.statusCode(), "Page " + page + " must return HTTP 200 directly without authentication");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("text/html"), "Page " + page + " must return text/html Content-Type, got: " + contentType);
    }

    @Test
    public void testUsersPageIsRemoved() throws Exception {
        HttpResponse<String> response = fetch("/users.html");
        assertEquals(404, response.statusCode(), "Page /users.html must return 404 since it has been removed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/css/variables.css",
        "/css/components.css",
        "/css/styles.css"
    })
    public void testCssAssetsReturn200AndTextCss(String cssPath) throws Exception {
        HttpResponse<String> response = fetch(cssPath);
        assertEquals(200, response.statusCode(), "CSS " + cssPath + " must return HTTP 200");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("text/css"), "CSS " + cssPath + " must have text/css Content-Type for strict browser MIME checking, got: " + contentType);
        assertFalse(response.body().trim().isEmpty(), "CSS " + cssPath + " must not be empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/js/api.js",
        "/js/dashboard.js",
        "/js/inventory.js",
        "/js/redistribution.js",
        "/js/reports.js",
        "/js/sales.js",
        "/js/waste.js",
        "/js/i18n/en.js",
        "/js/i18n/mm.js",
        "/js/i18n/language.js"
    })
    public void testJsAssetsReturn200AndJavascriptMime(String jsPath) throws Exception {
        HttpResponse<String> response = fetch(jsPath);
        assertEquals(200, response.statusCode(), "JS " + jsPath + " must return HTTP 200");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("javascript"), "JS " + jsPath + " must have javascript Content-Type, got: " + contentType);
        assertFalse(response.body().trim().isEmpty(), "JS " + jsPath + " must not be empty");
    }

    @Test
    public void testAiAssistantJsUnregistered() throws Exception {
        HttpResponse<String> response = fetch("/js/ai-assistant.js");
        assertEquals(404, response.statusCode(), "Chatbot JS asset must return 404 Not Found");
    }

    @Test
    public void testApiHealthCheckRemainsAccessible() throws Exception {
        HttpResponse<String> response = fetch("/api/health");
        assertEquals(200, response.statusCode(), "API health check must return HTTP 200");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("application/json"), "API health check must return JSON");
        assertTrue(response.body().contains("UP"), "Health response body must contain UP status");
    }
}
