package com.foodwasteai;

import com.foodwasteai.config.DatabaseConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseCleanupTest {

    @Test
    @DisplayName("Clean all default/demo/test inventory data and verify production empty state")
    public void cleanupDefaultInventoryData() throws Exception {
        if (!DatabaseConfig.isAvailable()) {
            return;
        }

        try (Connection conn = DatabaseConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            // Delete all child tables and food_items
            stmt.executeUpdate("DELETE FROM inventory_transactions");
            stmt.executeUpdate("DELETE FROM sales");
            stmt.executeUpdate("DELETE FROM waste_records");
            stmt.executeUpdate("DELETE FROM redistributions");
            stmt.executeUpdate("DELETE FROM recommendations");
            stmt.executeUpdate("DELETE FROM prediction_items");
            stmt.executeUpdate("DELETE FROM predictions");
            stmt.executeUpdate("DELETE FROM food_items");

            // Verify food_items is completely empty
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM food_items")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "food_items table must be empty in production");
            }

            // Verify redistribution_recipients table is preserved
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM redistribution_recipients")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "redistribution_recipients table must be preserved");
            }

            // Verify users table is preserved
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "users table must be preserved");
            }
        }
    }
}
