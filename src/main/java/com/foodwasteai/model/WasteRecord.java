package com.foodwasteai.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Model representing recorded food waste incidents, categorized reasons, and monetary losses.
 */
public class WasteRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        EXPIRED,
        OVERPRODUCTION,
        UNSOLD,
        SPOILED,
        DAMAGED,
        PREPARATION_WASTE,
        OTHER
    }

    private Long id;
    private Long foodItemId;
    private String foodItemName; // Joined
    private String unit;         // Joined measurement unit (e.g. liter, kg, pieces)
    private BigDecimal quantityWasted;
    private Reason reason;
    private BigDecimal monetaryLoss;
    private LocalDateTime wasteDate;
    private String notes;
    private LocalDateTime createdAt;
    private String clientRequestId; // Idempotency token to prevent duplicate waste submissions

    public WasteRecord() {}

    public WasteRecord(Long foodItemId, BigDecimal quantityWasted, Reason reason, BigDecimal monetaryLoss, LocalDateTime wasteDate, String notes) {
        this.foodItemId = foodItemId;
        this.quantityWasted = quantityWasted;
        this.reason = reason;
        this.monetaryLoss = monetaryLoss;
        this.wasteDate = wasteDate;
        this.notes = notes;
    }

    public WasteRecord(Long foodItemId, BigDecimal quantityWasted, String unit, Reason reason, BigDecimal monetaryLoss, LocalDateTime wasteDate, String notes) {
        this.foodItemId = foodItemId;
        this.quantityWasted = quantityWasted;
        this.unit = unit;
        this.reason = reason;
        this.monetaryLoss = monetaryLoss;
        this.wasteDate = wasteDate;
        this.notes = notes;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFoodItemId() {
        return foodItemId;
    }

    public void setFoodItemId(Long foodItemId) {
        this.foodItemId = foodItemId;
    }

    public String getFoodItemName() {
        return foodItemName;
    }

    public void setFoodItemName(String foodItemName) {
        this.foodItemName = foodItemName;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getQuantityWasted() {
        return quantityWasted;
    }

    public void setQuantityWasted(BigDecimal quantityWasted) {
        this.quantityWasted = quantityWasted;
    }

    public Reason getReason() {
        return reason;
    }

    public void setReason(Reason reason) {
        this.reason = reason;
    }

    public BigDecimal getMonetaryLoss() {
        return monetaryLoss;
    }

    public void setMonetaryLoss(BigDecimal monetaryLoss) {
        this.monetaryLoss = monetaryLoss;
    }

    public LocalDateTime getWasteDate() {
        return wasteDate;
    }

    public void setWasteDate(LocalDateTime wasteDate) {
        this.wasteDate = wasteDate;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }
}
