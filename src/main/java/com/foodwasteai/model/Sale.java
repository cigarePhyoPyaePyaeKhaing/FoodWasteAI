package com.foodwasteai.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Model representing food sales transactions and customer demand velocity.
 */
public class Sale implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long foodItemId;
    private String foodItemName; // Joined
    private String unit;         // Joined measurement unit (e.g. liter, kg, pieces)
    private BigDecimal quantitySold;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private Integer customerCount;
    private LocalDateTime saleDate;
    private LocalDateTime createdAt;
    private String clientRequestId; // Idempotency token to prevent duplicate transactions

    public Sale() {}

    public Sale(Long foodItemId, BigDecimal quantitySold, BigDecimal unitPrice, BigDecimal totalAmount, Integer customerCount, LocalDateTime saleDate) {
        this.foodItemId = foodItemId;
        this.quantitySold = quantitySold;
        this.unitPrice = unitPrice;
        this.totalAmount = totalAmount;
        this.customerCount = customerCount;
        this.saleDate = saleDate;
    }

    public Sale(Long foodItemId, BigDecimal quantitySold, String unit, BigDecimal unitPrice, BigDecimal totalAmount, Integer customerCount, LocalDateTime saleDate) {
        this.foodItemId = foodItemId;
        this.quantitySold = quantitySold;
        this.unit = unit;
        this.unitPrice = unitPrice;
        this.totalAmount = totalAmount;
        this.customerCount = customerCount;
        this.saleDate = saleDate;
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

    public BigDecimal getQuantitySold() {
        return quantitySold;
    }

    public void setQuantitySold(BigDecimal quantitySold) {
        this.quantitySold = quantitySold;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Integer getCustomerCount() {
        return customerCount;
    }

    public void setCustomerCount(Integer customerCount) {
        this.customerCount = customerCount;
    }

    public LocalDateTime getSaleDate() {
        return saleDate;
    }

    public void setSaleDate(LocalDateTime saleDate) {
        this.saleDate = saleDate;
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
