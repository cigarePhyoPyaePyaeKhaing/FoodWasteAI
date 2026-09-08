package com.foodwasteai.dao;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.WasteRecord;
import com.foodwasteai.util.ValidationUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for Waste records, loss calculations, historical waste rates,
 * and atomic stock deduction transactions.
 * Reads and writes the existing waste_records table without requiring a user_id column.
 */
public class WasteRecordDao extends BaseDao {

    public Optional<WasteRecord> findById(Long id) throws SQLException {
        throw new IllegalArgumentException("Authenticated user is required");
    }

    public Optional<WasteRecord> findById(Long id, Long userId) throws SQLException {
        requireUserId(userId);
        String sql = "SELECT w.id, w.food_item_id, f.name AS food_name, f.unit AS food_unit, f.user_id, w.quantity_wasted, w.reason, " +
                     "w.monetary_loss, w.waste_date, w.notes, w.created_at " +
                     "FROM waste_records w JOIN food_items f ON w.food_item_id = f.id WHERE w.id = ? AND f.user_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            stmt.setLong(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToWasteRecord(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<WasteRecord> findAll() throws SQLException {
        return findAll(null);
    }

    public List<WasteRecord> findAll(Long userId) throws SQLException {
        List<WasteRecord> list = new ArrayList<>();
        String sql = "SELECT w.id, w.food_item_id, f.name AS food_name, f.unit AS food_unit, f.user_id, w.quantity_wasted, w.reason, " +
                     "w.monetary_loss, w.waste_date, w.notes, w.created_at " +
                     "FROM waste_records w JOIN food_items f ON w.food_item_id = f.id WHERE f.user_id = ? ORDER BY w.waste_date DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, requireUserId(userId));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) list.add(mapResultSetToWasteRecord(rs));
            }
        }
        return list;
    }

    public List<WasteRecord> findByFoodItemId(Long foodItemId) throws SQLException {
        throw new IllegalArgumentException("Authenticated user is required");
    }

    public List<WasteRecord> findByFoodItemId(Long foodItemId, Long userId) throws SQLException {
        requireUserId(userId);
        List<WasteRecord> list = new ArrayList<>();
        String sql = "SELECT w.id, w.food_item_id, f.name AS food_name, f.unit AS food_unit, f.user_id, w.quantity_wasted, w.reason, " +
                     "w.monetary_loss, w.waste_date, w.notes, w.created_at " +
                     "FROM waste_records w JOIN food_items f ON w.food_item_id = f.id " +
                     "WHERE w.food_item_id = ? AND f.user_id = ? ORDER BY w.waste_date DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, foodItemId);
            stmt.setLong(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToWasteRecord(rs));
                }
            }
        }
        return list;
    }

    public List<WasteRecord> findByDateRange(LocalDate start, LocalDate end) throws SQLException {
        throw new IllegalArgumentException("Authenticated user is required");
    }

    public List<WasteRecord> findByDateRange(LocalDate start, LocalDate end, Long userId) throws SQLException {
        requireUserId(userId);
        List<WasteRecord> list = new ArrayList<>();
        String sql = "SELECT w.id, w.food_item_id, f.name AS food_name, f.unit AS food_unit, f.user_id, w.quantity_wasted, w.reason, " +
                     "w.monetary_loss, w.waste_date, w.notes, w.created_at " +
                     "FROM waste_records w JOIN food_items f ON w.food_item_id = f.id " +
                     "WHERE w.waste_date >= ? AND w.waste_date < ? AND f.user_id = ? ORDER BY w.waste_date DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, com.foodwasteai.util.AppTime.startOfDayUtc(start));
            stmt.setObject(2, com.foodwasteai.util.AppTime.startOfDayUtc(end.plusDays(1)));
            stmt.setLong(3, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToWasteRecord(rs));
                }
            }
        }
        return list;
    }

    public BigDecimal calculateHistoricalWasteRate(Long foodItemId, int pastDays) throws SQLException {
        return getHistoricalWasteRate(foodItemId, pastDays);
    }
    public BigDecimal calculateHistoricalWasteRate(Long foodItemId, int pastDays, Long userId) throws SQLException {
        return getHistoricalWasteRate(foodItemId, pastDays, userId);
    }

    public BigDecimal getHistoricalWasteRate(Long foodItemId, int pastDays) throws SQLException {
        throw new IllegalArgumentException("Authenticated user is required");
    }

    public BigDecimal getHistoricalWasteRate(Long foodItemId, int pastDays, Long userId) throws SQLException {
        requireUserId(userId);
        String sql = "SELECT " +
                     "  IFNULL(SUM(w.quantity_wasted), 0) AS total_waste, " +
                     "  IFNULL((SELECT SUM(s.quantity_sold) FROM sales s WHERE s.food_item_id = f.id AND s.sale_date >= DATE_SUB(NOW(), INTERVAL ? DAY) AND s.sale_date <= NOW()), 0) AS total_sold " +
                     "FROM food_items f LEFT JOIN waste_records w ON w.food_item_id = f.id " +
                     "AND w.waste_date >= DATE_SUB(NOW(), INTERVAL ? DAY) AND w.waste_date <= NOW() " +
                     "WHERE f.id = ? AND f.user_id = ? GROUP BY f.id";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, pastDays > 0 ? pastDays : 14);
            stmt.setInt(2, pastDays > 0 ? pastDays : 14);
            stmt.setLong(3, foodItemId);
            stmt.setLong(4, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    BigDecimal waste = rs.getBigDecimal("total_waste");
                    BigDecimal sold = rs.getBigDecimal("total_sold");
                    BigDecimal total = waste.add(sold);
                    if (total.compareTo(BigDecimal.ZERO) > 0) {
                        return waste.divide(total, 4, RoundingMode.HALF_UP);
                    }
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Atomically validates stock, creates waste log record, calculates monetary loss,
     * and deducts inventory inside one database transaction.
     * Uses SELECT ... FOR UPDATE for row-level concurrency protection.
     */
    public WasteRecord recordWasteWithStockDeduction(WasteRecord record, Long userId) throws SQLException {
        ValidationUtils.validateWasteRecord(record);

        String selectFoodSql = "SELECT id, name, category, quantity, unit, price_per_unit, expiry_date, status " +
                               "FROM food_items WHERE id = ? AND user_id = ? FOR UPDATE";
        String insertWasteSql = "INSERT INTO waste_records (food_item_id, quantity_wasted, reason, monetary_loss, waste_date, notes) " +
                                "VALUES (?, ?, ?, ?, ?, ?)";
        String updateFoodQtySql = "UPDATE food_items SET quantity = ?, status = CASE " +
                                  "WHEN expiry_date < " + com.foodwasteai.util.AppTime.SQL_TODAY + " THEN 'EXPIRED' " +
                                  "WHEN expiry_date <= DATE_ADD(" + com.foodwasteai.util.AppTime.SQL_TODAY + ", INTERVAL 2 DAY) THEN 'NEAR_EXPIRY' " +
                                  "ELSE 'OK' END WHERE id = ? AND user_id = ?";
        String insertTxSql = "INSERT INTO inventory_transactions (food_item_id, transaction_type, quantity, unit, notes, created_by) " +
                             "VALUES (?, 'WASTE_ADJUSTMENT', ?, ?, ?, ?)";

        Connection conn = null;
        boolean originalAutoCommit = true;
        try {
            conn = getConnection();
            originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            // 1. Lock and read food item
            FoodItem foodItem = null;
            try (PreparedStatement stmt = conn.prepareStatement(selectFoodSql)) {
                stmt.setLong(1, record.getFoodItemId());
                stmt.setLong(2, requireUserId(userId));
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        foodItem = new FoodItem();
                        foodItem.setId(rs.getLong("id"));
                        foodItem.setName(rs.getString("name"));
                        foodItem.setCategory(rs.getString("category"));
                        foodItem.setQuantity(rs.getBigDecimal("quantity"));
                        foodItem.setUnit(rs.getString("unit"));
                        foodItem.setPricePerUnit(rs.getBigDecimal("price_per_unit"));
                        java.time.LocalDate exp = rs.getObject("expiry_date", java.time.LocalDate.class);
                        if (exp != null) foodItem.setExpiryDate(exp);
                        foodItem.setStatus(rs.getString("status"));
                    }
                }
            }

            if (foodItem == null) {
                conn.rollback();
                throw new IllegalArgumentException("Food item #" + record.getFoodItemId() + " does not exist");
            }

            record.setFoodItemName(foodItem.getName());
            record.setUnit(foodItem.getUnit());

            // 2. Strict stock validation
            BigDecimal availableStock = foodItem.getQuantity() != null ? foodItem.getQuantity() : BigDecimal.ZERO;
            BigDecimal requestedQty = record.getQuantityWasted();

            if (requestedQty.compareTo(BigDecimal.ZERO) <= 0) {
                conn.rollback();
                throw new IllegalArgumentException("Waste quantity must be greater than 0");
            }

            if (requestedQty.compareTo(availableStock) > 0) {
                conn.rollback();
                throw new IllegalArgumentException(String.format(
                        "Insufficient stock. Available: %s %s, requested: %s %s.",
                        availableStock.stripTrailingZeros().toPlainString(),
                        foodItem.getUnit(),
                        requestedQty.stripTrailingZeros().toPlainString(),
                        foodItem.getUnit()
                ));
            }

            // 3. Exact deduction
            BigDecimal newStock = availableStock.subtract(requestedQty);
            if (newStock.compareTo(BigDecimal.ZERO) < 0) {
                conn.rollback();
                throw new IllegalArgumentException("Deduction would result in negative stock");
            }
            if (newStock.compareTo(BigDecimal.ZERO) == 0) {
                newStock = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }

            // 4. Calculate exact monetary loss
            BigDecimal pricePerUnit = foodItem.getPricePerUnit() != null ? foodItem.getPricePerUnit() : BigDecimal.ZERO;
            BigDecimal monetaryLoss = pricePerUnit.multiply(requestedQty).setScale(2, RoundingMode.HALF_UP);
            record.setMonetaryLoss(monetaryLoss);

            if (record.getWasteDate() == null) {
                record.setWasteDate(com.foodwasteai.util.AppTime.utcNow());
            }

            Long validUserId = resolveValidUserId(conn, userId);
            record.setUserId(validUserId != null ? validUserId : userId);

            // 5. Insert waste record
            try (PreparedStatement insertStmt = conn.prepareStatement(insertWasteSql, Statement.RETURN_GENERATED_KEYS)) {
                insertStmt.setLong(1, record.getFoodItemId());
                insertStmt.setBigDecimal(2, record.getQuantityWasted());
                insertStmt.setString(3, record.getReason().name());
                insertStmt.setBigDecimal(4, record.getMonetaryLoss());
                insertStmt.setObject(5, record.getWasteDate());
                insertStmt.setString(6, record.getNotes());

                int affected = insertStmt.executeUpdate();
                if (affected > 0) {
                    try (ResultSet keys = insertStmt.getGeneratedKeys()) {
                        if (keys.next()) {
                            record.setId(keys.getLong(1));
                        }
                    }
                }
            }

            // 6. Update inventory quantity
            try (PreparedStatement updateStmt = conn.prepareStatement(updateFoodQtySql)) {
                updateStmt.setBigDecimal(1, newStock);
                updateStmt.setLong(2, foodItem.getId());
                updateStmt.setLong(3, userId);
                updateStmt.executeUpdate();
            }

            // 7. Insert inventory transaction audit log
            try (PreparedStatement txStmt = conn.prepareStatement(insertTxSql)) {
                txStmt.setLong(1, foodItem.getId());
                txStmt.setBigDecimal(2, requestedQty);
                txStmt.setString(3, foodItem.getUnit());
                txStmt.setString(4, "Waste incident: " + record.getReason() + " (" + requestedQty.stripTrailingZeros().toPlainString() + " " + foodItem.getUnit() + ")");
                if (validUserId != null) {
                    txStmt.setLong(5, validUserId);
                } else {
                    txStmt.setNull(5, Types.BIGINT);
                }
                txStmt.executeUpdate();
            }

            // 8. Commit atomic transaction
            conn.commit();
            logger.info("Transaction committed: Recorded waste #{} ({} {}) for food item '{}'. Monetary Loss: {} MMK. Stock updated: {} -> {}",
                    record.getId(), requestedQty, foodItem.getUnit(), foodItem.getName(), monetaryLoss, availableStock, newStock);
            return record;
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    logger.error("Failed to rollback waste transaction: {}", rollbackEx.getMessage());
                }
            }
            throw e;
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                    conn.close();
                } catch (SQLException closeEx) {
                    logger.error("Failed to reset autocommit / close connection: {}", closeEx.getMessage());
                }
            }
        }
    }

    public WasteRecord save(WasteRecord record) throws SQLException {
        return recordWasteWithStockDeduction(record, 1L);
    }

    public boolean delete(Long id) throws SQLException {
        throw new IllegalArgumentException("Authenticated user is required");
    }

    public boolean delete(Long id, Long userId) throws SQLException {
        requireUserId(userId);
        String sql = "DELETE FROM waste_records WHERE id = ? AND food_item_id IN (SELECT id FROM food_items WHERE user_id = ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            stmt.setLong(2, userId);
            return stmt.executeUpdate() > 0;
        }
    }

    private WasteRecord mapResultSetToWasteRecord(ResultSet rs) throws SQLException {
        WasteRecord record = new WasteRecord();
        record.setId(rs.getLong("id"));
        record.setUserId(rs.getLong("user_id"));
        record.setFoodItemId(rs.getLong("food_item_id"));
        record.setFoodItemName(rs.getString("food_name"));
        record.setUnit(rs.getString("food_unit"));
        record.setQuantityWasted(rs.getBigDecimal("quantity_wasted"));
        record.setReason(WasteRecord.Reason.valueOf(rs.getString("reason")));
        record.setMonetaryLoss(rs.getBigDecimal("monetary_loss"));

        java.time.LocalDateTime wasteDate = rs.getObject("waste_date", java.time.LocalDateTime.class);
        if (wasteDate != null) record.setWasteDate(wasteDate);

        record.setNotes(rs.getString("notes"));

        java.time.LocalDateTime created = rs.getObject("created_at", java.time.LocalDateTime.class);
        if (created != null) record.setCreatedAt(created);

        return record;
    }
}
