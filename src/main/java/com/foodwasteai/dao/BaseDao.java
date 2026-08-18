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
 * connection pooling access, and resource cleanup utilities.
 */
public abstract class BaseDao {
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected Connection getConnection() throws SQLException {
        return DatabaseConfig.getConnection();
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
