package com.foodwasteai.dao;

import com.foodwasteai.model.Redistribution;
import com.foodwasteai.model.RedistributionRecipient;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for Surplus Food Redistribution and Recipients.
 */
public class RedistributionDao extends BaseDao {

    public List<Redistribution> findAllDispatches() throws SQLException {
        List<Redistribution> list = new ArrayList<>();
        String sql = "SELECT r.id, r.food_item_id, f.name AS food_name, r.recipient_id, rc.name AS recipient_name, " +
                     "r.quantity, r.unit, r.pickup_time, r.status, r.notes, r.notes_en, r.notes_my, r.created_at, r.updated_at " +
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

    public Optional<RedistributionRecipient> findRecipientById(Long id) throws SQLException {
        if (id == null) return Optional.empty();
        String sql = "SELECT id, name, organization_type, contact_person, phone, email, address, active, created_at " +
                     "FROM redistribution_recipients WHERE id = ? AND active = TRUE";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    RedistributionRecipient r = new RedistributionRecipient();
                    r.setId(rs.getLong("id"));
                    r.setName(rs.getString("name"));
                    r.setOrganizationType(rs.getString("organization_type"));
                    r.setContactPerson(rs.getString("contact_person"));
                    r.setPhone(rs.getString("phone"));
                    r.setEmail(rs.getString("email"));
                    r.setAddress(rs.getString("address"));
                    r.setActive(rs.getBoolean("active"));
                    return Optional.of(r);
                }
            }
        }
        return Optional.empty();
    }

    public RedistributionRecipient saveRecipient(RedistributionRecipient r) throws SQLException {
        String sql = "INSERT INTO redistribution_recipients (name, organization_type, contact_person, phone, email, address, active) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, r.getName());
            stmt.setString(2, r.getOrganizationType());
            stmt.setString(3, r.getContactPerson());
            stmt.setString(4, r.getPhone());
            stmt.setString(5, r.getEmail());
            stmt.setString(6, r.getAddress());
            stmt.setBoolean(7, r.isActive());

            int affected = stmt.executeUpdate();
            if (affected > 0) {
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        r.setId(keys.getLong(1));
                    }
                }
            }
            return r;
        }
    }

    public boolean deleteRecipient(Long id) throws SQLException {
        if (id == null) return false;
        String sql = "DELETE FROM redistribution_recipients WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            return stmt.executeUpdate() > 0;
        }
    }

    public Redistribution saveDispatch(Redistribution d) throws SQLException {
        String sql = "INSERT INTO redistributions (food_item_id, recipient_id, quantity, unit, pickup_time, status, notes, notes_en, notes_my) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, d.getFoodItemId());
            stmt.setLong(2, d.getRecipientId());
            stmt.setBigDecimal(3, d.getQuantity());
            stmt.setString(4, d.getUnit() != null ? d.getUnit() : "kg");
            stmt.setTimestamp(5, d.getPickupTime() != null ? Timestamp.valueOf(d.getPickupTime()) : Timestamp.valueOf(java.time.LocalDateTime.now()));
            stmt.setString(6, d.getStatus() != null ? d.getStatus().name() : Redistribution.Status.PENDING.name());
            stmt.setString(7, d.getNotes());
            stmt.setString(8, d.getNotesEn());
            stmt.setString(9, d.getNotesMy());

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
            stmt.setLong(2, id);
            try {
                stmt.setString(1, status.name());
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                if (status == Redistribution.Status.COMPLETED) {
                    stmt.setString(1, "COLLECTED");
                    return stmt.executeUpdate() > 0;
                }
                throw e;
            }
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

        String statusStr = rs.getString("status");
        if ("COLLECTED".equalsIgnoreCase(statusStr)) {
            d.setStatus(Redistribution.Status.COMPLETED);
        } else {
            try {
                d.setStatus(Redistribution.Status.valueOf(statusStr.toUpperCase()));
            } catch (Exception e) {
                d.setStatus(Redistribution.Status.PENDING);
            }
        }
        d.setNotes(rs.getString("notes"));
        d.setNotesEn(rs.getString("notes_en"));
        d.setNotesMy(rs.getString("notes_my"));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) d.setCreatedAt(created.toLocalDateTime());

        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) d.setUpdatedAt(updated.toLocalDateTime());

        return d;
    }
}
