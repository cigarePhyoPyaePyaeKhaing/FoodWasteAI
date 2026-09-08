package com.foodwasteai;

import com.foodwasteai.config.AppConfig;
import com.foodwasteai.config.DatabaseConfig;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Prevent test classes from sharing operational fixtures or growing AI workloads. */
public class DisposableDatabaseCleanup implements AfterAllCallback {
    @Override public void afterAll(ExtensionContext context) throws Exception {
        String host = AppConfig.getDbHost();
        if (!Boolean.getBoolean("foodwaste.testRun") || !AppConfig.getDbName().endsWith("_test")
                || !(host.equals("127.0.0.1") || host.equals("localhost"))) {
            throw new IllegalStateException("Fixture cleanup requires the disposable local test database");
        }
        try (var connection = DatabaseConfig.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM food_items");
                statement.executeUpdate("DELETE FROM predictions");
                connection.commit();
            } catch (Exception exception) { connection.rollback(); throw exception; }
        }
    }
}
