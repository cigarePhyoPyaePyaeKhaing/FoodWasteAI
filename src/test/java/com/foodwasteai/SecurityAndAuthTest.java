package com.foodwasteai;

import com.foodwasteai.model.*;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.prolog.PrologService;
import com.foodwasteai.service.*;
import com.foodwasteai.util.ValidationUtils;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SecurityAndAuthTest {

    private Tomcat tomcat;
    private int port;
    private String baseUrl;
    private HttpClient client;
    private FoodItemService foodItemService;
    private SalesService salesService;
    private WasteService wasteService;
    private PredictionService predictionService;
    private RecommendationService recommendationService;
    private RedistributionService redistributionService;
    private PrologService prologService;

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

        foodItemService = new FoodItemService();
        salesService = new SalesService();
        wasteService = new WasteService();
        predictionService = new PredictionService();
        recommendationService = new RecommendationService();
        redistributionService = new RedistributionService();
        prologService = new PrologService();
    }

    @AfterAll
    public void afterAll() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private HttpResponse<String> sendGet(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPostJson(String path, String jsonBody) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(5))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // 1. Root URL opens Dashboard without login
    @Test
    @DisplayName("1. Root URL '/' opens Dashboard without login")
    public void testRootUrlOpensDashboardWithoutLogin() throws Exception {
        HttpResponse<String> resp = sendGet("/");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("Dashboard"));
        assertFalse(resp.body().contains("Sign In to Portal"));
        assertFalse(resp.body().contains("handleLogin"));
    }

    // 2. Dashboard loads with no session
    @Test
    @DisplayName("2. Dashboard loads with no session")
    public void testDashboardLoadsWithNoSession() throws Exception {
        HttpResponse<String> resp = sendGet("/dashboard.html");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("Dashboard"));
    }

    // 3. Inventory works without auth
    @Test
    @DisplayName("3. Inventory API and HTML page work without auth")
    public void testInventoryWorksWithoutAuth() throws Exception {
        HttpResponse<String> pageResp = sendGet("/inventory.html");
        assertEquals(200, pageResp.statusCode());

        HttpResponse<String> apiResp = sendGet("/api/inventory");
        assertEquals(200, apiResp.statusCode());
        assertTrue(apiResp.body().contains("\"success\":true"));

        String uniqueName = "Test NoAuth Item " + System.currentTimeMillis();
        String itemJson = String.format("{\"name\":\"%s\",\"category\":\"Produce\",\"quantity\":15.0,\"unit\":\"kg\",\"pricePerUnit\":2000.0,\"expiryDate\":\"%s\",\"reorderThreshold\":5.0}",
                uniqueName, LocalDate.now().plusDays(10));
        HttpResponse<String> createResp = sendPostJson("/api/inventory", itemJson);
        assertEquals(201, createResp.statusCode());
        assertTrue(createResp.body().contains(uniqueName));
    }

    // 4. Sales works without auth
    @Test
    @DisplayName("4. Sales API and HTML page work without auth")
    public void testSalesWorksWithoutAuth() throws Exception {
        HttpResponse<String> pageResp = sendGet("/sales.html");
        assertEquals(200, pageResp.statusCode());

        FoodItem item = foodItemService.createFoodItem(new FoodItem(null, "Sales Test " + System.currentTimeMillis(), "Produce",
                new BigDecimal("20.00"), "kg", new BigDecimal("1000.00"), LocalDate.now().plusDays(10), new BigDecimal("2.00")), null);

        String saleJson = String.format("{\"foodItemId\":%d,\"quantitySold\":3.0,\"unitPrice\":1000.0}", item.getId());
        HttpResponse<String> saleResp = sendPostJson("/api/sales", saleJson);
        assertEquals(201, saleResp.statusCode());
        assertTrue(saleResp.body().contains("\"success\":true"));
    }

    // 5. Waste works without auth
    @Test
    @DisplayName("5. Waste API and HTML page work without auth")
    public void testWasteWorksWithoutAuth() throws Exception {
        HttpResponse<String> pageResp = sendGet("/waste.html");
        assertEquals(200, pageResp.statusCode());

        FoodItem item = foodItemService.createFoodItem(new FoodItem(null, "Waste Test " + System.currentTimeMillis(), "Dairy",
                new BigDecimal("10.00"), "liter", new BigDecimal("1500.00"), LocalDate.now().plusDays(5), new BigDecimal("2.00")), null);

        String wasteJson = String.format("{\"foodItemId\":%d,\"quantityWasted\":2.0,\"reason\":\"EXPIRED\",\"notes\":\"No-auth test\"}", item.getId());
        HttpResponse<String> wasteResp = sendPostJson("/api/waste", wasteJson);
        assertEquals(201, wasteResp.statusCode());
        assertTrue(wasteResp.body().contains("\"success\":true"));
    }

    // 6. Prediction works without auth
    @Test
    @DisplayName("6. Prediction API works without auth")
    public void testPredictionWorksWithoutAuth() throws Exception {
        HttpResponse<String> resp = sendGet("/api/prediction");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"success\":true"));
    }

    // 7. Recommendations work without auth
    @Test
    @DisplayName("7. Recommendations API works without auth")
    public void testRecommendationsWorkWithoutAuth() throws Exception {
        HttpResponse<String> resp = sendGet("/api/recommendations");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"success\":true"));
    }

    // 8. Redistribution works without auth
    @Test
    @DisplayName("8. Redistribution API and HTML page work without auth")
    public void testRedistributionWorksWithoutAuth() throws Exception {
        HttpResponse<String> pageResp = sendGet("/redistribution.html");
        assertEquals(200, pageResp.statusCode());

        HttpResponse<String> recipientsResp = sendGet("/api/redistribution/recipients");
        assertEquals(200, recipientsResp.statusCode());
        assertTrue(recipientsResp.body().contains("\"success\":true"));
    }

    // 9. No login redirect
    @ParameterizedTest
    @ValueSource(strings = {"/dashboard.html", "/inventory.html", "/sales.html", "/waste.html", "/redistribution.html", "/reports.html", "/settings.html"})
    @DisplayName("9. No login redirect on any standard application route")
    public void testNoLoginRedirect(String path) throws Exception {
        HttpResponse<String> resp = sendGet(path);
        assertNotEquals(302, resp.statusCode(), "Route " + path + " must not redirect to login");
        assertEquals(200, resp.statusCode());
    }

    // 10. No auth-only 401/403
    @ParameterizedTest
    @ValueSource(strings = {"/api/inventory", "/api/sales", "/api/waste", "/api/prediction", "/api/recommendations", "/api/redistribution", "/api/version", "/api/health"})
    @DisplayName("10. No auth-only 401 or 403 on standard API endpoints")
    public void testNoAuthOnly401or403(String apiPath) throws Exception {
        HttpResponse<String> resp = sendGet(apiPath);
        assertNotEquals(401, resp.statusCode(), "API " + apiPath + " must not return 401");
        assertNotEquals(403, resp.statusCode(), "API " + apiPath + " must not return 403");
        assertEquals(200, resp.statusCode());
    }

    // 11. No logout UI
    @ParameterizedTest
    @ValueSource(strings = {"/dashboard.html", "/inventory.html", "/sales.html", "/waste.html", "/redistribution.html", "/reports.html", "/settings.html"})
    @DisplayName("11. No logout UI elements in any page")
    public void testNoLogoutUi(String path) throws Exception {
        HttpResponse<String> resp = sendGet(path);
        assertFalse(resp.body().contains("sidebar-logout-btn"), "Page " + path + " must not contain sidebar-logout-btn");
        assertFalse(resp.body().contains("Auth.logout()"), "Page " + path + " must not contain Auth.logout()");
    }

    // 12. No Restaurant Manager profile card
    @ParameterizedTest
    @ValueSource(strings = {"/dashboard.html", "/inventory.html", "/sales.html", "/waste.html", "/redistribution.html", "/reports.html", "/settings.html"})
    @DisplayName("12. No user identity profile card in sidebar")
    public void testNoUserProfileCard(String path) throws Exception {
        HttpResponse<String> resp = sendGet(path);
        assertFalse(resp.body().contains("sidebar-account-card"), "Page " + path + " must not contain sidebar-account-card");
        assertFalse(resp.body().contains("current-user-avatar"), "Page " + path + " must not contain current-user-avatar");
        assertFalse(resp.body().contains("current-user-name"), "Page " + path + " must not contain current-user-name");
    }

    // 13. No ADMIN/STAFF badge
    @ParameterizedTest
    @ValueSource(strings = {"/dashboard.html", "/inventory.html", "/sales.html", "/waste.html", "/redistribution.html", "/reports.html", "/settings.html"})
    @DisplayName("13. No ADMIN/STAFF role badges in application pages")
    public void testNoRoleBadges(String path) throws Exception {
        HttpResponse<String> resp = sendGet(path);
        assertFalse(resp.body().contains("current-user-role"), "Page " + path + " must not contain current-user-role");
        assertFalse(resp.body().contains("admin-only"), "Page " + path + " must not contain admin-only class");
    }

    // 14. Users page/navigation removed
    @Test
    @DisplayName("14. Users page and navigation links removed")
    public void testUsersPageAndNavigationRemoved() throws Exception {
        HttpResponse<String> usersPageResp = sendGet("/users.html");
        assertEquals(404, usersPageResp.statusCode(), "/users.html should return 404 Not Found");

        HttpResponse<String> dashResp = sendGet("/dashboard.html");
        assertFalse(dashResp.body().contains("href=\"/users.html\""));

        HttpResponse<String> settingsResp = sendGet("/settings.html");
        assertFalse(settingsResp.body().contains("href=\"/users.html\""));
    }

    // 15. Login page no longer part of normal app
    @Test
    @DisplayName("15. Login form is no longer part of application")
    public void testLoginPageNoLongerPartOfNormalApp() throws Exception {
        HttpResponse<String> rootResp = sendGet("/");
        assertFalse(rootResp.body().contains("name=\"password\"") || rootResp.body().contains("id=\"password\""));
        assertFalse(rootResp.body().contains("id=\"login-form\""));
    }

    // 16. Zero-stock protections still pass
    @Test
    @DisplayName("16. Zero-stock business protections remain enforced")
    public void testZeroStockProtectionsPass() throws Exception {
        FoodItem zeroItem = foodItemService.createFoodItem(new FoodItem(null, "Zero Stock Test " + System.currentTimeMillis(), "Produce",
                BigDecimal.ZERO, "kg", new BigDecimal("500.00"), LocalDate.now().plusDays(5), new BigDecimal("1.00")), null);

        // Attempting to sell zero stock item must fail validation
        Sale invalidSale = new Sale(zeroItem.getId(), new BigDecimal("1.00"), new BigDecimal("500.00"), null, 1, LocalDateTime.now());
        assertThrows(IllegalArgumentException.class, () -> salesService.recordSale(invalidSale, null));

        // Attempting to waste zero stock item must fail validation
        WasteRecord invalidWaste = new WasteRecord(zeroItem.getId(), new BigDecimal("1.00"), WasteRecord.Reason.SPOILED, null, LocalDateTime.now(), "Fail test");
        assertThrows(IllegalArgumentException.class, () -> wasteService.recordWaste(invalidWaste, null));
    }

    // 17. Expiry redistribution boundaries still pass
    @Test
    @DisplayName("17. Expiry redistribution boundary classifications pass")
    public void testExpiryRedistributionBoundariesPass() throws Exception {
        PrologAssessment assessment = prologService.assessFoodItem("Expired Test", "kg", 5.0, 0.0, -1, 0.0, 0.0);
        assertNotNull(assessment);
        assertEquals("HIGH", assessment.getRiskLevel());
    }

    // 18. Prolog production authority still passes
    @Test
    @DisplayName("18. Prolog production reasoning authority remains active")
    public void testPrologProductionAuthorityPasses() {
        PrologAssessment assessment = prologService.assessFoodItem("Near Expiry Milk", "liter", 10.0, 2.0, 1, 0.50, 0.0);
        assertNotNull(assessment);
        assertTrue(assessment.getRiskScore() >= 50, "Near expiry dairy must have elevated risk score");
    }

    // 19. Business validation still passes
    @Test
    @DisplayName("19. Business validation utils continue to enforce constraints")
    public void testBusinessValidationPasses() {
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateFoodItem(null));
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateSale(null));
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateWasteRecord(null));
    }

    // 20. Transaction integrity tests still pass
    @Test
    @DisplayName("20. Transaction integrity and stock deduction remain safe and consistent")
    public void testTransactionIntegrityPasses() throws Exception {
        FoodItem item = foodItemService.createFoodItem(new FoodItem(null, "Stock Integrity Item " + System.currentTimeMillis(), "Produce",
                new BigDecimal("50.00"), "kg", new BigDecimal("1000.00"), LocalDate.now().plusDays(10), new BigDecimal("5.00")), null);
        Long itemId = item.getId();

        // 1. Sell 10 kg
        salesService.recordSale(new Sale(itemId, new BigDecimal("10.00"), new BigDecimal("1000.00"), null, 1, LocalDateTime.now()), null);
        Optional<FoodItem> afterSale = foodItemService.getFoodItemById(itemId);
        assertTrue(afterSale.isPresent());
        assertEquals(0, new BigDecimal("40.00").compareTo(afterSale.get().getQuantity()));

        // 2. Waste 5 kg
        wasteService.recordWaste(new WasteRecord(itemId, new BigDecimal("5.00"), WasteRecord.Reason.PREPARATION_WASTE, null, LocalDateTime.now(), "Trim"), null);
        Optional<FoodItem> afterWaste = foodItemService.getFoodItemById(itemId);
        assertTrue(afterWaste.isPresent());
        assertEquals(0, new BigDecimal("35.00").compareTo(afterWaste.get().getQuantity()));
    }
}
