package com.foodwasteai.dao;

import com.foodwasteai.model.Redistribution;
import com.foodwasteai.model.RedistributionRecipient;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for Surplus Food Redistribution and Recipients.
 */
public class RedistributionDao extends BaseDao {

    public List<Redistribution> findAllDispatches() throws SQLException {
        List<Redistribution> list = new ArrayList<>();
        String sql = "SELECT r.id, r.food_item_id, f.name AS food_name, r.recipient_id, rc.name AS recipient_name, " +
                     "r.quantity, r.unit, r.pickup_time, r.status, r.notes, r.created_at, r.updated_at " +
                     "FROM redistributions r " +
                     "JOIN food_items f ON r.food_item_id = f.id " +
                     "JOIN redistribution_recipients rc ON r.recipient_id = rc.id " +
                     "ORDER BY r.pickup_time ASC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(mapResultSetToRedistribution(rs));
            }
        }
        return list;
    }

    public List<RedistributionRecipient> findAllRecipients() throws SQLException {
        List<RedistributionRecipient> list = new ArrayList<>();
        String sql = "SELECT id, name, organization_type, contact_person, phone, email, address, active, created_at " +
                     "FROM redistribution_recipients WHERE active = TRUE ORDER BY name ASC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                RedistributionRecipient r = new RedistributionRecipient();
                r.setId(rs.getLong("id"));
                r.setName(rs.getString("name"));
                r.setOrganizationType(rs.getString("organization_type"));
                r.setContactPerson(rs.getString("contact_person"));
                r.setPhone(rs.getString("phone"));
                r.setEmail(rs.getString("email"));
                r.setAddress(rs.getString("address"));
                r.setActive(rs.getBoolean("active"));
                list.add(r);
            }
        }
        return list;
    }

    public Redistribution saveDispatch(Redistribution d) throws SQLException {
        String sql = "INSERT INTO redistributions (food_item_id, recipient_id, quantity, unit, pickup_time, status, notes) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, d.getFoodItemId());
            stmt.setLong(2, d.getRecipientId());
            stmt.setBigDecimal(3, d.getQuantity());
            stmt.setString(4, d.getUnit() != null ? d.getUnit() : "kg");
            stmt.setTimestamp(5, d.getPickupTime() != null ? Timestamp.valueOf(d.getPickupTime()) : Timestamp.valueOf(java.time.LocalDateTime.now()));
            stmt.setString(6, d.getStatus() != null ? d.getStatus().name() : Redistribution.Status.PENDING.name());
            stmt.setString(7, d.getNotes());

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        d.setId(keys.getLong(1));
                    }
                }
            }
            return d;
        }
    }

    public boolean updateStatus(Long id, Redistribution.Status status) throws SQLException {
        String sql = "UPDATE redistributions SET status = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.name());
            stmt.setLong(2, id);
            return stmt.executeUpdate() > 0;
        }
    }

    private Redistribution mapResultSetToRedistribution(ResultSet rs) throws SQLException {
        Redistribution d = new Redistribution();
        d.setId(rs.getLong("id"));
        d.setFoodItemId(rs.getLong("food_item_id"));
        d.setFoodItemName(rs.getString("food_name"));
        d.setRecipientId(rs.getLong("recipient_id"));
        d.setRecipientName(rs.getString("recipient_name"));
        d.setQuantity(rs.getBigDecimal("quantity"));
        d.setUnit(rs.getString("unit"));

        Timestamp pickup = rs.getTimestamp("pickup_time");
        if (pickup != null) d.setPickupTime(pickup.toLocalDateTime());

        d.setStatus(Redistribution.Status.valueOf(rs.getString("status")));
        d.setNotes(rs.getString("notes"));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) d.setCreatedAt(created.toLocalDateTime());

        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) d.setUpdatedAt(updated.toLocalDateTime());

        return d;
    }
}
