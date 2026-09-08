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


    @ParameterizedTest
    @ValueSource(strings={"/", "/index.html", "/dashboard.html", "/inventory.html", "/sales.html", "/waste.html", "/redistribution.html", "/reports.html", "/settings.html", "/users.html"})
    void privatePagesRedirectAnonymousUsers(String path) throws Exception {
        var response=sendGet(path);
        assertEquals(302,response.statusCode());
        assertTrue(response.headers().firstValue("Location").orElse("").endsWith("/login.html"));
    }
    @ParameterizedTest
    @ValueSource(strings={"/api/inventory", "/api/sales", "/api/waste", "/api/prediction", "/api/recommendations", "/api/redistribution", "/api/redistribution/recipients", "/api/inventory/1.css", "/api/auth/me"})
    void anonymousApiRequestsCannotReadOrMutate(String path) throws Exception {
        for(String method:new String[]{"GET","POST","PUT","DELETE"}) {
            var response=client.send(HttpRequest.newBuilder(URI.create(baseUrl+path)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,response.statusCode(),method+" "+path);
            assertTrue(response.body().contains("AUTHENTICATION_REQUIRED"));
        }
    }
    @ParameterizedTest
    @ValueSource(strings={"/login.html","/register.html"})
    void publicAuthenticationPagesRemainAccessible(String path) throws Exception {
        var response=sendGet(path);assertEquals(200,response.statusCode());
        assertTrue(response.body().contains("auth-page.js"));
    }
    // 16. Zero-stock protections still pass
    @Test
    @DisplayName("16. Zero-stock business protections remain enforced")
    public void testZeroStockProtectionsPass() throws Exception {
        FoodItem zeroItem = foodItemService.createFoodItem(new OwnedFoodItemFixture(null, "Zero Stock Test " + System.currentTimeMillis(), "Produce",
                BigDecimal.ZERO, "kg", new BigDecimal("500.00"), LocalDate.now().plusDays(5), new BigDecimal("1.00")), 1L);

        // Attempting to sell zero stock item must fail validation
        Sale invalidSale = new Sale(zeroItem.getId(), new BigDecimal("1.00"), new BigDecimal("500.00"), null, 1, LocalDateTime.now());
        assertThrows(IllegalArgumentException.class, () -> salesService.recordSale(invalidSale, 1L));

        // Attempting to waste zero stock item must fail validation
        WasteRecord invalidWaste = new WasteRecord(zeroItem.getId(), new BigDecimal("1.00"), WasteRecord.Reason.SPOILED, null, LocalDateTime.now(), "Fail test");
        assertThrows(IllegalArgumentException.class, () -> wasteService.recordWaste(invalidWaste, 1L));
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
        FoodItem item = foodItemService.createFoodItem(new OwnedFoodItemFixture(null, "Stock Integrity Item " + System.currentTimeMillis(), "Produce",
                new BigDecimal("50.00"), "kg", new BigDecimal("1000.00"), LocalDate.now().plusDays(10), new BigDecimal("5.00")), 1L);
        Long itemId = item.getId();

        // 1. Sell 10 kg
        salesService.recordSale(new Sale(itemId, new BigDecimal("10.00"), new BigDecimal("1000.00"), null, 1, LocalDateTime.now()), 1L);
        Optional<FoodItem> afterSale = foodItemService.getFoodItemById(itemId, 1L);
        assertTrue(afterSale.isPresent());
        assertEquals(0, new BigDecimal("40.00").compareTo(afterSale.get().getQuantity()));

        // 2. Waste 5 kg
        wasteService.recordWaste(new WasteRecord(itemId, new BigDecimal("5.00"), WasteRecord.Reason.PREPARATION_WASTE, null, LocalDateTime.now(), "Trim"), 1L);
        Optional<FoodItem> afterWaste = foodItemService.getFoodItemById(itemId, 1L);
        assertTrue(afterWaste.isPresent());
        assertEquals(0, new BigDecimal("35.00").compareTo(afterWaste.get().getQuantity()));
    }
}
