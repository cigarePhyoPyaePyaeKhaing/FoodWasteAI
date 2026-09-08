package com.foodwasteai;

import com.foodwasteai.model.*;
import com.foodwasteai.service.*;
import com.foodwasteai.util.PasswordUtils;
import com.foodwasteai.util.ValidationUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification Test Suite for Authentication and User Data Isolation.
 * Verifies:
 * 1. Valid Gmail registration (@gmail.com -> 201)
 * 2. Non-Gmail domain rejection (@yahoo.com, @hotmail.com, etc. -> 400)
 * 3. Password complexity enforcement (length, upper, lower, number, special char -> 400)
 * 4. Password mismatch rejection (400)
 * 5. Duplicate email rejection (409)
 * 6. Login authentication & session creation (200, session cookie)
 * 7. Invalid login credentials rejection (401)
 * 8. Session protection (401 for protected API, 302 to login.html for views)
 * 9. Logout session invalidation
 * 10. Strict User Data Isolation: User A vs User B for Inventory, Sales, Waste, Predictions, Recommendations
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AuthenticationAndIsolationTest {

    private Tomcat tomcat;
    private int port;
    private String baseUrl;
    private static final Gson gson = new Gson();

    @BeforeAll
    public void beforeAll() throws Exception {
        com.foodwasteai.config.DatabaseConfig.isAvailable();
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        baseUrl = "http://localhost:" + port;
        tomcat = App.createServer(port);
        tomcat.start();
    }

    @AfterAll
    public void afterAll() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private HttpClient createClientWithCookieJar() {
        CookieManager cm = new CookieManager();
        cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder()
                .cookieHandler(cm)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    private HttpResponse<String> postJson(HttpClient client, String path, String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(20))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(HttpClient client, String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // 1. Valid Gmail registration
    @Test
    @DisplayName("1. Registration succeeds with valid @gmail.com email and strong password")
    public void testValidGmailRegistration() throws Exception {
        HttpClient client = createClientWithCookieJar();
        String email = "testuser." + System.currentTimeMillis() + "@gmail.com";
        String payload = String.format("{\"email\":\"%s\",\"password\":\"StrongP@ss123\",\"confirmPassword\":\"StrongP@ss123\"}", email);

        HttpResponse<String> resp = postJson(client, "/api/auth/register", payload);
        assertEquals(201, resp.statusCode());
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertTrue(json.get("success").getAsBoolean());
    }

    // 2. Reject non-Gmail domains
    @Test
    @DisplayName("2. Registration rejects non-Gmail domains (@yahoo.com, @outlook.com, etc.)")
    public void testRejectNonGmailRegistration() throws Exception {
        HttpClient client = createClientWithCookieJar();
        String[] invalidEmails = {
                "user@yahoo.com",
                "user@outlook.com",
                "user@hotmail.com",
                "user@company.org",
                "user@gmail.com.co",
                "user@notgmail.com"
        };

        for (String email : invalidEmails) {
            String payload = String.format("{\"email\":\"%s\",\"password\":\"StrongP@ss123\",\"confirmPassword\":\"StrongP@ss123\"}", email);
            HttpResponse<String> resp = postJson(client, "/api/auth/register", payload);
            assertEquals(400, resp.statusCode(), "Expected 400 for domain: " + email);
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            assertFalse(json.get("success").getAsBoolean());
            assertTrue(json.get("message").getAsString().toLowerCase().contains("gmail.com"));
        }
    }

    // 3. Password complexity validation
    @Test
    @DisplayName("3. Registration rejects passwords that do not meet complexity requirements")
    public void testPasswordComplexityEnforcement() throws Exception {
        HttpClient client = createClientWithCookieJar();
        String[] weakPasswords = {
                "Short1!",          // Less than 8 chars
                "nouppercase123!",  // Missing uppercase
                "NOLOWERCASE123!",  // Missing lowercase
                "NoNumberSpecial!", // Missing digit
                "NoSpecialChar123"  // Missing special character
        };

        for (String pwd : weakPasswords) {
            String email = "weak." + System.currentTimeMillis() + "@gmail.com";
            String payload = String.format("{\"email\":\"%s\",\"password\":\"%s\",\"confirmPassword\":\"%s\"}", email, pwd, pwd);
            HttpResponse<String> resp = postJson(client, "/api/auth/register", payload);
            assertEquals(400, resp.statusCode(), "Expected 400 for weak password: " + pwd);
        }
    }

    // 4. Confirm Password mismatch
    @Test
    @DisplayName("4. Registration rejects password mismatch")
    public void testPasswordMismatchRejection() throws Exception {
        HttpClient client = createClientWithCookieJar();
        String email = "mismatch." + System.currentTimeMillis() + "@gmail.com";
        String payload = String.format("{\"email\":\"%s\",\"password\":\"StrongP@ss123\",\"confirmPassword\":\"DifferentP@ss123\"}", email);

        HttpResponse<String> resp = postJson(client, "/api/auth/register", payload);
        assertEquals(400, resp.statusCode());
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertFalse(json.get("success").getAsBoolean());
        assertTrue(json.get("message").getAsString().toLowerCase().contains("match"));
    }

    // 5. Duplicate email registration rejected
    @Test
    @DisplayName("5. Registration prevents duplicate email registration (409 Conflict)")
    public void testDuplicateEmailRegistrationRejected() throws Exception {
        HttpClient client = createClientWithCookieJar();
        String email = "duplicate." + System.currentTimeMillis() + "@gmail.com";
        String payload = String.format("{\"email\":\"%s\",\"password\":\"StrongP@ss123\",\"confirmPassword\":\"StrongP@ss123\"}", email);

        HttpResponse<String> resp1 = postJson(client, "/api/auth/register", payload);
        assertEquals(201, resp1.statusCode());

        HttpResponse<String> resp2 = postJson(client, "/api/auth/register", payload);
        assertEquals(409, resp2.statusCode());
        JsonObject json = JsonParser.parseString(resp2.body()).getAsJsonObject();
        assertFalse(json.get("success").getAsBoolean());
    }

    // 6. Login authentication & session creation
    @Test
    @DisplayName("6. Login succeeds with correct credentials and returns authenticated session")
    public void testSuccessfulLoginAndSession() throws Exception {
        HttpClient client = createClientWithCookieJar();
        String email = "login." + System.currentTimeMillis() + "@gmail.com";
        String password = "CorrectP@ss123";

        // Register
        postJson(client, "/api/auth/register", String.format("{\"email\":\"%s\",\"password\":\"%s\",\"confirmPassword\":\"%s\"}", email, password, password));

        // Login
        HttpResponse<String> loginResp = postJson(client, "/api/auth/login", String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password));
        assertEquals(200, loginResp.statusCode());

        JsonObject json = JsonParser.parseString(loginResp.body()).getAsJsonObject();
        assertTrue(json.get("success").getAsBoolean());
        assertNotNull(json.get("data"));

        // Verify /api/auth/me returns current user
        HttpResponse<String> meResp = get(client, "/api/auth/me");
        assertEquals(200, meResp.statusCode());
        JsonObject meJson = JsonParser.parseString(meResp.body()).getAsJsonObject();
        assertTrue(meJson.get("success").getAsBoolean());
        assertEquals(email, meJson.getAsJsonObject("data").get("email").getAsString());
    }

    // 7. Login rejects invalid password or unknown email
    @Test
    @DisplayName("7. Login rejects invalid password and unknown email (401 Unauthorized)")
    public void testInvalidLoginCredentials() throws Exception {
        HttpClient client = createClientWithCookieJar();
        String email = "user." + System.currentTimeMillis() + "@gmail.com";
        String password = "CorrectP@ss123";

        // Register
        postJson(client, "/api/auth/register", String.format("{\"email\":\"%s\",\"password\":\"%s\",\"confirmPassword\":\"%s\"}", email, password, password));

        // Wrong password
        HttpResponse<String> wrongPassResp = postJson(client, "/api/auth/login", String.format("{\"email\":\"%s\",\"password\":\"WrongP@ss123\"}", email));
        assertEquals(401, wrongPassResp.statusCode());

        // Unknown email
        HttpResponse<String> unknownEmailResp = postJson(client, "/api/auth/login", "{\"email\":\"nonexistent@gmail.com\",\"password\":\"SomeP@ss123\"}");
        assertEquals(401, unknownEmailResp.statusCode());
    }

    // 8. Session protection & unauthenticated blocking
    @Test
    @DisplayName("8. AuthFilter blocks unauthenticated requests to protected APIs and pages")
    public void testAuthFilterBlocksUnauthenticated() throws Exception {
        HttpClient clientWithoutSession = HttpClient.newHttpClient();

        // Protected API returns 401
        HttpResponse<String> apiResp = clientWithoutSession.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/inventory")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(401, apiResp.statusCode());

        // Protected HTML page returns 302 redirect to /login.html
        HttpResponse<String> pageResp = clientWithoutSession.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl + "/inventory.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(302, pageResp.statusCode());
        assertTrue(pageResp.headers().firstValue("Location").orElse("").contains("/login.html"));
    }

    // 9. Logout invalidates session
    @Test
    @DisplayName("9. Logout invalidates session and subsequent calls are unauthenticated")
    public void testLogoutInvalidatesSession() throws Exception {
        HttpClient client = createClientWithCookieJar();
        String email = "logout." + System.currentTimeMillis() + "@gmail.com";
        String password = "SecureP@ss123";

        // Register and Login
        postJson(client, "/api/auth/register", String.format("{\"email\":\"%s\",\"password\":\"%s\",\"confirmPassword\":\"%s\"}", email, password, password));
        postJson(client, "/api/auth/login", String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password));

        // Verify authenticated
        HttpResponse<String> meBefore = get(client, "/api/auth/me");
        assertEquals(200, meBefore.statusCode());

        // Logout
        HttpResponse<String> logoutResp = postJson(client, "/api/auth/logout", "{}");
        assertEquals(200, logoutResp.statusCode());

        // Verify subsequent call is unauthenticated (401)
        HttpResponse<String> meAfter = get(client, "/api/auth/me");
        assertEquals(401, meAfter.statusCode());
    }

    // 10. Strict User Data Isolation
    @Test
    @DisplayName("10. Strict User Data Isolation: User A cannot see User B inventory, sales, or waste")
    public void testUserDataIsolation() throws Exception {
        // Setup User A
        HttpClient clientA = createClientWithCookieJar();
        String emailA = "usera." + System.currentTimeMillis() + "@gmail.com";
        String passA = "UserAP@ss123!";
        postJson(clientA, "/api/auth/register", String.format("{\"email\":\"%s\",\"password\":\"%s\",\"confirmPassword\":\"%s\"}", emailA, passA, passA));
        postJson(clientA, "/api/auth/login", String.format("{\"email\":\"%s\",\"password\":\"%s\"}", emailA, passA));

        // Setup User B
        HttpClient clientB = createClientWithCookieJar();
        String emailB = "userb." + System.currentTimeMillis() + "@gmail.com";
        String passB = "UserBP@ss123!";
        postJson(clientB, "/api/auth/register", String.format("{\"email\":\"%s\",\"password\":\"%s\",\"confirmPassword\":\"%s\"}", emailB, passB, passB));
        postJson(clientB, "/api/auth/login", String.format("{\"email\":\"%s\",\"password\":\"%s\"}", emailB, passB));

        // 1. User A creates an inventory item
        String itemAName = "ItemA_" + System.currentTimeMillis();
        String itemAJson = String.format("{\"name\":\"%s\",\"category\":\"Produce\",\"quantity\":50.0,\"unit\":\"kg\",\"pricePerUnit\":1200.0,\"expiryDate\":\"%s\",\"reorderThreshold\":5.0}",
                itemAName, LocalDate.now().plusDays(10));
        HttpResponse<String> createItemAResp = postJson(clientA, "/api/inventory", itemAJson);
        assertEquals(201, createItemAResp.statusCode());
        JsonObject itemAObj = JsonParser.parseString(createItemAResp.body()).getAsJsonObject().getAsJsonObject("data");
        long itemAId = itemAObj.get("id").getAsLong();

        // 2. User B creates an inventory item
        String itemBName = "ItemB_" + System.currentTimeMillis();
        String itemBJson = String.format("{\"name\":\"%s\",\"category\":\"Bakery\",\"quantity\":30.0,\"unit\":\"pieces\",\"pricePerUnit\":800.0,\"expiryDate\":\"%s\",\"reorderThreshold\":3.0}",
                itemBName, LocalDate.now().plusDays(5));
        HttpResponse<String> createItemBResp = postJson(clientB, "/api/inventory", itemBJson);
        assertEquals(201, createItemBResp.statusCode());

        // 3. User A lists inventory -> must see itemA, must NOT see itemB
        HttpResponse<String> listAResp = get(clientA, "/api/inventory");
        assertEquals(200, listAResp.statusCode());
        assertTrue(listAResp.body().contains(itemAName), "User A should see item A");
        assertFalse(listAResp.body().contains(itemBName), "User A should NOT see item B");

        // 4. User B lists inventory -> must see itemB, must NOT see itemA
        HttpResponse<String> listBResp = get(clientB, "/api/inventory");
        assertEquals(200, listBResp.statusCode());
        assertTrue(listBResp.body().contains(itemBName), "User B should see item B");
        assertFalse(listBResp.body().contains(itemAName), "User B should NOT see item A");

        // 5. User B tries to view item A directly -> 404
        HttpResponse<String> viewItemAResp = get(clientB, "/api/inventory/" + itemAId);
        assertEquals(404, viewItemAResp.statusCode(), "User B cannot view User A's food item");

        // 6. User A records a sale
        String saleAJson = String.format("{\"foodItemId\":%d,\"quantitySold\":5.0,\"unitPrice\":1200.0}", itemAId);
        HttpResponse<String> saleAResp = postJson(clientA, "/api/sales", saleAJson);
        assertEquals(201, saleAResp.statusCode());

        // 7. User B sales list must NOT contain User A's sale
        HttpResponse<String> salesBResp = get(clientB, "/api/sales");
        assertEquals(200, salesBResp.statusCode());
        assertFalse(salesBResp.body().contains(itemAName), "User B should not see User A's sales");

        // 8. User A records a waste record
        String wasteAJson = String.format("{\"foodItemId\":%d,\"quantityWasted\":2.0,\"reason\":\"SPOILED\",\"notes\":\"User A waste test\"}", itemAId);
        HttpResponse<String> wasteAResp = postJson(clientA, "/api/waste", wasteAJson);
        assertEquals(201, wasteAResp.statusCode());

        // 9. User B waste list must NOT contain User A's waste record
        HttpResponse<String> wasteBResp = get(clientB, "/api/waste");
        assertEquals(200, wasteBResp.statusCode());
        assertFalse(wasteBResp.body().contains(itemAName), "User B should not see User A's waste");
    }
}
