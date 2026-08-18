package com.foodwasteai.prolog;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates structured reasoning results returned from SWI-Prolog expert system.
 */
public class PrologAssessment implements Serializable {
    private static final long serialVersionUID = 1L;

    private String foodName;
    private double stock;
    private double expectedDemand;
    private int expiryDays;
    private double historicalWasteRate;
    private String riskLevel; // HIGH, MEDIUM, LOW
    private double riskPercentage; // e.g., 85%
    private List<String> reasons = new ArrayList<>();
    private double recommendedProduction;
    private String recommendedAction;
    private String priorityUsage; // IMMEDIATE_USE, HIGH_PRIORITY, MODERATE_PRIORITY, STANDARD
    private boolean recommendRedistribution;
    private String engineUsed; // "SWI-Prolog Subprocess" or "Development Safe Fallback"

    public PrologAssessment() {}

    // Getters and Setters
    public String getFoodName() {
        return foodName;
    }

    public void setFoodName(String foodName) {
        this.foodName = foodName;
    }

    public double getStock() {
        return stock;
    }

    public void setStock(double stock) {
        this.stock = stock;
    }

    public double getExpectedDemand() {
        return expectedDemand;
    }

    public void setExpectedDemand(double expectedDemand) {
        this.expectedDemand = expectedDemand;
    }

    public int getExpiryDays() {
        return expiryDays;
    }

    public void setExpiryDays(int expiryDays) {
        this.expiryDays = expiryDays;
    }

    public double getHistoricalWasteRate() {
        return historicalWasteRate;
    }

    public void setHistoricalWasteRate(double historicalWasteRate) {
        this.historicalWasteRate = historicalWasteRate;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public double getRiskPercentage() {
        return riskPercentage;
    }

    public void setRiskPercentage(double riskPercentage) {
        this.riskPercentage = riskPercentage;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public void setReasons(List<String> reasons) {
        this.reasons = reasons;
    }

    public void addReason(String reason) {
        this.reasons.add(reason);
    }

    public double getRecommendedProduction() {
        return recommendedProduction;
    }

    public void setRecommendedProduction(double recommendedProduction) {
        this.recommendedProduction = recommendedProduction;
    }

    public String getRecommendedAction() {
        return recommendedAction;
    }

    public void setRecommendedAction(String recommendedAction) {
        this.recommendedAction = recommendedAction;
    }

    public String getPriorityUsage() {
        return priorityUsage;
    }

    public void setPriorityUsage(String priorityUsage) {
        this.priorityUsage = priorityUsage;
    }

    public boolean isRecommendRedistribution() {
        return recommendRedistribution;
    }

    public void setRecommendRedistribution(boolean recommendRedistribution) {
        this.recommendRedistribution = recommendRedistribution;
    }

    public String getEngineUsed() {
        return engineUsed;
    }

    public void setEngineUsed(String engineUsed) {
        this.engineUsed = engineUsed;
    }
}
