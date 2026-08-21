package com.foodwasteai.prolog;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates structured Explainable AI reasoning results returned from SWI-Prolog expert system.
 * Serves as the single authoritative source of truth for risk score, predicted waste quantity, and units.
 */
public class PrologAssessment implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long foodItemId;
    private String foodName;
    private String foodItemName; // Alias
    private String item; // Output alias for foodName
    private String unit = "kg"; // Preserved food item unit
    private double stock;
    private double expectedDemand;
    private int expiryDays;
    private double historicalWasteRate;
    private String riskLevel; // HIGH, MEDIUM, LOW
    private String risk; // Output alias for riskLevel
    private double riskScore; // Authoritative expert system risk score e.g. 85.0%
    private double riskPercentage; // e.g. 85.0% (synchronized with riskScore)
    private double predictedWasteQuantity; // Calculated predicted waste in item unit
    private List<String> reasons = new ArrayList<>();
    private List<String> reasonsMy = new ArrayList<>();
    private String reason; // Primary explanation reason
    private String reasoning; // Alias
    private String reasonEn;
    private String reasonMy;
    private String recommendation; // Actionable summary recommendation
    private String recommendationEn;
    private String recommendationMy;
    private double recommendedProduction;
    private String recommendedAction;
    private String action; // Alias
    private String priorityUsage; // IMMEDIATE_USE, HIGH_PRIORITY, MODERATE_PRIORITY, STANDARD
    private String priority; // Alias
    private boolean recommendRedistribution;
    private String engineUsed; // "SWI-Prolog Expert Engine" or "Development Safe Fallback"

    public PrologAssessment() {}

    public String getUnit() {
        return unit != null ? unit : "kg";
    }

    public void setUnit(String unit) {
        this.unit = unit != null ? unit.trim() : "kg";
    }

    public double getPredictedWasteQuantity() {
        return predictedWasteQuantity;
    }

    public void setPredictedWasteQuantity(double predictedWasteQuantity) {
        this.predictedWasteQuantity = Math.max(0.0, predictedWasteQuantity);
    }

    public double getPredictedWasteQty() {
        return predictedWasteQuantity;
    }

    public void setPredictedWasteQty(double qty) {
        this.predictedWasteQuantity = Math.max(0.0, qty);
    }

    public double getRiskScore() {
        return riskPercentage > 0 ? riskPercentage : riskScore;
    }

    public void setRiskScore(double riskScore) {
        this.riskScore = riskScore;
        this.riskPercentage = riskScore;
    }

    public double getRiskPercentage() {
        return riskPercentage > 0 ? riskPercentage : riskScore;
    }

    public void setRiskPercentage(double riskPercentage) {
        this.riskPercentage = riskPercentage;
        this.riskScore = riskPercentage;
    }

    public String getReasonEn() {
        return reasonEn != null ? reasonEn : getReason();
    }

    public void setReasonEn(String reasonEn) {
        this.reasonEn = reasonEn;
    }

    public String getReasonMy() {
        return reasonMy;
    }

    public void setReasonMy(String reasonMy) {
        this.reasonMy = reasonMy;
    }

    public String getRecommendationEn() {
        return recommendationEn != null ? recommendationEn : getRecommendation();
    }

    public void setRecommendationEn(String recommendationEn) {
        this.recommendationEn = recommendationEn;
    }

    public String getRecommendationMy() {
        return recommendationMy;
    }

    public void setRecommendationMy(String recommendationMy) {
        this.recommendationMy = recommendationMy;
    }

    public List<String> getReasonsMy() {
        return reasonsMy;
    }

    public void setReasonsMy(List<String> reasonsMy) {
        this.reasonsMy = reasonsMy;
    }

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
        this.foodItemName = foodName;
        this.item = foodName;
    }

    public String getFoodItemName() {
        return foodItemName != null ? foodItemName : foodName;
    }

    public void setFoodItemName(String foodItemName) {
        this.foodItemName = foodItemName;
        this.foodName = foodItemName;
        this.item = foodItemName;
    }

    public String getItem() {
        return item != null ? item : foodName;
    }

    public void setItem(String item) {
        this.item = item;
        this.foodName = item;
        this.foodItemName = item;
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

    public List<String> getReasons() {
        return reasons;
    }

    public void setReasons(List<String> reasons) {
        this.reasons = reasons;
        if (reasons != null && !reasons.isEmpty()) {
            this.reason = reasons.get(0);
            this.reasoning = this.reason;
        }
    }

    public void addReason(String reason) {
        this.reasons.add(reason);
        if (this.reason == null || this.reason.isEmpty()) {
            this.reason = reason;
            this.reasoning = reason;
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
        this.reasoning = reason;
        if (reason != null && !reason.isEmpty() && this.reasons.isEmpty()) {
            this.reasons.add(reason);
        }
    }

    public String getReasoning() {
        return getReason();
    }

    public void setReasoning(String reasoning) {
        setReason(reasoning);
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
        this.action = recommendedAction;
        this.recommendation = recommendedAction;
    }

    public String getAction() {
        return getRecommendedAction();
    }

    public void setAction(String action) {
        setRecommendedAction(action);
    }

    public String getPriorityUsage() {
        return priorityUsage;
    }

    public void setPriorityUsage(String priorityUsage) {
        this.priorityUsage = priorityUsage;
        this.priority = priorityUsage;
    }

    public String getPriority() {
        return priorityUsage;
    }

    public void setPriority(String priority) {
        this.priority = priority;
        this.priorityUsage = priority;
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
