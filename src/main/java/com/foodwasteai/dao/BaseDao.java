package com.foodwasteai.dao;

import com.foodwasteai.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Base Data Access Object providing safe JDBC query execution,
 * connection pooling access, user foreign key validation, and resource cleanup utilities.
 */
public abstract class BaseDao {
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected Connection getConnection() throws SQLException {
        return DatabaseConfig.getConnection();
    }

    /**
     * Verifies that the given userId exists in the database users table.
     * If valid, returns the verified userId.
     * If not found or null, safely returns null to prevent foreign key constraint failures on inventory_transactions.
     */
    public Long resolveValidUserId(Connection conn, Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        String sql = "SELECT id FROM users WHERE id = ? LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        } catch (SQLException e) {
            logger.warn("Could not verify user ID {} in database: {}", userId, e.getMessage());
        }
        logger.warn("User ID {} not found in users table. Setting audit created_by to NULL to avoid foreign key failure.", userId);
        return null;
    }

    protected void closeQuietly(AutoCloseable... resources) {
        for (AutoCloseable res : resources) {
            if (res != null) {
                try {
                    res.close();
                } catch (Exception e) {
                    logger.debug("Error closing resource: {}", e.getMessage());
                }
            }
        }
    }
}
