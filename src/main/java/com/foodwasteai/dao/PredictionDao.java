package com.foodwasteai.dao;

import com.foodwasteai.model.Prediction;
import com.foodwasteai.model.PredictionItem;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for persisting batch AI predictions and item-level reasoning facts.
 */
public class PredictionDao extends BaseDao {

    public Prediction savePrediction(Prediction pred) throws SQLException {
        String sql = "INSERT INTO predictions (prediction_date, overall_risk_score, expected_total_waste_kg, " +
                     "estimated_money_lost, potential_savings, status) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setDate(1, Date.valueOf(pred.getPredictionDate() != null ? pred.getPredictionDate() : java.time.LocalDate.now()));
            stmt.setBigDecimal(2, pred.getOverallRiskScore());
            stmt.setBigDecimal(3, pred.getExpectedTotalWasteKg());
            stmt.setBigDecimal(4, pred.getEstimatedMoneyLost());
            stmt.setBigDecimal(5, pred.getPotentialSavings());
            stmt.setString(6, pred.getStatus() != null ? pred.getStatus().name() : Prediction.Status.GENERATED.name());

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
        if (predictionId == null || items == null || items.isEmpty()) return;

        String sql = "INSERT INTO prediction_items (prediction_id, food_item_id, current_stock, expected_demand, " +
                     "expiry_days, historical_waste_rate, risk_level, risk_percentage, predicted_waste_qty, " +
                     "recommended_production, priority_usage, reasoning_text, reasoning_text_en, reasoning_text_my) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public List<PredictionItem> findItemsByPredictionId(Long predictionId) throws SQLException {
        List<PredictionItem> list = new ArrayList<>();
        String sql = "SELECT pi.id, pi.prediction_id, pi.food_item_id, f.name AS food_name, f.unit AS food_unit, pi.current_stock, " +
                     "pi.expected_demand, pi.expiry_days, pi.historical_waste_rate, pi.risk_level, " +
                     "pi.risk_percentage, pi.predicted_waste_qty, pi.recommended_production, " +
                     "pi.priority_usage, pi.reasoning_text, pi.reasoning_text_en, pi.reasoning_text_my, pi.created_at " +
                     "FROM prediction_items pi " +
                     "JOIN food_items f ON pi.food_item_id = f.id " +
                     "WHERE pi.prediction_id = ? ORDER BY pi.risk_percentage DESC";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, predictionId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToPredictionItem(rs));
                }
            }
        }
        return list;
    }

    public Optional<Prediction> findLatestPrediction() throws SQLException {
        String sql = "SELECT id, prediction_date, overall_risk_score, expected_total_waste_kg, " +
                     "estimated_money_lost, potential_savings, status, created_at " +
                     "FROM predictions ORDER BY id DESC LIMIT 1";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                Prediction p = new Prediction();
                p.setId(rs.getLong("id"));
                p.setPredictionDate(rs.getDate("prediction_date").toLocalDate());
                p.setOverallRiskScore(rs.getBigDecimal("overall_risk_score"));
                p.setExpectedTotalWasteKg(rs.getBigDecimal("expected_total_waste_kg"));
                p.setEstimatedMoneyLost(rs.getBigDecimal("estimated_money_lost"));
                p.setPotentialSavings(rs.getBigDecimal("potential_savings"));
                p.setStatus(Prediction.Status.valueOf(rs.getString("status")));
                Timestamp ct = rs.getTimestamp("created_at");
                if (ct != null) p.setCreatedAt(ct.toLocalDateTime());
                return Optional.of(p);
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

        Timestamp ct = rs.getTimestamp("created_at");
        if (ct != null) item.setCreatedAt(ct.toLocalDateTime());
        return item;
    }
}
