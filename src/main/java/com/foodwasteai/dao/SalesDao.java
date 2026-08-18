package com.foodwasteai.dao;

import com.foodwasteai.model.Sale;
import com.foodwasteai.util.ValidationUtils;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for Sales records and demand calculations with prepared statements.
 */
public class SalesDao extends BaseDao {

    public Optional<Sale> findById(Long id) throws SQLException {
        String sql = "SELECT s.id, s.food_item_id, f.name AS food_name, s.quantity_sold, s.unit_price, " +
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
        String sql = "SELECT s.id, s.food_item_id, f.name AS food_name, s.quantity_sold, s.unit_price, " +
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
        String sql = "SELECT s.id, s.food_item_id, f.name AS food_name, s.quantity_sold, s.unit_price, " +
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
        String sql = "SELECT s.id, s.food_item_id, f.name AS food_name, s.quantity_sold, s.unit_price, " +
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

    public Sale save(Sale sale) throws SQLException {
        ValidationUtils.validateSale(sale);
        if (sale.getTotalAmount() == null && sale.getUnitPrice() != null && sale.getQuantitySold() != null) {
            sale.setTotalAmount(sale.getUnitPrice().multiply(sale.getQuantitySold()));
        }

        String sql = "INSERT INTO sales (food_item_id, quantity_sold, unit_price, total_amount, customer_count, sale_date) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, sale.getFoodItemId());
            stmt.setBigDecimal(2, sale.getQuantitySold());
            stmt.setBigDecimal(3, sale.getUnitPrice());
            stmt.setBigDecimal(4, sale.getTotalAmount());
            stmt.setInt(5, sale.getCustomerCount() != null ? sale.getCustomerCount() : 1);
            stmt.setTimestamp(6, sale.getSaleDate() != null ? Timestamp.valueOf(sale.getSaleDate()) : Timestamp.valueOf(java.time.LocalDateTime.now()));

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        sale.setId(keys.getLong(1));
                    }
                }
            }
            logger.info("Saved sales record: {} units of food #{}", sale.getQuantitySold(), sale.getFoodItemId());
            return sale;
        }
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
