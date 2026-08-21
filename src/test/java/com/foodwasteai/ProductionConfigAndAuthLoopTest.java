package com.foodwasteai;

import com.foodwasteai.config.AppConfig;
import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.service.AuthService;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProductionConfigAndAuthLoopTest {

    private Tomcat tomcat;
    private int port;
    private String baseUrl;
    private HttpClient client;
    private AuthService authService;

    @BeforeAll
    public void beforeAll() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        baseUrl = "http://localhost:" + port;
        tomcat = App.createServer(port);
        tomcat.start();

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        authService = new AuthService();
    }

    @AfterAll
    public void afterAll() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @Test
    @DisplayName("Configuration: Port handles literal '$PORT' gracefully without warning or crash")
    public void testPortConfigHandlesDollarPortGracefully() {
        System.setProperty("server.port", "$PORT");
        int resolvedPort = AppConfig.getPort();
        assertTrue(resolvedPort > 0, "Port must resolve to a valid integer (default 8080)");
        System.clearProperty("server.port");
    }

    @Test
    @DisplayName("Configuration: Database environment variables override local defaults")
    public void testDatabaseConfigOverrides() {
        System.setProperty("DB_HOST", "mysql-33833560-foodwasteai.h.aivencloud.com");
        System.setProperty("DB_PORT", "15129");
        System.setProperty("DB_NAME", "foodwaste_ai");
        System.setProperty("DB_USER", "avnadmin");
        System.setProperty("DB_SSL_MODE", "REQUIRED");

        assertEquals("mysql-33833560-foodwasteai.h.aivencloud.com", AppConfig.getDbHost());
        assertEquals(15129, AppConfig.getDbPort());
        assertEquals("foodwaste_ai", AppConfig.getDbName());
        assertEquals("avnadmin", AppConfig.getDbUser());
        assertEquals("REQUIRED", AppConfig.getDbSslMode());

        System.clearProperty("DB_HOST");
        System.clearProperty("DB_PORT");
        System.clearProperty("DB_NAME");
        System.clearProperty("DB_USER");
        System.clearProperty("DB_SSL_MODE");
    }

    @Test
    @DisplayName("Auth Loop Prevention: GET /api/auth/me returns 401 when unauthenticated")
    public void testSessionCheckUnauthenticatedReturns401() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/me"))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode(), "Unauthenticated session check must return 401");
        assertTrue(resp.body().contains("Authentication required") || resp.body().contains("no active session") || resp.body().contains("invalid"));
    }

    @Test
    @DisplayName("Auth Loop Prevention: GET /api/auth/me returns 200 with valid user data when session exists")
    public void testSessionCheckWithValidSessionReturns200() throws Exception {
        Optional<AuthService.UserSession> sessionOpt = authService.authenticate("admin", "admin123");
        assertTrue(sessionOpt.isPresent());
        String token = sessionOpt.get().getToken();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/me"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "Authenticated session check must return 200 OK");
        assertTrue(resp.body().contains("\"username\":\"admin\""));
        assertTrue(resp.body().contains("\"role\":\"ADMIN\""));
    }

    @Test
    @DisplayName("Auth Loop Prevention: Cookie-based GET /api/auth/session returns 200")
    public void testSessionCheckWithCookie() throws Exception {
        Optional<AuthService.UserSession> sessionOpt = authService.authenticate("staff", "staff123");
        assertTrue(sessionOpt.isPresent());
        String token = sessionOpt.get().getToken();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/session"))
                .header("Cookie", "foodwaste_session=" + token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "Cookie session check must return 200 OK");
        assertTrue(resp.body().contains("\"username\":\"staff\""));
        assertTrue(resp.body().contains("\"role\":\"STAFF\""));
    }

    @Test
    @DisplayName("Auth Loop Prevention: Direct unauthenticated /dashboard.html returns single 302 to /index.html")
    public void testDirectUnauthenticatedDashboardRedirectsOnce() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/dashboard.html"))
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(302, resp.statusCode(), "Unauthenticated dashboard request must return 302");
        String location = resp.headers().firstValue("Location").orElse("");
        assertEquals("/index.html", location, "Redirect destination must be /index.html");
    }

    @Test
    @DisplayName("Auth Loop Prevention: Invalidation and Logout clears session and subsequent check returns 401")
    public void testLogoutInvalidatesSessionCheck() throws Exception {
        Optional<AuthService.UserSession> sessionOpt = authService.authenticate("admin", "admin123");
        assertTrue(sessionOpt.isPresent());
        String token = sessionOpt.get().getToken();

        // Logout
        HttpRequest logoutReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/logout"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> logoutResp = client.send(logoutReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, logoutResp.statusCode());

        // Verify session check now fails with 401
        HttpRequest checkReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/me"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> checkResp = client.send(checkReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, checkResp.statusCode(), "Session check after logout must return 401");
    }
}
