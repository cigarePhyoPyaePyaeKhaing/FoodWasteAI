package com.foodwasteai.dao;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.util.ValidationUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for Food Items (Inventory) with prepared statements.
 */
public class FoodItemDao extends BaseDao {

    public Optional<FoodItem> findById(Long id) throws SQLException {
        String sql = "SELECT f.id, f.name, f.category, f.quantity, f.unit, f.price_per_unit, f.expiry_date, f.status, f.created_at, f.updated_at, " +
                     "GREATEST(COALESCE((SELECT SUM(t.quantity) FROM inventory_transactions t WHERE t.food_item_id = f.id AND t.transaction_type IN ('PURCHASE', 'STOCK_IN', 'MANUAL_COUNT')), 0), f.quantity) AS total_quantity " +
                     "FROM food_items f WHERE f.id = ?";
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
        String sql = "SELECT f.id, f.name, f.category, f.quantity, f.unit, f.price_per_unit, f.expiry_date, f.status, f.created_at, f.updated_at, " +
                     "GREATEST(COALESCE((SELECT SUM(t.quantity) FROM inventory_transactions t WHERE t.food_item_id = f.id AND t.transaction_type IN ('PURCHASE', 'STOCK_IN', 'MANUAL_COUNT')), 0), f.quantity) AS total_quantity " +
                     "FROM food_items f ORDER BY f.expiry_date ASC, f.name ASC";
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
        String sql = "SELECT f.id, f.name, f.category, f.quantity, f.unit, f.price_per_unit, f.expiry_date, f.status, f.created_at, f.updated_at, " +
                     "GREATEST(COALESCE((SELECT SUM(t.quantity) FROM inventory_transactions t WHERE t.food_item_id = f.id AND t.transaction_type IN ('PURCHASE', 'STOCK_IN', 'MANUAL_COUNT')), 0), f.quantity) AS total_quantity " +
                     "FROM food_items f WHERE f.category = ? ORDER BY f.expiry_date ASC";
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
        String sql = "SELECT f.id, f.name, f.category, f.quantity, f.unit, f.price_per_unit, f.expiry_date, f.status, f.created_at, f.updated_at, " +
                     "GREATEST(COALESCE((SELECT SUM(t.quantity) FROM inventory_transactions t WHERE t.food_item_id = f.id AND t.transaction_type IN ('PURCHASE', 'STOCK_IN', 'MANUAL_COUNT')), 0), f.quantity) AS total_quantity " +
                     "FROM food_items f WHERE f.expiry_date <= DATE_ADD(CURDATE(), INTERVAL ? DAY) AND f.quantity > 0 ORDER BY f.expiry_date ASC";
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
        return new ArrayList<>();
    }

    public FoodItem save(FoodItem item) throws SQLException {
        ValidationUtils.validateFoodItem(item);
        String sql = "INSERT INTO food_items (name, category, quantity, unit, price_per_unit, expiry_date, status) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, item.getName().trim());
            stmt.setString(2, item.getCategory().trim());
            stmt.setBigDecimal(3, item.getQuantity());
            stmt.setString(4, item.getUnit().trim());
            stmt.setBigDecimal(5, item.getPricePerUnit());
            stmt.setDate(6, Date.valueOf(item.getExpiryDate()));
            stmt.setString(7, item.getStatus() != null ? item.getStatus() : "OK");

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

    /**
     * Atomically adds stock by either merging into an existing matching inventory row
     * or creating a new inventory row if important attributes (normalized name, unit,
     * price_per_unit, or expiry_date) differ.
     * Transactionally inserts the inventory transaction audit record.
     */
    public FoodItem saveOrMergeStockWithTransaction(FoodItem item, Long userId) throws SQLException {
        ValidationUtils.validateFoodItem(item);

        String normName = item.getName() != null ? item.getName().trim() : "";
        String normUnit = item.getUnit() != null ? item.getUnit().trim() : "kg";
        BigDecimal price = item.getPricePerUnit() != null ? item.getPricePerUnit().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        LocalDate expiry = item.getExpiryDate();
        BigDecimal addedQty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;

        String selectMatchingSql = "SELECT id, name, category, quantity, unit, price_per_unit, expiry_date, status, created_at, updated_at " +
                                   "FROM food_items " +
                                   "WHERE LOWER(TRIM(name)) = LOWER(?) " +
                                   "  AND LOWER(TRIM(unit)) = LOWER(?) " +
                                   "  AND price_per_unit = ? " +
                                   "  AND expiry_date = ? " +
                                   "ORDER BY id ASC LIMIT 1 FOR UPDATE";

        String updateStockSql = "UPDATE food_items SET quantity = ?, status = CASE " +
                                "WHEN expiry_date < CURDATE() THEN 'EXPIRED' " +
                                "WHEN expiry_date <= DATE_ADD(CURDATE(), INTERVAL 2 DAY) THEN 'NEAR_EXPIRY' " +
                                "ELSE 'OK' END, updated_at = CURRENT_TIMESTAMP WHERE id = ?";

        String insertFoodSql = "INSERT INTO food_items (name, category, quantity, unit, price_per_unit, expiry_date, status) " +
                               "VALUES (?, ?, ?, ?, ?, ?, ?)";

        String insertTxSql = "INSERT INTO inventory_transactions (food_item_id, transaction_type, quantity, unit, notes, created_by) " +
                             "VALUES (?, 'PURCHASE', ?, ?, ?, ?)";

        Connection conn = null;
        boolean originalAutoCommit = true;
        try {
            conn = getConnection();
            originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            Long validUserId = resolveValidUserId(conn, userId);

            // 1. Search for an existing matching batch with lock
            FoodItem existingMatch = null;
            try (PreparedStatement stmt = conn.prepareStatement(selectMatchingSql)) {
                stmt.setString(1, normName);
                stmt.setString(2, normUnit);
                stmt.setBigDecimal(3, price);
                stmt.setDate(4, Date.valueOf(expiry));
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        existingMatch = mapResultSetToFoodItem(rs);
                    }
                }
            }

            if (existingMatch != null) {
                // CASE 1: MATCH FOUND -> Merge quantity into existing ID
                BigDecimal currentQty = existingMatch.getQuantity() != null ? existingMatch.getQuantity() : BigDecimal.ZERO;
                BigDecimal newQty = currentQty.add(addedQty);

                try (PreparedStatement updateStmt = conn.prepareStatement(updateStockSql)) {
                    updateStmt.setBigDecimal(1, newQty);
                    updateStmt.setLong(2, existingMatch.getId());
                    updateStmt.executeUpdate();
                }

                // Insert stock addition history record
                try (PreparedStatement txStmt = conn.prepareStatement(insertTxSql)) {
                    txStmt.setLong(1, existingMatch.getId());
                    txStmt.setBigDecimal(2, addedQty);
                    txStmt.setString(3, normUnit);
                    txStmt.setString(4, "Stock addition: +" + addedQty.stripTrailingZeros().toPlainString() + " " + normUnit);
                    if (validUserId != null) {
                        txStmt.setLong(5, validUserId);
                    } else {
                        txStmt.setNull(5, Types.BIGINT);
                    }
                    txStmt.executeUpdate();
                }

                conn.commit();

                // Query total stock-in for this item
                BigDecimal totalQty = newQty;
                try (PreparedStatement totalStmt = conn.prepareStatement(
                        "SELECT GREATEST(COALESCE(SUM(quantity), 0), ?) FROM inventory_transactions " +
                        "WHERE food_item_id = ? AND transaction_type IN ('PURCHASE', 'STOCK_IN', 'MANUAL_COUNT')")) {
                    totalStmt.setBigDecimal(1, newQty);
                    totalStmt.setLong(2, existingMatch.getId());
                    try (ResultSet trs = totalStmt.executeQuery()) {
                        if (trs.next()) {
                            BigDecimal val = trs.getBigDecimal(1);
                            if (val != null) totalQty = val;
                        }
                    }
                }

                existingMatch.setQuantity(newQty);
                existingMatch.setRemainingQuantity(newQty);
                existingMatch.setTotalQuantity(totalQty);
                existingMatch.updateComputedExpiryFields();
                logger.info("Merged stock addition for food item #{} ('{}'): added {} {}, new total {} {}",
                        existingMatch.getId(), existingMatch.getName(), addedQty, normUnit, newQty, normUnit);
                return existingMatch;

            } else {
                // CASE 2: NO MATCH -> Create brand new food_items row with a new ID
                long newId;
                try (PreparedStatement insertStmt = conn.prepareStatement(insertFoodSql, Statement.RETURN_GENERATED_KEYS)) {
                    insertStmt.setString(1, normName);
                    insertStmt.setString(2, item.getCategory() != null ? item.getCategory().trim() : "Kitchen Item");
                    insertStmt.setBigDecimal(3, addedQty);
                    insertStmt.setString(4, normUnit);
                    insertStmt.setBigDecimal(5, price);
                    insertStmt.setDate(6, Date.valueOf(expiry));
                    insertStmt.setString(7, item.getStatus() != null ? item.getStatus() : "OK");

                    insertStmt.executeUpdate();
                    try (ResultSet keys = insertStmt.getGeneratedKeys()) {
                        if (keys.next()) {
                            newId = keys.getLong(1);
                        } else {
                            throw new SQLException("Failed to retrieve generated ID for new food item");
                        }
                    }
                }

                // Insert initial stock addition transaction
                try (PreparedStatement txStmt = conn.prepareStatement(insertTxSql)) {
                    txStmt.setLong(1, newId);
                    txStmt.setBigDecimal(2, addedQty);
                    txStmt.setString(3, normUnit);
                    txStmt.setString(4, "Initial stock addition: +" + addedQty.stripTrailingZeros().toPlainString() + " " + normUnit);
                    if (validUserId != null) {
                        txStmt.setLong(5, validUserId);
                    } else {
                        txStmt.setNull(5, Types.BIGINT);
                    }
                    txStmt.executeUpdate();
                }

                conn.commit();

                item.setId(newId);
                item.setQuantity(addedQty);
                item.setRemainingQuantity(addedQty);
                item.setTotalQuantity(addedQty);
                item.setUnit(normUnit);
                item.setPricePerUnit(price);
                item.updateComputedExpiryFields();
                logger.info("Created new food item #{} ('{}') with initial stock {} {}",
                        newId, item.getName(), addedQty, normUnit);
                return item;
            }

        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    logger.error("Rollback failed: {}", ex.getMessage());
                }
            }
            logger.error("Failed to save or merge food item: {}", e.getMessage(), e);
            throw (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                    conn.close();
                } catch (SQLException ex) {
                    logger.error("Failed to reset autocommit / close connection: {}", ex.getMessage());
                }
            }
        }
    }

    public boolean update(FoodItem item) throws SQLException {
        ValidationUtils.validateFoodItem(item);
        if (item.getId() == null) {
            throw new IllegalArgumentException("Food item ID is required for update");
        }
        String sql = "UPDATE food_items SET name = ?, category = ?, quantity = ?, unit = ?, price_per_unit = ?, " +
                     "expiry_date = ?, status = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, item.getName().trim());
            stmt.setString(2, item.getCategory().trim());
            stmt.setBigDecimal(3, item.getQuantity());
            stmt.setString(4, item.getUnit().trim());
            stmt.setBigDecimal(5, item.getPricePerUnit());
            stmt.setDate(6, Date.valueOf(item.getExpiryDate()));
            stmt.setString(7, item.getStatus());
            stmt.setLong(8, item.getId());

            return stmt.executeUpdate() > 0;
        }
    }

    public boolean updateQuantity(Long id, BigDecimal newQuantity) throws SQLException {
        if (newQuantity != null && newQuantity.compareTo(BigDecimal.ZERO) == 0) {
            newQuantity = BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        String sql = "UPDATE food_items SET quantity = ?, status = CASE " +
                     "WHEN expiry_date < CURDATE() THEN 'EXPIRED' " +
                     "WHEN expiry_date <= DATE_ADD(CURDATE(), INTERVAL 2 DAY) THEN 'NEAR_EXPIRY' " +
                     "ELSE 'OK' END WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBigDecimal(1, newQuantity);
            stmt.setLong(2, id);

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
        BigDecimal remainingQty = rs.getBigDecimal("quantity");
        item.setQuantity(remainingQty);
        item.setRemainingQuantity(remainingQty);
        item.setUnit(rs.getString("unit"));
        item.setPricePerUnit(rs.getBigDecimal("price_per_unit"));

        try {
            BigDecimal totalQty = rs.getBigDecimal("total_quantity");
            if (totalQty != null) {
                item.setTotalQuantity(totalQty.max(remainingQty != null ? remainingQty : BigDecimal.ZERO));
            } else {
                item.setTotalQuantity(remainingQty != null ? remainingQty : BigDecimal.ZERO);
            }
        } catch (SQLException ignored) {
            item.setTotalQuantity(remainingQty != null ? remainingQty : BigDecimal.ZERO);
        }

        Date expiry = rs.getDate("expiry_date");
        if (expiry != null) {
            item.setExpiryDate(expiry.toLocalDate());
        }

        // Dynamically compute status and all expiry fields using Asia/Yangon date
        item.updateComputedExpiryFields();

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) item.setCreatedAt(created.toLocalDateTime());

        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) item.setUpdatedAt(updated.toLocalDateTime());

        return item;
    }
}
