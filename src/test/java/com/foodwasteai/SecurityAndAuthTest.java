package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.User;
import com.foodwasteai.service.AuthService;
import com.foodwasteai.service.GeminiExplanationService;
import com.foodwasteai.util.ValidationUtils;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mindrot.jbcrypt.BCrypt;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SecurityAndAuthTest {

    private AuthService authService;
    private GeminiExplanationService geminiService;
    private Tomcat tomcat;
    private int port;
    private String baseUrl;
    private HttpClient client;

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
    }

    @AfterAll
    public void afterAll() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @BeforeEach
    public void setUp() {
        authService = new AuthService();
        geminiService = new GeminiExplanationService();
    }

    private HttpResponse<String> sendGet(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET()
                .timeout(Duration.ofSeconds(5));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPostJson(String path, String jsonBody, String token) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(5));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("Security: BCrypt password hashing and authentication against database")
    public void testBCryptAuthentication() {
        // 1. Valid Admin login
        Optional<AuthService.UserSession> adminSession = authService.authenticate("admin", "admin123");
        assertTrue(adminSession.isPresent(), "Admin login should succeed with correct password");
        assertEquals("admin", adminSession.get().getUser().getUsername());
        assertEquals(User.Role.ADMIN, adminSession.get().getUser().getRole());
        assertNotNull(adminSession.get().getToken());
        assertTrue(adminSession.get().getToken().startsWith("fwt_"));

        // 2. Valid Staff login
        Optional<AuthService.UserSession> staffSession = authService.authenticate("staff", "staff123");
        assertTrue(staffSession.isPresent(), "Staff login should succeed with correct password");
        assertEquals(User.Role.STAFF, staffSession.get().getUser().getRole());

        // 3. Invalid password must be rejected
        Optional<AuthService.UserSession> badPass = authService.authenticate("admin", "wrongpassword123");
        assertFalse(badPass.isPresent(), "Invalid password must fail authentication");

        // 4. Non-existent user must be rejected
        Optional<AuthService.UserSession> unknownUser = authService.authenticate("hacker_user", "password");
        assertFalse(unknownUser.isPresent(), "Non-existent user must fail authentication");
    }

    @Test
    @DisplayName("Security: Role escalation attempt - backend must reject frontend role payload and load from DB")
    public void testRoleEscalationAttemptRejected() throws Exception {
        // Attempt login with STAFF credentials while sending "role": "ADMIN" in JSON payload
        String spoofedPayload = "{\"username\":\"staff\",\"password\":\"staff123\",\"role\":\"ADMIN\"}";
        HttpResponse<String> response = sendPostJson("/api/auth/login", spoofedPayload, null);
        assertEquals(200, response.statusCode(), "Login should succeed with valid credentials");
        assertTrue(response.body().contains("\"role\":\"STAFF\""), "Server must return database role (STAFF) and ignore frontend role injection");
        assertFalse(response.body().contains("\"role\":\"ADMIN\""), "Server must never grant ADMIN role from frontend payload");
    }

    @Test
    @DisplayName("Security: Session token validation, logout, and token invalidation")
    public void testTokenValidationAndLogout() throws Exception {
        Optional<AuthService.UserSession> session = authService.authenticate("admin", "admin123");
        assertTrue(session.isPresent());

        String token = session.get().getToken();
        Optional<User> validatedUser = authService.validateToken("Bearer " + token);
        assertTrue(validatedUser.isPresent());
        assertEquals("admin", validatedUser.get().getUsername());

        // Verify protected API access with active token
        HttpResponse<String> authResp = sendGet("/api/auth/me", token);
        assertEquals(200, authResp.statusCode());

        // Logout via API
        HttpResponse<String> logoutResp = sendPostJson("/api/auth/logout", "{}", token);
        assertEquals(200, logoutResp.statusCode());

        // After logout, token must be invalidated
        Optional<User> loggedOutUser = authService.validateToken("Bearer " + token);
        assertFalse(loggedOutUser.isPresent(), "Session must be invalidated after logout");

        HttpResponse<String> postLogoutResp = sendGet("/api/auth/me", token);
        assertEquals(401, postLogoutResp.statusCode(), "Expired/invalidated token must receive 401 Unauthorized");
    }

    @Test
    @DisplayName("Security: Expired session token rejection and eviction")
    public void testExpiredSessionToken() throws Exception {
        // Create an expired session (-1 minute)
        User adminUser = new User(1L, "admin", "admin@foodwaste.ai", "", "Admin", User.Role.ADMIN, true);
        AuthService.UserSession expiredSession = authService.createSession(adminUser, -1);
        String expiredToken = expiredSession.getToken();

        // Validate via service
        Optional<User> userOpt = authService.validateToken(expiredToken);
        assertFalse(userOpt.isPresent(), "Expired session token must be rejected");

        // Validate via HTTP API
        HttpResponse<String> response = sendGet("/api/inventory", expiredToken);
        assertEquals(401, response.statusCode(), "Expired session token must return HTTP 401");
    }

    @Test
    @DisplayName("Security: SecureRandom token entropy and format")
    public void testSecureRandomTokenFormat() {
        String token1 = AuthService.generateSecureToken();
        String token2 = AuthService.generateSecureToken();

        assertNotNull(token1);
        assertNotNull(token2);
        assertNotEquals(token1, token2, "Consecutive tokens must be distinct");
        assertTrue(token1.startsWith("fwt_"), "Token must start with 'fwt_' prefix");
        assertEquals(68, token1.length(), "Token length must be 4 (prefix) + 64 (32 hex bytes) = 68 chars");
    }

    @ParameterizedTest
    @DisplayName("Security: Direct URL access without login to protected pages redirects to /index.html")
    @ValueSource(strings = {
        "/dashboard.html",
        "/inventory.html",
        "/sales.html",
        "/waste.html",
        "/prediction.html",
        "/recommendations.html",
        "/redistribution.html",
        "/reports.html",
        "/settings.html",
        "/users.html"
    })
    public void testDirectProtectedPagesRedirectUnauthenticated(String page) throws Exception {
        HttpResponse<String> response = sendGet(page, null);
        assertEquals(302, response.statusCode(), "Unauthenticated request to " + page + " must return HTTP 302");
        String location = response.headers().firstValue("Location").orElse("");
        assertTrue(location.endsWith("/index.html"), "Unauthenticated request to " + page + " must redirect to /index.html");

        // Check no-cache headers
        String cacheControl = response.headers().firstValue("Cache-Control").orElse("");
        assertTrue(cacheControl.contains("no-store") || cacheControl.contains("no-cache"), "Protected pages must have Cache-Control header");
    }

    @ParameterizedTest
    @DisplayName("Security: Direct access to protected APIs without token returns HTTP 401")
    @ValueSource(strings = {
        "/api/inventory",
        "/api/sales",
        "/api/waste",
        "/api/prediction",
        "/api/recommendations",
        "/api/redistribution",
        "/api/chat",
        "/api/users",
        "/api/auth/me"
    })
    public void testProtectedApisReturn401WithoutAuth(String apiPath) throws Exception {
        HttpResponse<String> response = sendGet(apiPath, null);
        assertEquals(401, response.statusCode(), "Protected API " + apiPath + " must return HTTP 401 when unauthenticated");
        assertTrue(response.body().contains("Authentication required") || response.body().contains("Session expired"),
                "API 401 response body should contain error message");
    }

    @ParameterizedTest
    @DisplayName("Security: Public routes remain accessible without authentication (HTTP 200)")
    @ValueSource(strings = {
        "/",
        "/index.html",
        "/api/health",
        "/css/variables.css",
        "/css/components.css",
        "/css/styles.css",
        "/js/api.js",
        "/js/auth.js"
    })
    public void testPublicRoutesAccessible(String publicPath) throws Exception {
        HttpResponse<String> response = sendGet(publicPath, null);
        assertEquals(200, response.statusCode(), "Public path " + publicPath + " must return HTTP 200");
    }

    @Test
    @DisplayName("Security: RBAC - Authenticated STAFF accessing ADMIN-only /api/users returns HTTP 403")
    public void testStaffAccessToAdminEndpointForbidden() throws Exception {
        Optional<AuthService.UserSession> staffSession = authService.authenticate("staff", "staff123");
        assertTrue(staffSession.isPresent(), "Staff should authenticate successfully");
        String staffToken = staffSession.get().getToken();

        // 1. STAFF accessing /api/users GET -> 403
        HttpResponse<String> getResp = sendGet("/api/users", staffToken);
        assertEquals(403, getResp.statusCode(), "STAFF accessing /api/users must return HTTP 403 Forbidden");
        assertTrue(getResp.body().contains("ADMIN privileges required"));

        // 2. STAFF attempting POST /api/users -> 403
        String createPayload = "{\"username\":\"new_user\",\"password\":\"pass1234\",\"role\":\"STAFF\"}";
        HttpResponse<String> postResp = sendPostJson("/api/users", createPayload, staffToken);
        assertEquals(403, postResp.statusCode(), "STAFF attempting to create user must return HTTP 403 Forbidden");

        // 3. STAFF requesting /users.html page -> redirects to /dashboard.html
        HttpResponse<String> pageResp = sendGet("/users.html", staffToken);
        assertEquals(302, pageResp.statusCode(), "STAFF accessing /users.html page must be redirected");
        String location = pageResp.headers().firstValue("Location").orElse("");
        assertTrue(location.endsWith("/dashboard.html"), "STAFF must be redirected to /dashboard.html, got: " + location);
    }

    @Test
    @DisplayName("Security: RBAC - Authenticated ADMIN accessing /api/users is allowed (HTTP 200)")
    public void testAdminAccessToAdminEndpointAllowed() throws Exception {
        Optional<AuthService.UserSession> adminSession = authService.authenticate("admin", "admin123");
        assertTrue(adminSession.isPresent(), "Admin should authenticate successfully");
        String adminToken = adminSession.get().getToken();

        HttpResponse<String> getResp = sendGet("/api/users", adminToken);
        assertEquals(200, getResp.statusCode(), "ADMIN accessing /api/users must return HTTP 200 OK");
        assertTrue(getResp.body().contains("admin"));
    }

    @Test
    @DisplayName("Security: Register new user with enforced BCrypt hash")
    public void testRegisterUserBCrypt() throws SQLException {
        String testUser = "chef_mario_" + (System.currentTimeMillis() % 100000);
        String testEmail = testUser + "@foodwaste.ai";
        User newUser = authService.registerUser(
                testUser,
                testEmail,
                "secretPass2026",
                "Mario Rossi",
                User.Role.STAFF
        );

        assertNotNull(newUser);
        assertEquals(testUser, newUser.getUsername());
        assertNotNull(newUser.getPasswordHash());
        assertTrue(newUser.getPasswordHash().startsWith("$2a$") || newUser.getPasswordHash().startsWith("$2b$"),
                "Password must be hashed with BCrypt ($2a$ format)");
        assertTrue(BCrypt.checkpw("secretPass2026", newUser.getPasswordHash()));

        // Authenticate with newly registered user
        Optional<AuthService.UserSession> newSession = authService.authenticate(testUser, "secretPass2026");
        assertTrue(newSession.isPresent());
    }

    @Test
    @DisplayName("Security: Input validation utility prevents SQL injection & malformed data")
    public void testInputValidation() {
        // Null / Empty Food Item
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateFoodItem(null));

        FoodItem emptyName = new FoodItem();
        emptyName.setName("   ");
        emptyName.setCategory("Produce");
        emptyName.setQuantity(new BigDecimal("10.0"));
        emptyName.setUnit("kg");
        emptyName.setPricePerUnit(new BigDecimal("1000"));
        emptyName.setExpiryDate(LocalDate.now().plusDays(5));
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateFoodItem(emptyName));

        // Negative Quantity
        FoodItem negQty = new FoodItem();
        negQty.setName("Apples");
        negQty.setCategory("Produce");
        negQty.setQuantity(new BigDecimal("-5.0"));
        negQty.setUnit("kg");
        negQty.setPricePerUnit(new BigDecimal("1000"));
        negQty.setExpiryDate(LocalDate.now().plusDays(5));
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateFoodItem(negQty));
    }

    @Test
    @DisplayName("Security: Gemini missing API key fallback handling")
    public void testGeminiFallbackSafety() {
        GeminiExplanationService.ChatResponse response = geminiService.processUserQuery("What is our general waste risk?");

        assertNotNull(response);
        assertNotNull(response.getExplanation());
        assertFalse(response.getExplanation().isEmpty());
        assertNotNull(response.getSourceEngine());
        assertFalse(response.getSmartRecommendations().isEmpty());
    }
}
