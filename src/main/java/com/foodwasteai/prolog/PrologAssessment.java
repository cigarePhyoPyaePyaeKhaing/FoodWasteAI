package com.foodwasteai.prolog;

import java.io.Serializable;
import java.time.LocalDate;
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
    private double remainingQuantity;
    private double expectedDemand;
    private int expiryDays;
    private Integer currentDaysRemaining;
    private LocalDate expiryDate;
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
    private String redistributionStatus; // PRIORITY_DONATION, DONATION_RECOMMENDED, NOT_NEEDED_YET, NO_SURPLUS, OUT_OF_STOCK, UNSAFE, EXPIRED_NOT_FOR_HUMAN_DONATION
    private String redistributionPriority; // HIGH, RECOMMENDED, LOW, NONE, BLOCKED
    private String redistributionReason;
    private String redistributionReasonEn;
    private String redistributionReasonMy;
    private String redistributionStatusLabelEn;
    private String redistributionStatusLabelMy;
    private String redistributionSuggestedActionEn;
    private String redistributionSuggestedActionMy;
    private String category;
    private double projectedSurplus;
    private boolean redistributionEligible;
    private String engineUsed; // "SWI-Prolog Expert Engine" or "Development Safe Fallback"

    public PrologAssessment() {}

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

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
        this.remainingQuantity = stock;
    }

    public double getRemainingQuantity() {
        return stock;
    }

    public void setRemainingQuantity(double remainingQuantity) {
        setStock(remainingQuantity);
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

    public Integer getCurrentDaysRemaining() {
        if (currentDaysRemaining != null) {
            return currentDaysRemaining;
        }
        if (expiryDate != null) {
            return com.foodwasteai.util.ExpiryStatusResolver.calculateDaysRemaining(expiryDate);
        }
        return expiryDays;
    }

    public void setCurrentDaysRemaining(Integer currentDaysRemaining) {
        this.currentDaysRemaining = currentDaysRemaining;
    }

    public Integer getExpiryDaysRemaining() {
        return getCurrentDaysRemaining();
    }

    public void setExpiryDaysRemaining(Integer expiryDaysRemaining) {
        this.currentDaysRemaining = expiryDaysRemaining;
    }

    public Integer getCurrent_days_remaining() {
        return getCurrentDaysRemaining();
    }

    public Integer getExpiry_days_remaining() {
        return getCurrentDaysRemaining();
    }

    public double getQuantity() {
        return stock;
    }

    public String getName() {
        return foodName != null ? foodName : foodItemName;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
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
        return recommendRedistribution || redistributionEligible;
    }

    public void setRecommendRedistribution(boolean recommendRedistribution) {
        this.recommendRedistribution = recommendRedistribution;
        this.redistributionEligible = recommendRedistribution;
    }

    public boolean isRedistributionEligible() {
        return redistributionEligible || recommendRedistribution;
    }

    public void setRedistributionEligible(boolean redistributionEligible) {
        this.redistributionEligible = redistributionEligible;
        this.recommendRedistribution = redistributionEligible;
    }

    public String getRedistributionStatus() {
        return redistributionStatus != null ? redistributionStatus : "NOT_ELIGIBLE";
    }

    public void setRedistributionStatus(String redistributionStatus) {
        this.redistributionStatus = redistributionStatus;
    }

    public String getRedistributionReason() {
        return redistributionReason != null ? redistributionReason : "";
    }

    public void setRedistributionReason(String redistributionReason) {
        this.redistributionReason = redistributionReason;
    }

    public String getRedistributionPriority() {
        return redistributionPriority != null ? redistributionPriority : "NONE";
    }

    public void setRedistributionPriority(String redistributionPriority) {
        this.redistributionPriority = redistributionPriority;
    }

    public String getRedistributionReasonEn() {
        return redistributionReasonEn != null ? redistributionReasonEn : getRedistributionReason();
    }

    public void setRedistributionReasonEn(String redistributionReasonEn) {
        this.redistributionReasonEn = redistributionReasonEn;
    }

    public String getRedistributionReasonMy() {
        return redistributionReasonMy != null ? redistributionReasonMy : "";
    }

    public void setRedistributionReasonMy(String redistributionReasonMy) {
        this.redistributionReasonMy = redistributionReasonMy;
    }

    public String getRedistributionStatusLabelEn() {
        return redistributionStatusLabelEn != null ? redistributionStatusLabelEn : getRedistributionStatus();
    }

    public void setRedistributionStatusLabelEn(String redistributionStatusLabelEn) {
        this.redistributionStatusLabelEn = redistributionStatusLabelEn;
    }

    public String getRedistributionStatusLabelMy() {
        return redistributionStatusLabelMy != null ? redistributionStatusLabelMy : "";
    }

    public void setRedistributionStatusLabelMy(String redistributionStatusLabelMy) {
        this.redistributionStatusLabelMy = redistributionStatusLabelMy;
    }

    public String getRedistributionSuggestedActionEn() {
        return redistributionSuggestedActionEn != null ? redistributionSuggestedActionEn : "";
    }

    public void setRedistributionSuggestedActionEn(String redistributionSuggestedActionEn) {
        this.redistributionSuggestedActionEn = redistributionSuggestedActionEn;
    }

    public String getRedistributionSuggestedActionMy() {
        return redistributionSuggestedActionMy != null ? redistributionSuggestedActionMy : "";
    }

    public void setRedistributionSuggestedActionMy(String redistributionSuggestedActionMy) {
        this.redistributionSuggestedActionMy = redistributionSuggestedActionMy;
    }

    public double getProjectedSurplus() {
        return projectedSurplus;
    }

    public void setProjectedSurplus(double projectedSurplus) {
        this.projectedSurplus = Math.max(0.0, projectedSurplus);
        this.suggestedDonationQuantity = this.projectedSurplus;
    }

    private double suggestedDonationQuantity;

    public double getSuggestedDonationQuantity() {
        return projectedSurplus > 0 ? projectedSurplus : suggestedDonationQuantity;
    }

    public void setSuggestedDonationQuantity(double suggestedDonationQuantity) {
        this.suggestedDonationQuantity = Math.max(0.0, suggestedDonationQuantity);
        if (this.projectedSurplus <= 0 && suggestedDonationQuantity > 0) {
            this.projectedSurplus = suggestedDonationQuantity;
        }
    }

    public double getProjected_surplus() {
        return getProjectedSurplus();
    }

    public double getSuggested_donation_quantity() {
        return getSuggestedDonationQuantity();
    }

    public double getExpected_demand() {
        return getExpectedDemand();
    }

    public String getEngineUsed() {
        return engineUsed;
    }

    public void setEngineUsed(String engineUsed) {
        this.engineUsed = engineUsed;
    }
}
