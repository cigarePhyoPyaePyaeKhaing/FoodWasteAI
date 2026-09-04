package com.foodwasteai.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Model representing inventory stock transactions (Purchase, Usage, Waste Adjustment, Redistribution, Count).
 */
public class InventoryTransaction implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        PURCHASE,
        USAGE,
        WASTE_ADJUSTMENT,
        REDISTRIBUTION,
        MANUAL_COUNT
    }

    private Long id;
    private Long foodItemId;
    private String foodItemName; // Joined for display convenience
    private Type transactionType;
    private BigDecimal quantity;
    private String unit;
    private String notes;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;

    public InventoryTransaction() {}

    public InventoryTransaction(Long foodItemId, Type transactionType, BigDecimal quantity, String unit, String notes, Long createdBy) {
        this.foodItemId = foodItemId;
        this.transactionType = transactionType;
        this.quantity = quantity;
        this.unit = unit;
        this.notes = notes;
        this.createdBy = createdBy;
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

    public Type getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(Type transactionType) {
        this.transactionType = transactionType;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
