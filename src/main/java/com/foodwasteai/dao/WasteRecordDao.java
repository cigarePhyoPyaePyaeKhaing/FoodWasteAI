package com.foodwasteai.dao;

import com.foodwasteai.model.WasteRecord;
import com.foodwasteai.util.ValidationUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for Waste records, loss calculations, and historical waste rates.
 */
public class WasteRecordDao extends BaseDao {

    public Optional<WasteRecord> findById(Long id) throws SQLException {
        String sql = "SELECT w.id, w.food_item_id, f.name AS food_name, w.quantity_wasted, w.reason, " +
                     "w.monetary_loss, w.waste_date, w.notes, w.created_at " +
                     "FROM waste_records w JOIN food_items f ON w.food_item_id = f.id WHERE w.id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToWasteRecord(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<WasteRecord> findAll() throws SQLException {
        List<WasteRecord> list = new ArrayList<>();
        String sql = "SELECT w.id, w.food_item_id, f.name AS food_name, w.quantity_wasted, w.reason, " +
                     "w.monetary_loss, w.waste_date, w.notes, w.created_at " +
                     "FROM waste_records w JOIN food_items f ON w.food_item_id = f.id ORDER BY w.waste_date DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(mapResultSetToWasteRecord(rs));
            }
        }
        return list;
    }

    public List<WasteRecord> findByFoodItemId(Long foodItemId) throws SQLException {
        List<WasteRecord> list = new ArrayList<>();
        String sql = "SELECT w.id, w.food_item_id, f.name AS food_name, w.quantity_wasted, w.reason, " +
                     "w.monetary_loss, w.waste_date, w.notes, w.created_at " +
                     "FROM waste_records w JOIN food_items f ON w.food_item_id = f.id " +
                     "WHERE w.food_item_id = ? ORDER BY w.waste_date DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, foodItemId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToWasteRecord(rs));
                }
            }
        }
        return list;
    }

    public List<WasteRecord> findByDateRange(LocalDate start, LocalDate end) throws SQLException {
        List<WasteRecord> list = new ArrayList<>();
        String sql = "SELECT w.id, w.food_item_id, f.name AS food_name, w.quantity_wasted, w.reason, " +
                     "w.monetary_loss, w.waste_date, w.notes, w.created_at " +
                     "FROM waste_records w JOIN food_items f ON w.food_item_id = f.id " +
                     "WHERE DATE(w.waste_date) BETWEEN ? AND ? ORDER BY w.waste_date DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDate(1, Date.valueOf(start));
            stmt.setDate(2, Date.valueOf(end));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToWasteRecord(rs));
                }
            }
        }
        return list;
    }

    /**
     * Calculates the historical waste rate = Total Waste / (Total Waste + Total Sales) over past N days.
     * Essential input for SWI-Prolog assessment.
     */
    public BigDecimal calculateHistoricalWasteRate(Long foodItemId, int pastDays) throws SQLException {
        String sql = "SELECT " +
                     "  (SELECT IFNULL(SUM(quantity_wasted), 0) FROM waste_records WHERE food_item_id = ? AND waste_date >= DATE_SUB(NOW(), INTERVAL ? DAY)) AS total_waste, " +
                     "  (SELECT IFNULL(SUM(quantity_sold), 0) FROM sales WHERE food_item_id = ? AND sale_date >= DATE_SUB(NOW(), INTERVAL ? DAY)) AS total_sold";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, foodItemId);
            stmt.setInt(2, pastDays > 0 ? pastDays : 14);
            stmt.setLong(3, foodItemId);
            stmt.setInt(4, pastDays > 0 ? pastDays : 14);

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

    public WasteRecord save(WasteRecord record) throws SQLException {
        ValidationUtils.validateWasteRecord(record);

        // Auto-calculate monetary loss if not set by looking up price_per_unit
        if (record.getMonetaryLoss() == null || record.getMonetaryLoss().compareTo(BigDecimal.ZERO) == 0) {
            String priceSql = "SELECT price_per_unit FROM food_items WHERE id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement priceStmt = conn.prepareStatement(priceSql)) {
                priceStmt.setLong(1, record.getFoodItemId());
                try (ResultSet rs = priceStmt.executeQuery()) {
                    if (rs.next()) {
                        BigDecimal unitPrice = rs.getBigDecimal("price_per_unit");
                        record.setMonetaryLoss(unitPrice.multiply(record.getQuantityWasted()));
                    }
                }
            }
        }

        String sql = "INSERT INTO waste_records (food_item_id, quantity_wasted, reason, monetary_loss, waste_date, notes) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, record.getFoodItemId());
            stmt.setBigDecimal(2, record.getQuantityWasted());
            stmt.setString(3, record.getReason().name());
            stmt.setBigDecimal(4, record.getMonetaryLoss() != null ? record.getMonetaryLoss() : BigDecimal.ZERO);
            stmt.setTimestamp(5, record.getWasteDate() != null ? Timestamp.valueOf(record.getWasteDate()) : Timestamp.valueOf(java.time.LocalDateTime.now()));
            stmt.setString(6, record.getNotes());

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        record.setId(keys.getLong(1));
                    }
                }
            }
            logger.info("Saved waste record: {} kg of food #{} (Reason: {})", record.getQuantityWasted(), record.getFoodItemId(), record.getReason());
            return record;
        }
    }

    public boolean delete(Long id) throws SQLException {
        String sql = "DELETE FROM waste_records WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            return stmt.executeUpdate() > 0;
        }
    }

    private WasteRecord mapResultSetToWasteRecord(ResultSet rs) throws SQLException {
        WasteRecord record = new WasteRecord();
        record.setId(rs.getLong("id"));
        record.setFoodItemId(rs.getLong("food_item_id"));
        record.setFoodItemName(rs.getString("food_name"));
        record.setQuantityWasted(rs.getBigDecimal("quantity_wasted"));
        record.setReason(WasteRecord.Reason.valueOf(rs.getString("reason")));
        record.setMonetaryLoss(rs.getBigDecimal("monetary_loss"));

        Timestamp wasteDate = rs.getTimestamp("waste_date");
        if (wasteDate != null) record.setWasteDate(wasteDate.toLocalDateTime());

        record.setNotes(rs.getString("notes"));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) record.setCreatedAt(created.toLocalDateTime());

        return record;
    }
}
