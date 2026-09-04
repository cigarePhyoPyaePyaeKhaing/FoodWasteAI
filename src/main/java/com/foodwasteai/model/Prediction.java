package com.foodwasteai.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Model representing a generated batch AI waste prediction run.
 */
public class Prediction implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Status {
        GENERATED,
        APPLIED,
        ARCHIVED
    }

    private Long id;
    private LocalDate predictionDate;
    private BigDecimal overallRiskScore; // 0-100%
    private BigDecimal expectedTotalWasteKg;
    private BigDecimal estimatedMoneyLost;
    private BigDecimal potentialSavings;
    private Status status;
    private LocalDateTime createdAt;
    private List<PredictionItem> items = new ArrayList<>();

    public Prediction() {}

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getPredictionDate() {
        return predictionDate;
    }

    public void setPredictionDate(LocalDate predictionDate) {
        this.predictionDate = predictionDate;
    }

    public BigDecimal getOverallRiskScore() {
        return overallRiskScore;
    }

    public void setOverallRiskScore(BigDecimal overallRiskScore) {
        this.overallRiskScore = overallRiskScore;
    }

    public BigDecimal getExpectedTotalWasteKg() {
        return expectedTotalWasteKg;
    }

    public void setExpectedTotalWasteKg(BigDecimal expectedTotalWasteKg) {
        this.expectedTotalWasteKg = expectedTotalWasteKg;
    }

    public BigDecimal getEstimatedMoneyLost() {
        return estimatedMoneyLost;
    }

    public void setEstimatedMoneyLost(BigDecimal estimatedMoneyLost) {
        this.estimatedMoneyLost = estimatedMoneyLost;
    }

    public BigDecimal getPotentialSavings() {
        return potentialSavings;
    }

    public void setPotentialSavings(BigDecimal potentialSavings) {
        this.potentialSavings = potentialSavings;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<PredictionItem> getItems() {
        return items;
    }

    public void setItems(List<PredictionItem> items) {
        this.items = items;
    }
}
