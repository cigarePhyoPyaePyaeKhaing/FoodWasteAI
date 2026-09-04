package com.foodwasteai.dao;

import com.foodwasteai.model.Recommendation;

import java.sql.*;
import java.util.*;

/**
 * Data Access Object for AI recommendations with prepared statements.
 */
public class RecommendationDao extends BaseDao {

    public Optional<Recommendation> findById(Long id) throws SQLException {
        String sql = "SELECT r.id, r.food_item_id, f.name AS food_name, r.category, r.risk_level, " +
                     "r.title, r.title_en, r.title_my, r.description, r.description_en, r.description_my, " +
                     "r.reasoning_details, r.reasoning_details_en, r.reasoning_details_my, r.estimated_savings, r.status, r.created_at, r.updated_at " +
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
        return findActiveRecommendations();
    }

    public List<Recommendation> findActiveRecommendations() throws SQLException {
        List<Recommendation> list = new ArrayList<>();
        String sql = "SELECT r.id, r.food_item_id, f.name AS food_name, f.quantity AS stock, " +
                     "r.category, r.risk_level, r.title, r.title_en, r.title_my, r.description, r.description_en, r.description_my, " +
                     "r.reasoning_details, r.reasoning_details_en, r.reasoning_details_my, r.estimated_savings, r.status, r.created_at, r.updated_at " +
                     "FROM recommendations r JOIN food_items f ON r.food_item_id = f.id " +
                     "WHERE f.quantity > 0 AND r.status != 'DISMISSED' " +
                     "ORDER BY r.id DESC";

        Set<String> seenActions = new HashSet<>();
        Map<Long, Recommendation.RiskLevel> latestItemRisk = new HashMap<>();

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Recommendation rec = mapResultSetToRecommendation(rs);
                Long foodId = rec.getFoodItemId();

                // Maintain single authoritative risk level consistency per food item
                Recommendation.RiskLevel itemRisk = latestItemRisk.computeIfAbsent(foodId, k -> rec.getRiskLevel());
                if (rec.getRiskLevel() != itemRisk) {
                    continue; // Skip stale rows from previous runs with contradictory risk levels
                }

                // Deduplicate by foodItemId + category + actionTitle
                String dedupeKey = foodId + "_" + rec.getCategory().name() + "_" + rec.getTitle();
                if (seenActions.add(dedupeKey)) {
                    list.add(rec);
                }
            }
        }
        list.sort(Comparator.comparing(Recommendation::getCategory));
        return list;
    }

    public List<Recommendation> findByStatus(Recommendation.Status status) throws SQLException {
        List<Recommendation> list = new ArrayList<>();
        String sql = "SELECT r.id, r.food_item_id, f.name AS food_name, f.quantity AS stock, " +
                     "r.category, r.risk_level, r.title, r.title_en, r.title_my, r.description, r.description_en, r.description_my, " +
                     "r.reasoning_details, r.reasoning_details_en, r.reasoning_details_my, r.estimated_savings, r.status, r.created_at, r.updated_at " +
                     "FROM recommendations r JOIN food_items f ON r.food_item_id = f.id " +
                     "WHERE f.quantity > 0 AND r.status = ? " +
                     "ORDER BY r.id DESC";

        Set<String> seenActions = new HashSet<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.name());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Recommendation rec = mapResultSetToRecommendation(rs);
                    String dedupeKey = rec.getFoodItemId() + "_" + rec.getCategory().name() + "_" + rec.getTitle();
                    if (seenActions.add(dedupeKey)) {
                        list.add(rec);
                    }
                }
            }
        }
        list.sort(Comparator.comparing(Recommendation::getCategory));
        return list;
    }

    public Recommendation save(Recommendation rec) throws SQLException {
        String sql = "INSERT INTO recommendations (food_item_id, category, risk_level, title, title_en, title_my, " +
                     "description, description_en, description_my, reasoning_details, reasoning_details_en, reasoning_details_my, " +
                     "estimated_savings, status) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, rec.getFoodItemId());
            stmt.setString(2, rec.getCategory().name());
            stmt.setString(3, rec.getRiskLevel().name());
            stmt.setString(4, rec.getTitle());
            stmt.setString(5, rec.getTitleEn());
            stmt.setString(6, rec.getTitleMy());
            stmt.setString(7, rec.getDescription());
            stmt.setString(8, rec.getDescriptionEn());
            stmt.setString(9, rec.getDescriptionMy());
            stmt.setString(10, rec.getReasoningDetails());
            stmt.setString(11, rec.getReasoningDetailsEn());
            stmt.setString(12, rec.getReasoningDetailsMy());
            stmt.setBigDecimal(13, rec.getEstimatedSavings());
            stmt.setString(14, rec.getStatus() != null ? rec.getStatus().name() : Recommendation.Status.PENDING.name());

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

    public int clearAllRecommendations() throws SQLException {
        String sql = "DELETE FROM recommendations";
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
        rec.setTitleEn(rs.getString("title_en"));
        rec.setTitleMy(rs.getString("title_my"));
        rec.setDescription(rs.getString("description"));
        rec.setDescriptionEn(rs.getString("description_en"));
        rec.setDescriptionMy(rs.getString("description_my"));
        rec.setReasoningDetails(rs.getString("reasoning_details"));
        rec.setReasoningDetailsEn(rs.getString("reasoning_details_en"));
        rec.setReasoningDetailsMy(rs.getString("reasoning_details_my"));
        rec.setEstimatedSavings(rs.getBigDecimal("estimated_savings"));
        rec.setStatus(Recommendation.Status.valueOf(rs.getString("status")));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) rec.setCreatedAt(created.toLocalDateTime());

        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) rec.setUpdatedAt(updated.toLocalDateTime());

        return rec;
    }
}
