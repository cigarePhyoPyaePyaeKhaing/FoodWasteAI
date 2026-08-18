package com.foodwasteai.dao;

import com.foodwasteai.model.InventoryTransaction;
import com.foodwasteai.util.ValidationUtils;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for Inventory stock transactions.
 */
public class InventoryTransactionDao extends BaseDao {

    public InventoryTransaction save(InventoryTransaction tx) throws SQLException {
        ValidationUtils.validateInventoryTransaction(tx);
        String sql = "INSERT INTO inventory_transactions (food_item_id, transaction_type, quantity, unit, notes, created_by) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, tx.getFoodItemId());
            stmt.setString(2, tx.getTransactionType().name());
            stmt.setBigDecimal(3, tx.getQuantity());
            stmt.setString(4, tx.getUnit() != null ? tx.getUnit().trim() : "kg");
            stmt.setString(5, tx.getNotes());
            if (tx.getCreatedBy() != null) {
                stmt.setLong(6, tx.getCreatedBy());
            } else {
                stmt.setNull(6, Types.BIGINT);
            }

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        tx.setId(keys.getLong(1));
                    }
                }
            }
            logger.info("Recorded inventory transaction: {} of item #{}", tx.getTransactionType(), tx.getFoodItemId());
            return tx;
        }
    }

    public List<InventoryTransaction> findByFoodItemId(Long foodItemId) throws SQLException {
        List<InventoryTransaction> list = new ArrayList<>();
        String sql = "SELECT t.id, t.food_item_id, f.name AS food_name, t.transaction_type, t.quantity, t.unit, " +
                     "t.notes, t.created_by, u.full_name AS user_name, t.created_at " +
                     "FROM inventory_transactions t " +
                     "LEFT JOIN food_items f ON t.food_item_id = f.id " +
                     "LEFT JOIN users u ON t.created_by = u.id " +
                     "WHERE t.food_item_id = ? ORDER BY t.created_at DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, foodItemId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToTransaction(rs));
                }
            }
        }
        return list;
    }

    public List<InventoryTransaction> findRecent(int limit) throws SQLException {
        List<InventoryTransaction> list = new ArrayList<>();
        String sql = "SELECT t.id, t.food_item_id, f.name AS food_name, t.transaction_type, t.quantity, t.unit, " +
                     "t.notes, t.created_by, u.full_name AS user_name, t.created_at " +
                     "FROM inventory_transactions t " +
                     "LEFT JOIN food_items f ON t.food_item_id = f.id " +
                     "LEFT JOIN users u ON t.created_by = u.id " +
                     "ORDER BY t.created_at DESC LIMIT ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToTransaction(rs));
                }
            }
        }
        return list;
    }

    private InventoryTransaction mapResultSetToTransaction(ResultSet rs) throws SQLException {
        InventoryTransaction tx = new InventoryTransaction();
        tx.setId(rs.getLong("id"));
        tx.setFoodItemId(rs.getLong("food_item_id"));
        tx.setFoodItemName(rs.getString("food_name"));
        tx.setTransactionType(InventoryTransaction.Type.valueOf(rs.getString("transaction_type")));
        tx.setQuantity(rs.getBigDecimal("quantity"));
        tx.setUnit(rs.getString("unit"));
        tx.setNotes(rs.getString("notes"));

        long createdBy = rs.getLong("created_by");
        if (!rs.wasNull()) tx.setCreatedBy(createdBy);
        tx.setCreatedByName(rs.getString("user_name"));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) tx.setCreatedAt(created.toLocalDateTime());

        return tx;
    }
}
