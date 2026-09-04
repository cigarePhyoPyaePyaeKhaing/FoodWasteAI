package com.foodwasteai.dao;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.Sale;
import com.foodwasteai.util.ValidationUtils;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for Sales records and demand calculations with prepared statements
 * and atomic stock deduction transactions.
 */
public class SalesDao extends BaseDao {

    public Optional<Sale> findById(Long id) throws SQLException {
        String sql = "SELECT s.id, s.food_item_id, f.name AS food_name, f.unit AS food_unit, s.quantity_sold, s.unit_price, " +
                     "s.total_amount, s.customer_count, s.sale_date, s.created_at " +
                     "FROM sales s JOIN food_items f ON s.food_item_id = f.id WHERE s.id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToSale(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<Sale> findAll() throws SQLException {
        List<Sale> list = new ArrayList<>();
        String sql = "SELECT s.id, s.food_item_id, f.name AS food_name, f.unit AS food_unit, s.quantity_sold, s.unit_price, " +
                     "s.total_amount, s.customer_count, s.sale_date, s.created_at " +
                     "FROM sales s JOIN food_items f ON s.food_item_id = f.id ORDER BY s.sale_date DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(mapResultSetToSale(rs));
            }
        }
        return list;
    }

    public List<Sale> findByFoodItemId(Long foodItemId) throws SQLException {
        List<Sale> list = new ArrayList<>();
        String sql = "SELECT s.id, s.food_item_id, f.name AS food_name, f.unit AS food_unit, s.quantity_sold, s.unit_price, " +
                     "s.total_amount, s.customer_count, s.sale_date, s.created_at " +
                     "FROM sales s JOIN food_items f ON s.food_item_id = f.id " +
                     "WHERE s.food_item_id = ? ORDER BY s.sale_date DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, foodItemId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToSale(rs));
                }
            }
        }
        return list;
    }

    public List<Sale> findByDateRange(LocalDate start, LocalDate end) throws SQLException {
        List<Sale> list = new ArrayList<>();
        String sql = "SELECT s.id, s.food_item_id, f.name AS food_name, f.unit AS food_unit, s.quantity_sold, s.unit_price, " +
                     "s.total_amount, s.customer_count, s.sale_date, s.created_at " +
                     "FROM sales s JOIN food_items f ON s.food_item_id = f.id " +
                     "WHERE DATE(s.sale_date) BETWEEN ? AND ? ORDER BY s.sale_date DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDate(1, Date.valueOf(start));
            stmt.setDate(2, Date.valueOf(end));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToSale(rs));
                }
            }
        }
        return list;
    }

    public BigDecimal getHistoricalAverageDailySales(Long foodItemId, int pastDays) throws SQLException {
        String sql = "SELECT IFNULL(SUM(quantity_sold) / ?, 0) AS avg_daily_demand " +
                     "FROM sales WHERE food_item_id = ? AND sale_date >= DATE_SUB(NOW(), INTERVAL ? DAY)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, pastDays > 0 ? pastDays : 7);
            stmt.setLong(2, foodItemId);
            stmt.setInt(3, pastDays > 0 ? pastDays : 7);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBigDecimal("avg_daily_demand");
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Atomically validates stock, creates sale record, and deducts inventory inside one database transaction.
     * Uses SELECT ... FOR UPDATE for row-level concurrency protection.
     */
    public Sale recordSaleWithStockDeduction(Sale sale, Long userId) throws SQLException {
        ValidationUtils.validateSale(sale);

        String selectFoodSql = "SELECT id, name, category, quantity, unit, price_per_unit, expiry_date, status " +
                               "FROM food_items WHERE id = ? FOR UPDATE";
        String insertSaleSql = "INSERT INTO sales (food_item_id, quantity_sold, unit_price, total_amount, customer_count, sale_date) " +
                               "VALUES (?, ?, ?, ?, ?, ?)";
        String updateFoodQtySql = "UPDATE food_items SET quantity = ?, status = CASE " +
                                  "WHEN expiry_date < CURDATE() THEN 'EXPIRED' " +
                                  "WHEN expiry_date <= DATE_ADD(CURDATE(), INTERVAL 2 DAY) THEN 'NEAR_EXPIRY' " +
                                  "ELSE 'OK' END WHERE id = ?";
        String insertTxSql = "INSERT INTO inventory_transactions (food_item_id, transaction_type, quantity, unit, notes, created_by) " +
                             "VALUES (?, 'USAGE', ?, ?, ?, ?)";

        Connection conn = null;
        boolean originalAutoCommit = true;
        try {
            conn = getConnection();
            originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            // 1. Lock and read food item
            FoodItem foodItem = null;
            try (PreparedStatement stmt = conn.prepareStatement(selectFoodSql)) {
                stmt.setLong(1, sale.getFoodItemId());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        foodItem = new FoodItem();
                        foodItem.setId(rs.getLong("id"));
                        foodItem.setName(rs.getString("name"));
                        foodItem.setCategory(rs.getString("category"));
                        foodItem.setQuantity(rs.getBigDecimal("quantity"));
                        foodItem.setUnit(rs.getString("unit"));
                        foodItem.setPricePerUnit(rs.getBigDecimal("price_per_unit"));
                        Date exp = rs.getDate("expiry_date");
                        if (exp != null) foodItem.setExpiryDate(exp.toLocalDate());
                        foodItem.setStatus(rs.getString("status"));
                    }
                }
            }

            if (foodItem == null) {
                conn.rollback();
                throw new IllegalArgumentException("Food item #" + sale.getFoodItemId() + " does not exist");
            }

            sale.setFoodItemName(foodItem.getName());
            sale.setUnit(foodItem.getUnit());

            // 2. Expiry validation (strictly expired before today cannot be sold)
            if (foodItem.getExpiryDate() != null && com.foodwasteai.util.ExpiryStatusResolver.isExpired(foodItem.getExpiryDate())) {
                conn.rollback();
                throw new IllegalArgumentException(String.format("Cannot record sale for expired food item '%s' (Expired on %s)",
                        foodItem.getName(), foodItem.getExpiryDate()));
            }

            // 3. Strict stock validation
            BigDecimal availableStock = foodItem.getQuantity() != null ? foodItem.getQuantity() : BigDecimal.ZERO;
            BigDecimal requestedQty = sale.getQuantitySold();

            if (requestedQty.compareTo(BigDecimal.ZERO) <= 0) {
                conn.rollback();
                throw new IllegalArgumentException("Sale quantity must be greater than 0");
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

            // 4. Exact deduction (never clamp with max(0, ...))
            BigDecimal newStock = availableStock.subtract(requestedQty);
            if (newStock.compareTo(BigDecimal.ZERO) < 0) {
                conn.rollback();
                throw new IllegalArgumentException("Deduction would result in negative stock");
            }

            // Pricing
            if (sale.getUnitPrice() == null || sale.getUnitPrice().compareTo(BigDecimal.ZERO) == 0) {
                sale.setUnitPrice(foodItem.getPricePerUnit());
            }
            if (sale.getTotalAmount() == null) {
                sale.setTotalAmount(sale.getUnitPrice().multiply(requestedQty).setScale(2, java.math.RoundingMode.HALF_UP));
            }
            if (sale.getSaleDate() == null) {
                sale.setSaleDate(LocalDateTime.now());
            }

            // 5. Insert sales record
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSaleSql, Statement.RETURN_GENERATED_KEYS)) {
                insertStmt.setLong(1, sale.getFoodItemId());
                insertStmt.setBigDecimal(2, sale.getQuantitySold());
                insertStmt.setBigDecimal(3, sale.getUnitPrice());
                insertStmt.setBigDecimal(4, sale.getTotalAmount());
                insertStmt.setInt(5, sale.getCustomerCount() != null ? sale.getCustomerCount() : 1);
                insertStmt.setTimestamp(6, Timestamp.valueOf(sale.getSaleDate()));

                int affected = insertStmt.executeUpdate();
                if (affected > 0) {
                    try (ResultSet keys = insertStmt.getGeneratedKeys()) {
                        if (keys.next()) {
                            sale.setId(keys.getLong(1));
                        }
                    }
                }
            }

            // 6. Update inventory quantity
            try (PreparedStatement updateStmt = conn.prepareStatement(updateFoodQtySql)) {
                updateStmt.setBigDecimal(1, newStock);
                updateStmt.setLong(2, foodItem.getId());
                updateStmt.executeUpdate();
            }

            // 7. Insert inventory transaction audit log
            Long validUserId = resolveValidUserId(conn, userId);
            try (PreparedStatement txStmt = conn.prepareStatement(insertTxSql)) {
                txStmt.setLong(1, foodItem.getId());
                txStmt.setBigDecimal(2, requestedQty);
                txStmt.setString(3, foodItem.getUnit());
                txStmt.setString(4, "Customer sale (" + requestedQty.stripTrailingZeros().toPlainString() + " " + foodItem.getUnit() + ")");
                if (validUserId != null) {
                    txStmt.setLong(5, validUserId);
                } else {
                    txStmt.setNull(5, Types.BIGINT);
                }
                txStmt.executeUpdate();
            }

            // 8. Commit atomic transaction
            conn.commit();
            logger.info("Transaction committed: Recorded sale #{} ({} {}) for food item '{}'. Stock updated: {} -> {}",
                    sale.getId(), requestedQty, foodItem.getUnit(), foodItem.getName(), availableStock, newStock);
            return sale;
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    logger.error("Failed to rollback transaction: {}", rollbackEx.getMessage());
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

    public Sale save(Sale sale) throws SQLException {
        return recordSaleWithStockDeduction(sale, 1L);
    }

    public boolean delete(Long id) throws SQLException {
        String sql = "DELETE FROM sales WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            return stmt.executeUpdate() > 0;
        }
    }

    private Sale mapResultSetToSale(ResultSet rs) throws SQLException {
        Sale sale = new Sale();
        sale.setId(rs.getLong("id"));
        sale.setFoodItemId(rs.getLong("food_item_id"));
        sale.setFoodItemName(rs.getString("food_name"));
        sale.setUnit(rs.getString("food_unit"));
        sale.setQuantitySold(rs.getBigDecimal("quantity_sold"));
        sale.setUnitPrice(rs.getBigDecimal("unit_price"));
        sale.setTotalAmount(rs.getBigDecimal("total_amount"));
        sale.setCustomerCount(rs.getInt("customer_count"));

        Timestamp saleDate = rs.getTimestamp("sale_date");
        if (saleDate != null) sale.setSaleDate(saleDate.toLocalDateTime());

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) sale.setCreatedAt(created.toLocalDateTime());

        return sale;
    }
}
