package com.foodwasteai.dao;

import com.foodwasteai.model.Prediction;
import com.foodwasteai.model.PredictionItem;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for persisting batch AI predictions and item-level reasoning facts.
 * Reads and writes the existing predictions table without requiring a user_id column.
 */
public class PredictionDao extends BaseDao {

    public Prediction savePrediction(Prediction pred) throws SQLException {
        String sql = "INSERT INTO predictions (prediction_date, overall_risk_score, expected_total_waste_kg, " +
                     "estimated_money_lost, potential_savings, status, user_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setObject(1, pred.getPredictionDate() != null ? pred.getPredictionDate() : com.foodwasteai.util.AppTime.today());
            stmt.setBigDecimal(2, pred.getOverallRiskScore());
            stmt.setBigDecimal(3, pred.getExpectedTotalWasteKg());
            stmt.setBigDecimal(4, pred.getEstimatedMoneyLost());
            stmt.setBigDecimal(5, pred.getPotentialSavings());
            stmt.setString(6, pred.getStatus() != null ? pred.getStatus().name() : Prediction.Status.GENERATED.name());
            stmt.setLong(7, requireUserId(pred.getUserId()));

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        pred.setId(keys.getLong(1));
                    }
                }
            }
            return pred;
        }
    }

    public void savePredictionItems(Long predictionId, List<PredictionItem> items) throws SQLException {
        throw new IllegalArgumentException("Authenticated user is required");
    }

    public void savePredictionItems(Long predictionId, List<PredictionItem> items, Long userId) throws SQLException {
        requireUserId(userId);
        if (predictionId == null || items == null || items.isEmpty()) return;

        String sql = "INSERT INTO prediction_items (prediction_id, food_item_id, current_stock, expected_demand, " +
                     "expiry_days, historical_waste_rate, risk_level, risk_percentage, predicted_waste_qty, " +
                     "recommended_production, priority_usage, reasoning_text, reasoning_text_en, reasoning_text_my) " +
                     "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? FROM predictions p JOIN food_items f ON f.user_id = p.user_id " +
                     "WHERE p.id = ? AND f.id = ? AND p.user_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            // Individual prediction items are committed together.
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
            for (PredictionItem item : items) {
                stmt.setLong(1, predictionId);
                stmt.setLong(2, item.getFoodItemId());
                stmt.setBigDecimal(3, item.getCurrentStock());
                stmt.setBigDecimal(4, item.getExpectedDemand());
                stmt.setInt(5, item.getExpiryDays() != null ? item.getExpiryDays() : 0);
                stmt.setBigDecimal(6, item.getHistoricalWasteRate());
                stmt.setString(7, item.getRiskLevel().name());
                stmt.setBigDecimal(8, item.getRiskPercentage());
                stmt.setBigDecimal(9, item.getPredictedWasteQty());
                stmt.setBigDecimal(10, item.getRecommendedProduction());
                stmt.setString(11, item.getPriorityUsage());
                stmt.setString(12, item.getReasoningText());
                stmt.setString(13, item.getReasoningTextEn());
                stmt.setString(14, item.getReasoningTextMy());
                stmt.setLong(15, predictionId);
                stmt.setLong(16, item.getFoodItemId());
                stmt.setLong(17, userId);
                stmt.addBatch();
            }
            for (int count : stmt.executeBatch()) {
                if (count != 1 && count != Statement.SUCCESS_NO_INFO) throw new SQLException("Prediction item owner mismatch");
            }
            conn.commit();
            } catch (SQLException | RuntimeException exception) {
                conn.rollback(); throw exception;
            } finally { conn.setAutoCommit(originalAutoCommit); }
        }
    }

    public List<PredictionItem> findItemsByPredictionId(Long predictionId) throws SQLException {
        throw new IllegalArgumentException("Authenticated user is required");
    }

    public List<PredictionItem> findItemsByPredictionId(Long predictionId, Long userId) throws SQLException {
        List<PredictionItem> list = new ArrayList<>();
        String sql = "SELECT pi.id, pi.prediction_id, pi.food_item_id, f.name AS food_name, f.unit AS food_unit, pi.current_stock, " +
                     "pi.expected_demand, pi.expiry_days, pi.historical_waste_rate, pi.risk_level, " +
                     "pi.risk_percentage, pi.predicted_waste_qty, pi.recommended_production, " +
                     "pi.priority_usage, pi.reasoning_text, pi.reasoning_text_en, pi.reasoning_text_my, pi.created_at " +
                     "FROM prediction_items pi " +
                     "JOIN food_items f ON pi.food_item_id = f.id " +
                     "JOIN predictions p ON p.id = pi.prediction_id AND p.user_id = f.user_id " +
                     "WHERE pi.prediction_id = ? AND p.user_id = ? ORDER BY pi.risk_percentage DESC";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, predictionId);
            stmt.setLong(2, requireUserId(userId));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToPredictionItem(rs));
                }
            }
        }
        return list;
    }

    public Optional<Prediction> findLatestPrediction() throws SQLException {
        return findLatestPrediction(null);
    }

    public Optional<Prediction> findLatestPrediction(Long userId) throws SQLException {
        String sql = "SELECT id, prediction_date, overall_risk_score, expected_total_waste_kg, " +
                     "estimated_money_lost, potential_savings, status, created_at " +
                     "FROM predictions WHERE user_id = ? ORDER BY id DESC LIMIT 1";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, requireUserId(userId));
            try (ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                Prediction p = new Prediction();
                p.setUserId(userId);
                p.setId(rs.getLong("id"));
                p.setPredictionDate(rs.getObject("prediction_date", java.time.LocalDate.class));
                p.setOverallRiskScore(rs.getBigDecimal("overall_risk_score"));
                p.setExpectedTotalWasteKg(rs.getBigDecimal("expected_total_waste_kg"));
                p.setEstimatedMoneyLost(rs.getBigDecimal("estimated_money_lost"));
                p.setPotentialSavings(rs.getBigDecimal("potential_savings"));
                p.setStatus(Prediction.Status.valueOf(rs.getString("status")));
                java.time.LocalDateTime ct = rs.getObject("created_at", java.time.LocalDateTime.class);
                if (ct != null) p.setCreatedAt(ct);
                return Optional.of(p);
            }
            }
        }
        return Optional.empty();
    }

    private PredictionItem mapResultSetToPredictionItem(ResultSet rs) throws SQLException {
        PredictionItem item = new PredictionItem();
        item.setId(rs.getLong("id"));
        item.setPredictionId(rs.getLong("prediction_id"));
        item.setFoodItemId(rs.getLong("food_item_id"));
        item.setFoodItemName(rs.getString("food_name"));
        try {
            item.setUnit(rs.getString("food_unit"));
        } catch (SQLException ignored) {}
        item.setCurrentStock(rs.getBigDecimal("current_stock"));
        item.setExpectedDemand(rs.getBigDecimal("expected_demand"));
        item.setExpiryDays(rs.getInt("expiry_days"));
        item.setHistoricalWasteRate(rs.getBigDecimal("historical_waste_rate"));
        item.setRiskLevel(PredictionItem.RiskLevel.valueOf(rs.getString("risk_level")));
        item.setRiskPercentage(rs.getBigDecimal("risk_percentage"));
        item.setRiskScore(rs.getBigDecimal("risk_percentage"));
        item.setPredictedWasteQty(rs.getBigDecimal("predicted_waste_qty"));
        item.setRecommendedProduction(rs.getBigDecimal("recommended_production"));
        item.setPriorityUsage(rs.getString("priority_usage"));
        item.setReasoningText(rs.getString("reasoning_text"));
        item.setReasoningTextEn(rs.getString("reasoning_text_en"));
        item.setReasoningTextMy(rs.getString("reasoning_text_my"));

        java.time.LocalDateTime ct = rs.getObject("created_at", java.time.LocalDateTime.class);
        if (ct != null) item.setCreatedAt(ct);
        return item;
    }
}
