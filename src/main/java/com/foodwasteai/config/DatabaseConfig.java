package com.foodwasteai.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * High-performance JDBC connection pool manager for MySQL (Aiven / Local).
 * Provides safe fallback handling so the web application can boot even when
 * database credentials have not been configured yet during early development.
 */
public class DatabaseConfig {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);
    private static HikariDataSource dataSource;
    private static boolean initializationAttempted = false;
    private static boolean available = false;

    private static synchronized void initialize() {
        if (initializationAttempted) {
            return;
        }
        initializationAttempted = true;

        String host = AppConfig.getDbHost();
        int port = AppConfig.getDbPort();
        String dbName = AppConfig.getDbName();
        String user = AppConfig.getDbUser();
        String password = AppConfig.getDbPassword();
        String sslMode = AppConfig.getDbSslMode();

        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=true&sslMode=%s&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
                host, port, dbName, sslMode
        );

        logger.info("Initializing HikariCP DataSource for MySQL at {}:{}/{}", host, port, dbName);

        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(user);
            config.setPassword(password);
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");

            config.setMinimumIdle(AppConfig.getInt("DB_POOL_MIN_IDLE", 2));
            config.setMaximumPoolSize(AppConfig.getInt("DB_POOL_MAX_SIZE", 10));
            config.setConnectionTimeout(AppConfig.getInt("DB_POOL_TIMEOUT", 5000)); // 5s fast timeout
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);
            config.setPoolName("FoodWasteAI-HikariPool");

            // Recommended MySQL performance properties
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");

            dataSource = new HikariDataSource(config);

            // Test connection
            try (Connection conn = dataSource.getConnection()) {
                if (conn.isValid(2)) {
                    available = true;
                    logger.info("Database connection established successfully!");
                }
            }
        } catch (Exception e) {
            logger.warn("Database connection could not be established: {}. Running in offline/mock fallback mode.", e.getMessage());
            available = false;
        }
    }

    public static DataSource getDataSource() {
        if (!initializationAttempted) {
            initialize();
        }
        return dataSource;
    }

    public static Connection getConnection() throws SQLException {
        DataSource ds = getDataSource();
        if (ds == null || !available) {
            throw new SQLException("Database connection is currently unavailable. Please verify Aiven MySQL credentials.");
        }
        return ds.getConnection();
    }

    public static boolean isAvailable() {
        if (!initializationAttempted) {
            initialize();
        }
        return available;
    }

    public static synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("Closing HikariCP DataSource");
            dataSource.close();
            dataSource = null;
            available = false;
            initializationAttempted = false;
        }
    }
}
