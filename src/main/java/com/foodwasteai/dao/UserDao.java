package com.foodwasteai.dao;

import com.foodwasteai.model.User;
import com.foodwasteai.util.ValidationUtils;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for User accounts with secure prepared statements.
 */
public class UserDao extends BaseDao {

    public Optional<User> findById(Long id) throws SQLException {
        String sql = "SELECT id, username, email, password_hash, full_name, role, active, created_at, updated_at " +
                     "FROM users WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToUser(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<User> findByUsername(String username) throws SQLException {
        String sql = "SELECT id, username, email, password_hash, full_name, role, active, created_at, updated_at " +
                     "FROM users WHERE username = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToUser(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<User> findByEmail(String email) throws SQLException {
        String sql = "SELECT id, username, email, password_hash, full_name, role, active, created_at, updated_at " +
                     "FROM users WHERE email = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToUser(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<User> findAll() throws SQLException {
        List<User> list = new ArrayList<>();
        String sql = "SELECT id, username, email, password_hash, full_name, role, active, created_at, updated_at " +
                     "FROM users ORDER BY id ASC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(mapResultSetToUser(rs));
            }
        }
        return list;
    }

    public User save(User user) throws SQLException {
        ValidationUtils.validateUser(user);
        String sql = "INSERT INTO users (username, email, password_hash, full_name, role, active) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, user.getUsername().trim());
            stmt.setString(2, user.getEmail().trim());
            stmt.setString(3, user.getPasswordHash());
            stmt.setString(4, user.getFullName().trim());
            stmt.setString(5, user.getRole().name());
            stmt.setBoolean(6, user.isActive());

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        user.setId(keys.getLong(1));
                    }
                }
            }
            logger.info("Saved user account: {} (ID: {})", user.getUsername(), user.getId());
            return user;
        }
    }

    public boolean update(User user) throws SQLException {
        ValidationUtils.validateUser(user);
        if (user.getId() == null) {
            throw new IllegalArgumentException("User ID is required for update operation");
        }
        String sql = "UPDATE users SET email = ?, full_name = ?, role = ?, active = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getEmail().trim());
            stmt.setString(2, user.getFullName().trim());
            stmt.setString(3, user.getRole().name());
            stmt.setBoolean(4, user.isActive());
            stmt.setLong(5, user.getId());

            return stmt.executeUpdate() > 0;
        }
    }

    public boolean delete(Long id) throws SQLException {
        String sql = "DELETE FROM users WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            return stmt.executeUpdate() > 0;
        }
    }

    private User mapResultSetToUser(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setEmail(rs.getString("email"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setFullName(rs.getString("full_name"));
        user.setRole(User.Role.valueOf(rs.getString("role")));
        user.setActive(rs.getBoolean("active"));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) user.setCreatedAt(created.toLocalDateTime());

        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) user.setUpdatedAt(updated.toLocalDateTime());

        return user;
    }
}
