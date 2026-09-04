package com.foodwasteai.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Food item model for inventory tracking.
 */
public class FoodItem implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String category;
    private BigDecimal quantity;
    private BigDecimal totalQuantity;
    private BigDecimal remainingQuantity;
    private String unit; // kg, g, liter, portions, units, etc.
    private BigDecimal pricePerUnit;
    private LocalDate expiryDate;
    private String status; // OK, NEAR_EXPIRY, SAME_DAY_EXPIRY, EXPIRED
    private String expiryStatus; // EXPIRED, SAME_DAY_EXPIRY, NEAR_EXPIRY, SAFE
    private Integer expiryDaysRemaining; // negative, 0, positive
    private String expiryReason; // English standard reason
    private String expiryReasonMy; // Myanmar standard reason
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public FoodItem() {}

    public FoodItem(Long id, String name, String category, BigDecimal quantity, String unit,
                    BigDecimal pricePerUnit, LocalDate expiryDate) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.quantity = quantity;
        this.remainingQuantity = quantity;
        this.totalQuantity = quantity;
        this.unit = unit;
        this.pricePerUnit = pricePerUnit;
        this.expiryDate = expiryDate;
    }

    public FoodItem(Long id, String name, String category, BigDecimal quantity, String unit,
                    BigDecimal pricePerUnit, LocalDate expiryDate, BigDecimal minStockThreshold) {
        this(id, name, category, quantity, unit, pricePerUnit, expiryDate);
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public Long getFoodItemId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getQuantity() {
        return quantity != null ? quantity : (remainingQuantity != null ? remainingQuantity : BigDecimal.ZERO);
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
        this.remainingQuantity = quantity;
        if (this.totalQuantity == null) {
            this.totalQuantity = quantity;
        }
    }

    public BigDecimal getTotalQuantity() {
        if (totalQuantity != null) {
            return totalQuantity;
        }
        return quantity != null ? quantity : BigDecimal.ZERO;
    }

    public void setTotalQuantity(BigDecimal totalQuantity) {
        this.totalQuantity = totalQuantity;
    }

    public BigDecimal getRemainingQuantity() {
        if (remainingQuantity != null) {
            return remainingQuantity;
        }
        return quantity != null ? quantity : BigDecimal.ZERO;
    }

    public void setRemainingQuantity(BigDecimal remainingQuantity) {
        this.remainingQuantity = remainingQuantity;
        this.quantity = remainingQuantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getPricePerUnit() {
        return pricePerUnit;
    }

    public void setPricePerUnit(BigDecimal pricePerUnit) {
        this.pricePerUnit = pricePerUnit;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getExpiryStatus() {
        return expiryStatus;
    }

    public void setExpiryStatus(String expiryStatus) {
        this.expiryStatus = expiryStatus;
    }

    public Integer getExpiryDaysRemaining() {
        return expiryDaysRemaining;
    }

    public void setExpiryDaysRemaining(Integer expiryDaysRemaining) {
        this.expiryDaysRemaining = expiryDaysRemaining;
    }

    public String getExpiryReason() {
        return expiryReason;
    }

    public void setExpiryReason(String expiryReason) {
        this.expiryReason = expiryReason;
    }

    public String getExpiryReasonMy() {
        return expiryReasonMy;
    }

    public void setExpiryReasonMy(String expiryReasonMy) {
        this.expiryReasonMy = expiryReasonMy;
    }

    // Snake_case aliases for API JSON compatibility
    public String getExpiry_status() { return getExpiryStatus(); }
    public Integer getExpiry_days_remaining() { return getExpiryDaysRemaining(); }
    public Integer getCurrentDaysRemaining() {
        if (expiryDaysRemaining != null) {
            return expiryDaysRemaining;
        }
        if (expiryDate != null) {
            return com.foodwasteai.util.ExpiryStatusResolver.calculateDaysRemaining(expiryDate);
        }
        return null;
    }
    public Integer getCurrent_days_remaining() { return getCurrentDaysRemaining(); }
    public String getExpiry_reason() { return getExpiryReason(); }
    public String getExpiry_reason_my() { return getExpiryReasonMy(); }
    public String getDisplayStatus() {
        if (getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return "OUT_OF_STOCK";
        }
        if (expiryDaysRemaining != null && expiryDaysRemaining == 0) {
            return "SAME_DAY_EXPIRY";
        }
        return (expiryStatus != null) ? expiryStatus : status;
    }
    public String getDisplay_status() { return getDisplayStatus(); }

    /**
     * Updates and populates all computed expiry fields using ExpiryStatusResolver.
     */
    public void updateComputedExpiryFields(LocalDate today) {
        LocalDate current = (today != null) ? today : com.foodwasteai.util.ExpiryStatusResolver.getToday();
        this.status = com.foodwasteai.util.ExpiryStatusResolver.resolveStatus(this.expiryDate, current);
        com.foodwasteai.util.ExpiryStatusResolver.ExpiryState state = com.foodwasteai.util.ExpiryStatusResolver.resolveState(this.expiryDate, current);
        this.expiryStatus = state.name();
        this.expiryDaysRemaining = com.foodwasteai.util.ExpiryStatusResolver.calculateDaysRemaining(this.expiryDate, current);
        this.expiryReason = com.foodwasteai.util.ExpiryStatusResolver.getStandardRiskReasonEn(state, this.expiryDaysRemaining != null ? this.expiryDaysRemaining : 999);
        this.expiryReasonMy = com.foodwasteai.util.ExpiryStatusResolver.getStandardRiskReasonMy(state, this.expiryDaysRemaining != null ? this.expiryDaysRemaining : 999);
    }

    public void updateComputedExpiryFields() {
        updateComputedExpiryFields(com.foodwasteai.util.ExpiryStatusResolver.getToday());
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
