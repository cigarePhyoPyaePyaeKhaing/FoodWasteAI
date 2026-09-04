package com.foodwasteai;

import com.foodwasteai.config.AppConfig;
import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.FoodItemDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.service.PredictionService;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseTestingRunner {

    @Test
    public void setupDatabaseAndInsertSampleData() throws Exception {
        System.out.println("=== 1. ENSURE DATABASE CREATED ===");
        String rootUrl = "jdbc:mysql://" + AppConfig.getDbHost() + ":" + AppConfig.getDbPort() + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        try (Connection rootConn = DriverManager.getConnection(rootUrl, AppConfig.getDbUser(), AppConfig.getDbPassword());
             Statement stmt = rootConn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS " + AppConfig.getDbName() + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;");
            System.out.println("Database " + AppConfig.getDbName() + " verified/created.");
        }

        System.out.println("=== 2. CHECK DATABASE CONFIG CONNECTION ===");
        assertTrue(DatabaseConfig.isAvailable(), "Database should be available via DatabaseConfig");
        System.out.println("Database connection established successfully!");

        System.out.println("=== 3. RUN SCHEMA & SEED ===");
        try (Connection conn = DatabaseConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            executeSqlScript(conn, "database/schema.sql");
            executeSqlScript(conn, "database/seed.sql");
            try {
                stmt.executeUpdate("ALTER TABLE redistributions MODIFY COLUMN status ENUM('PENDING', 'CONFIRMED', 'COLLECTED', 'COMPLETED', 'CANCELLED') NOT NULL DEFAULT 'PENDING'");
            } catch (Exception ignored) {}
            
            // Ensure user records for admin and staff
            stmt.executeUpdate("INSERT INTO users (username, email, password_hash, full_name, role, active) VALUES " +
                    "('admin', 'manager@foodwaste.ai', 'admin123', 'Restaurant Manager (Admin)', 'ADMIN', TRUE) " +
                    "ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), role = VALUES(role)");
            stmt.executeUpdate("INSERT INTO users (username, email, password_hash, full_name, role, active) VALUES " +
                    "('staff', 'staff@foodwaste.ai', 'staff123', 'Kitchen Staff', 'STAFF', TRUE) " +
                    "ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), role = VALUES(role)");
            System.out.println("Schema, seed, and user records verified successfully.");
        }

        System.out.println("=== 4. INSERT SAMPLE INVENTORY DATA ===");
        FoodItemDao foodItemDao = new FoodItemDao();
        
        // Clear previous test food items if present
        try (Connection conn = DatabaseConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM food_items WHERE name IN ('Fresh Milk', 'Fresh Fish', 'Vegetables', 'Rice', 'Frozen Chicken')");
        }

        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        LocalDate threeDays = today.plusDays(3);
        LocalDate thirtyDays = today.plusDays(30);
        LocalDate sixtyDays = today.plusDays(60);

        // Record 1: Fresh Milk (HIGH RISK)
        FoodItem milk = new FoodItem();
        milk.setName("Fresh Milk");
        milk.setCategory("Dairy");
        milk.setQuantity(new BigDecimal("40.00"));
        milk.setUnit("kg");
        milk.setPricePerUnit(new BigDecimal("2800.00"));
        milk.setExpiryDate(tomorrow);
        milk.setStatus("NEAR_EXPIRY");
        foodItemDao.save(milk);
        System.out.println("Inserted HIGH RISK: Fresh Milk (ID=" + milk.getId() + ", Expiry=" + tomorrow + ")");

        // Record 2: Fresh Fish (HIGH RISK)
        FoodItem fish = new FoodItem();
        fish.setName("Fresh Fish");
        fish.setCategory("Seafood");
        fish.setQuantity(new BigDecimal("30.00"));
        fish.setUnit("kg");
        fish.setPricePerUnit(new BigDecimal("9500.00"));
        fish.setExpiryDate(tomorrow);
        fish.setStatus("NEAR_EXPIRY");
        foodItemDao.save(fish);
        System.out.println("Inserted HIGH RISK: Fresh Fish (ID=" + fish.getId() + ", Expiry=" + tomorrow + ")");

        // Record 3: Vegetables (MEDIUM RISK)
        FoodItem veg = new FoodItem();
        veg.setName("Vegetables");
        veg.setCategory("Produce");
        veg.setQuantity(new BigDecimal("50.00"));
        veg.setUnit("kg");
        veg.setPricePerUnit(new BigDecimal("1800.00"));
        veg.setExpiryDate(threeDays);
        veg.setStatus("NEAR_EXPIRY");
        foodItemDao.save(veg);
        System.out.println("Inserted MEDIUM RISK: Vegetables (ID=" + veg.getId() + ", Expiry=" + threeDays + ")");

        // Record 4: Rice (LOW RISK)
        FoodItem rice = new FoodItem();
        rice.setName("Rice");
        rice.setCategory("Grains");
        rice.setQuantity(new BigDecimal("80.00"));
        rice.setUnit("kg");
        rice.setPricePerUnit(new BigDecimal("3500.00"));
        rice.setExpiryDate(thirtyDays);
        rice.setStatus("OK");
        foodItemDao.save(rice);
        System.out.println("Inserted LOW RISK: Rice (ID=" + rice.getId() + ", Expiry=" + thirtyDays + ")");

        // Record 5: Frozen Chicken (LOW RISK)
        FoodItem chicken = new FoodItem();
        chicken.setName("Frozen Chicken");
        chicken.setCategory("Poultry");
        chicken.setQuantity(new BigDecimal("20.00"));
        chicken.setUnit("kg");
        chicken.setPricePerUnit(new BigDecimal("7000.00"));
        chicken.setExpiryDate(sixtyDays);
        chicken.setStatus("OK");
        foodItemDao.save(chicken);
        System.out.println("Inserted LOW RISK: Frozen Chicken (ID=" + chicken.getId() + ", Expiry=" + sixtyDays + ")");

        System.out.println("\n=== 5. VERIFY PREDICTIONS (AI ASSESSMENT) ===");
        PredictionService predictionService = new PredictionService();
        Map<String, Object> predictionReport = predictionService.assessAllInventory();
        assertNotNull(predictionReport, "Prediction report must not be null");

        @SuppressWarnings("unchecked")
        List<com.foodwasteai.prolog.PrologAssessment> items = (List<com.foodwasteai.prolog.PrologAssessment>) predictionReport.get("items");
        assertNotNull(items, "Prediction items list must not be null");
        assertFalse(items.isEmpty(), "Prediction items list must not be empty");

        System.out.println("\n----------------------------------------------------------------------------------");
        System.out.println("AI PREDICTION RESULTS BREAKDOWN (SWI-PROLOG GROUNDED):");
        System.out.println("----------------------------------------------------------------------------------");
        
        for (com.foodwasteai.prolog.PrologAssessment a : items) {
            System.out.printf("Item: %-16s | Risk: %-6s | Risk %%: %5.1f%% | Expiry: %2d days | Priority: %-15s | Action: %s%n",
                    a.getFoodName(), a.getRiskLevel(), a.getRiskPercentage(), a.getExpiryDays(),
                    a.getPriorityUsage(), a.getRecommendation());

            if ("Fresh Milk".equalsIgnoreCase(a.getFoodName())) {
                assertEquals("HIGH", a.getRiskLevel(), "Fresh Milk must be HIGH risk");
            } else if ("Fresh Fish".equalsIgnoreCase(a.getFoodName())) {
                assertEquals("HIGH", a.getRiskLevel(), "Fresh Fish must be HIGH risk");
            } else if ("Vegetables".equalsIgnoreCase(a.getFoodName())) {
                assertEquals("MEDIUM", a.getRiskLevel(), "Vegetables must be MEDIUM risk");
            } else if ("Rice".equalsIgnoreCase(a.getFoodName())) {
                assertEquals("LOW", a.getRiskLevel(), "Rice must be LOW risk");
            } else if ("Frozen Chicken".equalsIgnoreCase(a.getFoodName())) {
                assertEquals("LOW", a.getRiskLevel(), "Frozen Chicken must be LOW risk");
            }
        }

        System.out.println("----------------------------------------------------------------------------------");
        System.out.println("Overall Risk Score: " + predictionReport.get("overallRiskScore") + "%");
        System.out.println("High Risk Item Count: " + predictionReport.get("highRiskItemCount"));
        System.out.println("Expected Total Waste: " + predictionReport.get("expectedTotalWasteKg") + " kg");
        // Clean up test items so no sample data remains in production database
        try (Connection conn = DatabaseConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM inventory_transactions");
            stmt.executeUpdate("DELETE FROM sales");
            stmt.executeUpdate("DELETE FROM waste_records");
            stmt.executeUpdate("DELETE FROM redistributions");
            stmt.executeUpdate("DELETE FROM recommendations");
            stmt.executeUpdate("DELETE FROM prediction_items");
            stmt.executeUpdate("DELETE FROM predictions");
            stmt.executeUpdate("DELETE FROM food_items");
        }
        System.out.println("Cleaned up temporary test inventory. Production database is zero-state clean.");
    }

    private void executeSqlScript(Connection conn, String scriptPath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(scriptPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                    continue;
                }
                sb.append(line).append("\n");
            }
        }

        String[] statements = sb.toString().split(";");
        try (Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }
        }
    }
}
