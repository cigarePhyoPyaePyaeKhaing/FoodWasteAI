package com.foodwasteai;

import com.foodwasteai.config.AppConfig;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProductionConfigAndAuthLoopTest {

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
    @DisplayName("Direct Dashboard Access: GET / returns 200 without redirect or session requirement")
    public void testRootOpensDashboardDirectly() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/"))
                .header("Accept", "text/html")
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "Root path must return HTTP 200 directly");
        assertTrue(resp.body().contains("Dashboard"));
    }

    @Test
    @DisplayName("Direct Dashboard Access: GET /dashboard.html returns 200 without session")
    public void testDashboardReturns200Directly() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/dashboard.html"))
                .header("Accept", "text/html")
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "Dashboard request must return 200 OK without session");
        assertTrue(resp.body().contains("Dashboard"));
    }
}
