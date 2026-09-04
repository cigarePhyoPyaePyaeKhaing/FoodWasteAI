package com.foodwasteai.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * High-performance JDBC connection pool manager for MySQL (Aiven / Production / Local).
 * Enforces production connection validity without silent mock fallbacks in production.
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
        boolean isProd = AppConfig.isProduction();

        logger.info("Initializing HikariCP DataSource for MySQL at {}:{}/{} (SSL Mode: {}, Environment: {})",
                host, port, dbName, sslMode, AppConfig.getAppEnv());

        if (isProd && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))) {
            logger.error("[FATAL CONFIGURATION ERROR] APP_ENV=production is active, but DB_HOST is configured as '{}'. " +
                    "Production strictly requires a remote database (e.g. Aiven MySQL host: mysql-33833560-foodwasteai.h.aivencloud.com). " +
                    "Please configure DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD in Railway environment variables.", host);
        }

        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=true&sslMode=%s&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
                host, port, dbName, sslMode
        );

        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(user);
            config.setPassword(password);
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");

            config.setMinimumIdle(AppConfig.getInt("DB_POOL_MIN_IDLE", 2));
            config.setMaximumPoolSize(AppConfig.getInt("DB_POOL_MAX_SIZE", 10));
            config.setConnectionTimeout(AppConfig.getInt("DB_POOL_TIMEOUT", 8000));
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);
            config.setPoolName("FoodWasteAI-HikariPool");

            // Recommended MySQL performance properties
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");

            dataSource = new HikariDataSource(config);

            // Test connection and apply migrations
            try (Connection conn = dataSource.getConnection()) {
                if (conn.isValid(3)) {
                    available = true;
                    applyBilingualMigrations(conn);
                    ensureDefaultUsersExist(conn);
                    ensureDefaultRecipientsExist(conn);
                    logger.info("Production database connection established successfully to {}:{}/{}!", host, port, dbName);
                }
            }
        } catch (Exception e) {
            if (isProd) {
                logger.error("[FATAL DATABASE ERROR] Production database connection to {}:{}/{} failed: {}. " +
                        "In-memory mock fallback is strictly disabled in production mode.", host, port, dbName, e.getMessage());
            } else {
                logger.warn("Database connection could not be established to {}:{}/{}: {}. Running in development offline/mock fallback mode.",
                        host, port, dbName, e.getMessage());
            }
            available = false;
        }
    }

    public static void ensureDefaultRecipientsExist(Connection conn) {
        try {
            // 1. Ensure table exists
            String createTableSql = "CREATE TABLE IF NOT EXISTS redistribution_recipients (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(150) NOT NULL, " +
                    "organization_type VARCHAR(100) NOT NULL, " +
                    "contact_person VARCHAR(100) NOT NULL, " +
                    "phone VARCHAR(50) NOT NULL, " +
                    "email VARCHAR(100), " +
                    "address TEXT NOT NULL, " +
                    "active BOOLEAN NOT NULL DEFAULT TRUE, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
            try (java.sql.Statement createStmt = conn.createStatement()) {
                createStmt.executeUpdate(createTableSql);
            } catch (SQLException e) {
                logger.debug("Table check/creation error for redistribution_recipients: {}", e.getMessage());
            }

            // 2. Check active recipients count
            String countSql = "SELECT COUNT(*) FROM redistribution_recipients WHERE active = TRUE";
            int activeCount = 0;
            try (java.sql.PreparedStatement countStmt = conn.prepareStatement(countSql);
                 java.sql.ResultSet rs = countStmt.executeQuery()) {
                if (rs.next()) {
                    activeCount = rs.getInt(1);
                }
            }

            if (activeCount == 0) {
                logger.info("No active redistribution recipients found in database. Seeding standard reference partner records.");
                String insertSql = "INSERT INTO redistribution_recipients (id, name, organization_type, contact_person, phone, email, address, active) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE name=VALUES(name), organization_type=VALUES(organization_type), " +
                        "contact_person=VALUES(contact_person), phone=VALUES(phone), email=VALUES(email), " +
                        "address=VALUES(address), active=VALUES(active)";

                Object[][] defaultRecipients = {
                    {1L, "Hope Community Food Bank", "Food Bank", "Daw Khin Win", "+95 9 450012345", "contact@hopefoodbank.org", "124 Inya Road, Kamayut, Yangon", true},
                    {2L, "City Youth Shelter & Kitchen", "Soup Kitchen", "U Min Naing", "+95 9 790098765", "kitchen@cityshelter.org", "45 Merchant Street, Kyauktada, Yangon", true},
                    {3L, "GreenEarth Animal Sanctuary", "Animal Rescue", "Ma Thin Thin", "+95 9 260055443", "info@greenearthrescue.org", "88 Htauk Kyant Road, Mingaladon", true},
                    {4L, "Circular BioCompost Hub", "Composting", "Ko Thet Aung", "+95 9 310022110", "ops@biocomposthub.com", "Plot 12, Industrial Zone, South Dagon", true}
                };

                try (java.sql.PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    for (Object[] r : defaultRecipients) {
                        insertStmt.setLong(1, (Long) r[0]);
                        insertStmt.setString(2, (String) r[1]);
                        insertStmt.setString(3, (String) r[2]);
                        insertStmt.setString(4, (String) r[3]);
                        insertStmt.setString(5, (String) r[4]);
                        insertStmt.setString(6, (String) r[5]);
                        insertStmt.setString(7, (String) r[6]);
                        insertStmt.setBoolean(8, (Boolean) r[7]);
                        insertStmt.addBatch();
                    }
                    insertStmt.executeBatch();
                    logger.info("Successfully seeded 4 redistribution recipient partner records.");
                }
            }
        } catch (SQLException e) {
            logger.warn("Could not verify/seed redistribution recipients: {}", e.getMessage());
        }
    }

    private static void ensureDefaultUsersExist(Connection conn) {
        try {
            // 1. Ensure 'user' exists and is active
            String checkUserSql = "SELECT id, password_hash, active FROM users WHERE LOWER(TRIM(username)) = 'user' LIMIT 1";
            try (java.sql.PreparedStatement checkStmt = conn.prepareStatement(checkUserSql);
                 java.sql.ResultSet rs = checkStmt.executeQuery()) {
                if (!rs.next()) {
                    logger.info("User 'user' not found in database. Creating default user account.");
                    String insertSql = "INSERT INTO users (username, email, password_hash, full_name, role, active) VALUES (?, ?, ?, ?, ?, ?)";
                    try (java.sql.PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, "user");
                        insertStmt.setString(2, "user@foodwaste.ai");
                        insertStmt.setString(3, "user123");
                        insertStmt.setString(4, "User");
                        insertStmt.setString(5, "ADMIN");
                        insertStmt.setBoolean(6, true);
                        insertStmt.executeUpdate();
                        logger.info("Default 'user' account created successfully.");
                    }
                }
            }

            // 2. Ensure 'admin' user exists and is active
            String checkAdminSql = "SELECT id, password_hash, active FROM users WHERE LOWER(TRIM(username)) = 'admin' LIMIT 1";
            try (java.sql.PreparedStatement checkStmt = conn.prepareStatement(checkAdminSql);
                 java.sql.ResultSet rs = checkStmt.executeQuery()) {
                if (!rs.next()) {
                    logger.info("Admin user not found in database. Creating default admin account.");
                    String insertSql = "INSERT INTO users (username, email, password_hash, full_name, role, active) VALUES (?, ?, ?, ?, ?, ?)";
                    try (java.sql.PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, "admin");
                        insertStmt.setString(2, "admin@foodwaste.ai");
                        insertStmt.setString(3, "admin123");
                        insertStmt.setString(4, "Restaurant Manager");
                        insertStmt.setString(5, "ADMIN");
                        insertStmt.setBoolean(6, true);
                        insertStmt.executeUpdate();
                        logger.info("Default admin user created successfully.");
                    }
                }
            }

            // 3. Ensure 'staff' user exists and is active
            String checkStaffSql = "SELECT id, password_hash, active FROM users WHERE LOWER(TRIM(username)) = 'staff' LIMIT 1";
            try (java.sql.PreparedStatement checkStmt = conn.prepareStatement(checkStaffSql);
                 java.sql.ResultSet rs = checkStmt.executeQuery()) {
                if (!rs.next()) {
                    logger.info("Staff user not found in database. Creating default staff account.");
                    String insertSql = "INSERT INTO users (username, email, password_hash, full_name, role, active) VALUES (?, ?, ?, ?, ?, ?)";
                    try (java.sql.PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, "staff");
                        insertStmt.setString(2, "staff@foodwaste.ai");
                        insertStmt.setString(3, "staff123");
                        insertStmt.setString(4, "Sarah Jenkins");
                        insertStmt.setString(5, "STAFF");
                        insertStmt.setBoolean(6, true);
                        insertStmt.executeUpdate();
                        logger.info("Default staff user created successfully.");
                    }
                }
            }
        } catch (SQLException e) {
            logger.debug("Could not verify/seed users: {}", e.getMessage());
        }
    }

    private static void applyBilingualMigrations(Connection conn) {
        String[] migrations = {
            "ALTER TABLE prediction_items ADD COLUMN reasoning_text_en TEXT",
            "ALTER TABLE prediction_items ADD COLUMN reasoning_text_my TEXT",
            "ALTER TABLE recommendations ADD COLUMN title_en VARCHAR(200)",
            "ALTER TABLE recommendations ADD COLUMN title_my VARCHAR(200)",
            "ALTER TABLE recommendations ADD COLUMN description_en TEXT",
            "ALTER TABLE recommendations ADD COLUMN description_my TEXT",
            "ALTER TABLE recommendations ADD COLUMN reasoning_details_en TEXT",
            "ALTER TABLE recommendations ADD COLUMN reasoning_details_my TEXT",
            "ALTER TABLE redistributions ADD COLUMN notes_en TEXT",
            "ALTER TABLE redistributions ADD COLUMN notes_my TEXT"
        };

        for (String sql : migrations) {
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
            } catch (SQLException ignored) {
                // Column already exists or table not yet created
            }
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
            if (AppConfig.isProduction()) {
                throw new SQLException("CRITICAL: Production database is unavailable at " + AppConfig.getDbHost() + ":" +
                        AppConfig.getDbPort() + "/" + AppConfig.getDbName() + ". Check Railway DB environment variables and Aiven MySQL status.");
            }
            throw new SQLException("Database connection is currently unavailable. Please verify MySQL credentials.");
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
