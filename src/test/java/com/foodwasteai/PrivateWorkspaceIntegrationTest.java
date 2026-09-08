package com.foodwasteai;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.FoodItemDao;
import com.foodwasteai.model.FoodItem;
import com.google.gson.*;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP sessions and MySQL, never an in-memory isolation simulation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrivateWorkspaceIntegrationTest {
    private Tomcat server;
    private String base;
    private HttpClient alice, bob;
    private long fish, milk, aliceId, bobId;
    private static final String PASSWORD = "PrivateTest!234";
    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    private HttpResponse<String> request(HttpClient client, String method, String path, JsonObject body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private JsonObject account(String name, String email) {
        JsonObject body = new JsonObject();
        body.addProperty("fullName", name); body.addProperty("email", email);
        body.addProperty("password", PASSWORD); body.addProperty("confirmPassword", PASSWORD);
        return body;
    }
    private long register(HttpClient client, String name) throws Exception {
        JsonObject body = account(name, name.toLowerCase() + "." + UUID.randomUUID() + "@example.test");
        var registered = request(client, "POST", "/api/auth/register", body);
        assertEquals(201, registered.statusCode(), registered.body());
        var loggedIn = request(client, "POST", "/api/auth/login", body);
        assertEquals(200, loggedIn.statusCode(), loggedIn.body());
        String cookie = loggedIn.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Lax"));
        return json(registered.body()).getAsJsonObject("data").get("id").getAsLong();
    }
    private long inventory(HttpClient client, String name, String category, int quantity, String unit) throws Exception {
        JsonObject body = json("{\"name\":\"" + name + "\",\"category\":\"" + category + "\",\"quantity\":" + quantity
                + ",\"unit\":\"" + unit + "\",\"pricePerUnit\":1000,\"expiryDate\":\"" + LocalDate.now().plusDays(5) + "\",\"userId\":999999}");
        var response = request(client, "POST", "/api/inventory", body);
        assertEquals(201, response.statusCode(), response.body());
        return json(response.body()).getAsJsonObject("data").get("id").getAsLong();
    }
    @BeforeAll void start() throws Exception {
        assertTrue(DatabaseConfig.isAvailable(), "Real disposable MySQL is required");
        try (var connection = DatabaseConfig.getConnection()) { DatabaseConfig.ensureDefaultRecipientsExist(connection); }
        server = App.createServer(0); server.start();
        base = "http://127.0.0.1:" + server.getConnector().getLocalPort();
        alice = client(); bob = client();
        aliceId = register(alice, "Alice"); bobId = register(bob, "Bob");
        assertEquals(0, json(request(alice, "GET", "/api/inventory", null).body()).getAsJsonArray("data").size());
        assertEquals(0, json(request(bob, "GET", "/api/inventory", null).body()).getAsJsonArray("data").size());
        fish = inventory(alice, "Fish", "Seafood", 100, "kg");
        milk = inventory(bob, "Milk", "Dairy", 20, "liter");
    }
    @AfterAll void stop() throws Exception { if (server != null) { server.stop(); server.destroy(); } }

    @Test void authenticationValidationAndLogout() throws Exception {
        HttpClient anonymous = client();
        var bad = account("", "invalid");
        assertEquals(400, request(anonymous, "POST", "/api/auth/register", bad).statusCode());
        var body = account("Carol", "carol." + UUID.randomUUID() + "@example.test");
        body.addProperty("confirmPassword", "mismatch");
        assertEquals(400, request(anonymous, "POST", "/api/auth/register", body).statusCode());
        body.addProperty("confirmPassword", PASSWORD);
        assertEquals(201, request(anonymous, "POST", "/api/auth/register", body).statusCode());
        assertEquals(409, request(anonymous, "POST", "/api/auth/register", body).statusCode());
        body.addProperty("password", "incorrect");
        assertEquals(401, request(anonymous, "POST", "/api/auth/login", body).statusCode());
        body.addProperty("password", PASSWORD);
        assertEquals(200, request(anonymous, "POST", "/api/auth/login", body).statusCode());
        assertEquals(200, request(anonymous, "POST", "/api/auth/logout", new JsonObject()).statusCode());
        assertEquals(401, request(anonymous, "GET", "/api/inventory", null).statusCode());
    }
    @Test void allOperationalEndpointsRequireAuthentication() throws Exception {
        for (String path : new String[]{"/api/inventory", "/api/sales", "/api/waste", "/api/prediction", "/api/recommendations", "/api/redistribution", "/api/redistribution/recipients", "/api/auth/me"}) {
            assertEquals(401, request(client(), "GET", path, null).statusCode(), path);
        }
        for (String path : new String[]{"/", "/dashboard.html", "/reports.html", "/settings.html"}) {
            assertEquals(302, request(client(), "GET", path, null).statusCode(), path);
        }
    }
    @Test void inventoryOwnershipRejectsTamperedIds() throws Exception {
        String a = request(alice, "GET", "/api/inventory?userId=" + bobId, null).body();
        String b = request(bob, "GET", "/api/inventory", null).body();
        assertTrue(a.contains("Fish")); assertFalse(a.contains("Milk"));
        assertTrue(b.contains("Milk")); assertFalse(b.contains("Fish"));
        assertEquals(404, request(bob, "GET", "/api/inventory/" + fish, null).statusCode());
        assertEquals(404, request(bob, "PUT", "/api/inventory/" + fish, json("{\"name\":\"Stolen\",\"quantity\":1}")).statusCode());
        assertEquals(404, request(bob, "DELETE", "/api/inventory/" + fish, null).statusCode());
        assertFalse(request(bob, "GET", "/api/inventory/" + fish + "/history", null).body().contains("Initial stock"));
        assertEquals(aliceId, new FoodItemDao().findById(fish, aliceId).orElseThrow().getUserId());
    }
    @Test void privateOperationalListsAndForecasts() throws Exception {
        for (String path : new String[]{"/api/sales", "/api/waste", "/api/prediction", "/api/recommendations", "/api/redistribution", "/api/redistribution/stats", "/api/redistribution/candidates"}) {
            var response = request(bob, "GET", path, null);
            assertEquals(200, response.statusCode(), path + ": " + response.body());
            assertFalse(response.body().contains("Fish"), path);
        }
    }
    @Test void salesWasteAndRollbackKeepOwnersSeparate() throws Exception {
        JsonObject sale = json("{\"foodItemId\":" + fish + ",\"quantitySold\":2,\"unitPrice\":1000,\"clientRequestId\":\"shared-" + UUID.randomUUID() + "\"}");
        assertTrue(request(bob, "POST", "/api/sales", sale).statusCode() >= 400);
        assertEquals(201, request(alice, "POST", "/api/sales", sale).statusCode());
        sale.addProperty("foodItemId", milk);
        var bobSale = request(bob, "POST", "/api/sales", sale);
        assertEquals(201, bobSale.statusCode(), bobSale.body());
        assertFalse(bobSale.body().contains("Fish"));
        JsonObject waste = json("{\"foodItemId\":" + fish + ",\"quantityWasted\":3,\"reason\":\"SPOILED\"}");
        assertTrue(request(bob, "POST", "/api/waste", waste).statusCode() >= 400);
        var ownWaste = request(alice, "POST", "/api/waste", waste);
        assertEquals(201, ownWaste.statusCode(), ownWaste.body());
        var dao = new FoodItemDao();
        assertEquals(0, dao.findById(fish, aliceId).orElseThrow().getQuantity().compareTo(new java.math.BigDecimal("95")));
        assertEquals(0, dao.findById(milk, bobId).orElseThrow().getQuantity().compareTo(new java.math.BigDecimal("18")));
        sale.remove("clientRequestId"); sale.addProperty("quantitySold", 999);
        assertTrue(request(bob, "POST", "/api/sales", sale).statusCode() >= 400);
        assertEquals(0, dao.findById(milk, bobId).orElseThrow().getQuantity().compareTo(new java.math.BigDecimal("18")));
        assertFalse(request(bob, "GET", "/api/waste", null).body().contains("Fish"));
        assertFalse(request(alice, "GET", "/api/sales", null).body().contains("Milk"));
    }
    @Test void savedPredictionsRecommendationsAndDispatchesRejectOtherOwners() throws Exception {
        long item = inventory(alice, "Private dispatch fixture", "Produce", 20, "kg");
        long partner = json(request(alice, "GET", "/api/redistribution/recipients", null).body())
                .getAsJsonArray("data").get(0).getAsJsonObject().get("id").getAsLong();
        JsonObject payload = json("{\"foodItemId\":" + item + ",\"recipientId\":" + partner + ",\"quantity\":3,\"unit\":\"kg\",\"status\":\"PENDING\"}");
        assertTrue(request(bob,"POST","/api/redistribution",payload).statusCode() >= 400);
        var dispatch = request(alice,"POST","/api/redistribution",payload);
        assertEquals(201,dispatch.statusCode(),dispatch.body());
        long dispatchId = json(dispatch.body()).getAsJsonObject("data").get("id").getAsLong();
        assertEquals(404,request(bob,"PUT","/api/redistribution/"+dispatchId,json("{\"status\":\"COMPLETED\"}")).statusCode());
        assertFalse(request(bob,"GET","/api/redistribution",null).body().contains("Private dispatch fixture"));
        assertEquals(0,new FoodItemDao().findById(item,aliceId).orElseThrow().getQuantity().compareTo(new java.math.BigDecimal("17")));

        var recommendation = new com.foodwasteai.model.Recommendation();
        recommendation.setFoodItemId(item);recommendation.setUserId(aliceId);
        recommendation.setCategory(com.foodwasteai.model.Recommendation.Category.URGENT);
        recommendation.setRiskLevel(com.foodwasteai.model.Recommendation.RiskLevel.HIGH);
        recommendation.setTitle("Private action fixture");recommendation.setDescription("Private action fixture");
        recommendation.setEstimatedSavings(java.math.BigDecimal.ONE);
        new com.foodwasteai.dao.RecommendationDao().save(recommendation);
        assertEquals(404,request(bob,"PUT","/api/recommendations/"+recommendation.getId(),json("{\"status\":\"ACCEPTED\"}")).statusCode());
        assertFalse(request(bob,"GET","/api/recommendations",null).body().contains("Private action fixture"));
        var forecast = request(alice,"POST","/api/prediction/evaluate",new JsonObject());
        assertEquals(200,forecast.statusCode(),forecast.body());
        long predictionId=json(forecast.body()).getAsJsonObject("data").get("id").getAsLong();
        assertTrue(new com.foodwasteai.dao.PredictionDao().findItemsByPredictionId(predictionId,bobId).isEmpty());
        assertFalse(new com.foodwasteai.dao.PredictionDao().findItemsByPredictionId(predictionId,aliceId).isEmpty());
    }
}
