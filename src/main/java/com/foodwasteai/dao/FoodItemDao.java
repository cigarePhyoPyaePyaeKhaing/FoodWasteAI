package com.foodwasteai.dao;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.util.ValidationUtils;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for Food Items (Inventory) with prepared statements.
 */
public class FoodItemDao extends BaseDao {

    public Optional<FoodItem> findById(Long id) throws SQLException {
        String sql = "SELECT id, name, category, quantity, unit, price_per_unit, expiry_date, min_stock_threshold, status, created_at, updated_at " +
                     "FROM food_items WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToFoodItem(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<FoodItem> findAll() throws SQLException {
        List<FoodItem> list = new ArrayList<>();
        String sql = "SELECT id, name, category, quantity, unit, price_per_unit, expiry_date, min_stock_threshold, status, created_at, updated_at " +
                     "FROM food_items ORDER BY expiry_date ASC, name ASC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(mapResultSetToFoodItem(rs));
            }
        }
        return list;
    }

    public List<FoodItem> findByCategory(String category) throws SQLException {
        List<FoodItem> list = new ArrayList<>();
        String sql = "SELECT id, name, category, quantity, unit, price_per_unit, expiry_date, min_stock_threshold, status, created_at, updated_at " +
                     "FROM food_items WHERE category = ? ORDER BY expiry_date ASC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, category);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToFoodItem(rs));
                }
            }
        }
        return list;
    }

    public List<FoodItem> findNearExpiry(int daysThreshold) throws SQLException {
        List<FoodItem> list = new ArrayList<>();
        String sql = "SELECT id, name, category, quantity, unit, price_per_unit, expiry_date, min_stock_threshold, status, created_at, updated_at " +
                     "FROM food_items WHERE expiry_date <= DATE_ADD(CURDATE(), INTERVAL ? DAY) AND quantity > 0 ORDER BY expiry_date ASC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, daysThreshold);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToFoodItem(rs));
                }
            }
        }
        return list;
    }

    public List<FoodItem> findLowStock() throws SQLException {
        List<FoodItem> list = new ArrayList<>();
        String sql = "SELECT id, name, category, quantity, unit, price_per_unit, expiry_date, min_stock_threshold, status, created_at, updated_at " +
                     "FROM food_items WHERE quantity <= min_stock_threshold ORDER BY quantity ASC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(mapResultSetToFoodItem(rs));
            }
        }
        return list;
    }

    public FoodItem save(FoodItem item) throws SQLException {
        ValidationUtils.validateFoodItem(item);
        String sql = "INSERT INTO food_items (name, category, quantity, unit, price_per_unit, expiry_date, min_stock_threshold, status) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, item.getName().trim());
            stmt.setString(2, item.getCategory().trim());
            stmt.setBigDecimal(3, item.getQuantity());
            stmt.setString(4, item.getUnit().trim());
            stmt.setBigDecimal(5, item.getPricePerUnit());
            stmt.setDate(6, Date.valueOf(item.getExpiryDate()));
            stmt.setBigDecimal(7, item.getMinStockThreshold() != null ? item.getMinStockThreshold() : new BigDecimal("5.00"));
            stmt.setString(8, item.getStatus() != null ? item.getStatus() : "OK");

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        item.setId(keys.getLong(1));
                    }
                }
            }
            logger.info("Saved food item: {} (ID: {})", item.getName(), item.getId());
            return item;
        }
    }

    public boolean update(FoodItem item) throws SQLException {
        ValidationUtils.validateFoodItem(item);
        if (item.getId() == null) {
            throw new IllegalArgumentException("Food item ID is required for update");
        }
        String sql = "UPDATE food_items SET name = ?, category = ?, quantity = ?, unit = ?, price_per_unit = ?, " +
                     "expiry_date = ?, min_stock_threshold = ?, status = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, item.getName().trim());
            stmt.setString(2, item.getCategory().trim());
            stmt.setBigDecimal(3, item.getQuantity());
            stmt.setString(4, item.getUnit().trim());
            stmt.setBigDecimal(5, item.getPricePerUnit());
            stmt.setDate(6, Date.valueOf(item.getExpiryDate()));
            stmt.setBigDecimal(7, item.getMinStockThreshold());
            stmt.setString(8, item.getStatus());
            stmt.setLong(9, item.getId());

            return stmt.executeUpdate() > 0;
        }
    }

    public boolean updateQuantity(Long id, BigDecimal newQuantity) throws SQLException {
        String sql = "UPDATE food_items SET quantity = ?, status = CASE " +
                     "WHEN expiry_date < CURDATE() THEN 'EXPIRED' " +
                     "WHEN expiry_date <= DATE_ADD(CURDATE(), INTERVAL 2 DAY) THEN 'NEAR_EXPIRY' " +
                     "WHEN ? <= min_stock_threshold THEN 'LOW_STOCK' " +
                     "ELSE 'OK' END WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBigDecimal(1, newQuantity);
            stmt.setBigDecimal(2, newQuantity);
            stmt.setLong(3, id);

            return stmt.executeUpdate() > 0;
        }
    }

    public boolean delete(Long id) throws SQLException {
        String sql = "DELETE FROM food_items WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            return stmt.executeUpdate() > 0;
        }
    }

    private FoodItem mapResultSetToFoodItem(ResultSet rs) throws SQLException {
        FoodItem item = new FoodItem();
        item.setId(rs.getLong("id"));
        item.setName(rs.getString("name"));
        item.setCategory(rs.getString("category"));
        item.setQuantity(rs.getBigDecimal("quantity"));
        item.setUnit(rs.getString("unit"));
        item.setPricePerUnit(rs.getBigDecimal("price_per_unit"));

        Date expiry = rs.getDate("expiry_date");
        if (expiry != null) {
            item.setExpiryDate(expiry.toLocalDate());
            item.setStatus(com.foodwasteai.util.ExpiryStatusResolver.resolveStatus(item.getExpiryDate(), item.getQuantity(), item.getMinStockThreshold(), java.time.LocalDate.now()));
        } else {
            item.setStatus(rs.getString("status"));
        }

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) item.setCreatedAt(created.toLocalDateTime());

        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) item.setUpdatedAt(updated.toLocalDateTime());

        return item;
    }
}
