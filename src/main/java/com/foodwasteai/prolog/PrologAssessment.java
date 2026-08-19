package com.foodwasteai.prolog;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates structured Explainable AI reasoning results returned from SWI-Prolog expert system.
 */
public class PrologAssessment implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long foodItemId;
    private String foodName;
    private String item; // Output alias for foodName
    private double stock;
    private double expectedDemand;
    private int expiryDays;
    private double historicalWasteRate;
    private String riskLevel; // HIGH, MEDIUM, LOW
    private String risk; // Output alias for riskLevel
    private double riskPercentage; // e.g., 85.0%
    private List<String> reasons = new ArrayList<>();
    private String reason; // Primary explanation reason
    private String recommendation; // Actionable summary recommendation
    private double recommendedProduction;
    private String recommendedAction;
    private String priorityUsage; // IMMEDIATE_USE, HIGH_PRIORITY, MODERATE_PRIORITY, STANDARD
    private boolean recommendRedistribution;
    private String engineUsed; // "SWI-Prolog Expert Engine" or "Development Safe Fallback"

    public PrologAssessment() {}

    // Getters and Setters
    public Long getFoodItemId() {
        return foodItemId;
    }

    public void setFoodItemId(Long foodItemId) {
        this.foodItemId = foodItemId;
    }

    public String getFoodName() {
        return foodName;
    }

    public void setFoodName(String foodName) {
        this.foodName = foodName;
        this.item = foodName;
    }

    public String getItem() {
        return item != null ? item : foodName;
    }

    public void setItem(String item) {
        this.item = item;
        this.foodName = item;
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
        this.risk = riskLevel;
    }

    public String getRisk() {
        return risk != null ? risk : riskLevel;
    }

    public void setRisk(String risk) {
        this.risk = risk;
        this.riskLevel = risk;
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
        if (reasons != null && !reasons.isEmpty()) {
            this.reason = reasons.get(0);
        }
    }

    public void addReason(String reason) {
        this.reasons.add(reason);
        if (this.reason == null || this.reason.isEmpty()) {
            this.reason = reason;
        }
    }

    public String getReason() {
        if (reason != null && !reason.isEmpty()) {
            return reason;
        }
        if (reasons != null && !reasons.isEmpty()) {
            return reasons.get(0);
        }
        return "";
    }

    public void setReason(String reason) {
        this.reason = reason;
        if (reason != null && !reason.isEmpty() && this.reasons.isEmpty()) {
            this.reasons.add(reason);
        }
    }

    public String getRecommendation() {
        if (recommendation != null) return recommendation;
        return recommendedAction;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
        if (this.recommendedAction == null) {
            this.recommendedAction = recommendation;
        }
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
        this.recommendation = recommendedAction;
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
