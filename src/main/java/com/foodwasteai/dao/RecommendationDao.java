package com.foodwasteai.dao;

import com.foodwasteai.model.Recommendation;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for AI recommendations with prepared statements.
 */
public class RecommendationDao extends BaseDao {

    public Optional<Recommendation> findById(Long id) throws SQLException {
        String sql = "SELECT r.id, r.food_item_id, f.name AS food_name, r.category, r.risk_level, " +
                     "r.title, r.description, r.reasoning_details, r.estimated_savings, r.status, r.created_at, r.updated_at " +
                     "FROM recommendations r JOIN food_items f ON r.food_item_id = f.id WHERE r.id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToRecommendation(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<Recommendation> findAll() throws SQLException {
        List<Recommendation> list = new ArrayList<>();
        String sql = "SELECT r.id, r.food_item_id, f.name AS food_name, r.category, r.risk_level, " +
                     "r.title, r.description, r.reasoning_details, r.estimated_savings, r.status, r.created_at, r.updated_at " +
                     "FROM recommendations r JOIN food_items f ON r.food_item_id = f.id " +
                     "ORDER BY FIELD(r.category, 'URGENT', 'IMPORTANT', 'REDISTRIBUTION', 'OPTIMIZATION'), r.created_at DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(mapResultSetToRecommendation(rs));
            }
        }
        return list;
    }

    public List<Recommendation> findByStatus(Recommendation.Status status) throws SQLException {
        List<Recommendation> list = new ArrayList<>();
        String sql = "SELECT r.id, r.food_item_id, f.name AS food_name, r.category, r.risk_level, " +
                     "r.title, r.description, r.reasoning_details, r.estimated_savings, r.status, r.created_at, r.updated_at " +
                     "FROM recommendations r JOIN food_items f ON r.food_item_id = f.id " +
                     "WHERE r.status = ? ORDER BY r.created_at DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.name());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToRecommendation(rs));
                }
            }
        }
        return list;
    }

    public Recommendation save(Recommendation rec) throws SQLException {
        String sql = "INSERT INTO recommendations (food_item_id, category, risk_level, title, description, reasoning_details, estimated_savings, status) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, rec.getFoodItemId());
            stmt.setString(2, rec.getCategory().name());
            stmt.setString(3, rec.getRiskLevel().name());
            stmt.setString(4, rec.getTitle());
            stmt.setString(5, rec.getDescription());
            stmt.setString(6, rec.getReasoningDetails());
            stmt.setBigDecimal(7, rec.getEstimatedSavings());
            stmt.setString(8, rec.getStatus() != null ? rec.getStatus().name() : Recommendation.Status.PENDING.name());

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        rec.setId(keys.getLong(1));
                    }
                }
            }
            return rec;
        }
    }

    public boolean updateStatus(Long id, Recommendation.Status status) throws SQLException {
        String sql = "UPDATE recommendations SET status = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.name());
            stmt.setLong(2, id);
            return stmt.executeUpdate() > 0;
        }
    }

    public int clearPendingRecommendations() throws SQLException {
        String sql = "DELETE FROM recommendations WHERE status = 'PENDING'";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            return stmt.executeUpdate();
        }
    }

    private Recommendation mapResultSetToRecommendation(ResultSet rs) throws SQLException {
        Recommendation rec = new Recommendation();
        rec.setId(rs.getLong("id"));
        rec.setFoodItemId(rs.getLong("food_item_id"));
        rec.setFoodItemName(rs.getString("food_name"));
        rec.setCategory(Recommendation.Category.valueOf(rs.getString("category")));
        rec.setRiskLevel(Recommendation.RiskLevel.valueOf(rs.getString("risk_level")));
        rec.setTitle(rs.getString("title"));
        rec.setDescription(rs.getString("description"));
        rec.setReasoningDetails(rs.getString("reasoning_details"));
        rec.setEstimatedSavings(rs.getBigDecimal("estimated_savings"));
        rec.setStatus(Recommendation.Status.valueOf(rs.getString("status")));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) rec.setCreatedAt(created.toLocalDateTime());

        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) rec.setUpdatedAt(updated.toLocalDateTime());

        return rec;
    }
}
