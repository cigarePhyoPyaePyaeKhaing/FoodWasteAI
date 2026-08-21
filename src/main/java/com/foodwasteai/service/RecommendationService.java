package com.foodwasteai.service;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.RecommendationDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.Recommendation;
import com.foodwasteai.prolog.PrologAssessment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service managing Actionable AI Recommendations generated from Prolog expert predictions.
 */
public class RecommendationService {
    private static final Logger logger = LoggerFactory.getLogger(RecommendationService.class);
    private final RecommendationDao recommendationDao;
    private final PredictionService predictionService;
    private final FoodItemService foodItemService;

    // Memory store fallback
    private static final Map<Long, Recommendation> memoryRecs = new ConcurrentHashMap<>();
    private static final AtomicLong recIdGen = new AtomicLong(0);

    public RecommendationService() {
        this.recommendationDao = new RecommendationDao();
        this.predictionService = new PredictionService();
        this.foodItemService = new FoodItemService();
    }

    public RecommendationService(RecommendationDao recommendationDao, PredictionService predictionService, FoodItemService foodItemService) {
        this.recommendationDao = recommendationDao;
        this.predictionService = predictionService;
        this.foodItemService = foodItemService;
    }

    public List<Recommendation> getAllRecommendations() throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return recommendationDao.findAll();
        }
        List<Recommendation> list = new ArrayList<>(memoryRecs.values());
        list.sort(Comparator.comparing(Recommendation::getCategory).thenComparing(Recommendation::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    public List<Recommendation> getRecommendationsByCategory(Recommendation.Category category) throws SQLException {
        List<Recommendation> all = getAllRecommendations();
        if (category == null) return all;
        List<Recommendation> filtered = new ArrayList<>();
        for (Recommendation r : all) {
            if (r.getCategory() == category) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    public List<Recommendation> getRecommendationsByStatus(Recommendation.Status status) throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return recommendationDao.findByStatus(status);
        }
        List<Recommendation> filtered = new ArrayList<>();
        for (Recommendation r : memoryRecs.values()) {
            if (r.getStatus() == status) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    public boolean updateRecommendationStatus(Long id, Recommendation.Status status) throws SQLException {
        if (id == null || status == null) return false;
        if (DatabaseConfig.isAvailable()) {
            return recommendationDao.updateStatus(id, status);
        }
        Recommendation r = memoryRecs.get(id);
        if (r != null) {
            r.setStatus(status);
            r.setUpdatedAt(LocalDateTime.now());
            return true;
        }
        return false;
    }

    /**
     * Generates fresh actionable recommendations by running Prolog reasoning across inventory.
     * Connected to AI prediction results:
     * - HIGH Risk: Reduce next production batch, Redistribute excess inventory, Prioritize usage today
     * - MEDIUM Risk: Monitor stock, Adjust preparation quantity, Promote usage
     * - LOW Risk: Maintain normal operation
     */
    public List<Recommendation> generateRecommendationsFromProlog() throws SQLException {
        // 1. Run SWI-Prolog prediction assessment across all inventory items (persists to predictions & prediction_items)
        predictionService.assessAllInventory();

        List<FoodItem> items = foodItemService.getAllFoodItems();
        List<Recommendation> generated = new ArrayList<>();

        if (DatabaseConfig.isAvailable()) {
            recommendationDao.clearPendingRecommendations();
        } else {
            memoryRecs.entrySet().removeIf(e -> e.getValue().getStatus() == Recommendation.Status.PENDING);
        }

        for (FoodItem item : items) {
            Optional<PrologAssessment> opt = predictionService.assessFoodItemById(item.getId());
            if (opt.isPresent()) {
                PrologAssessment assessment = opt.get();
                String unit = item.getUnit() != null ? item.getUnit() : "kg";
                double price = item.getPricePerUnit() != null ? item.getPricePerUnit().doubleValue() : 5000.0;
                double surplus = Math.max(0, assessment.getStock() - assessment.getExpectedDemand());
                int expiryDays = assessment.getExpiryDays();

                // =========================================================================
                // 🔴 HIGH RISK - EXPIRED ITEM DIRECTIVE (Item passed expiration date)
                // =========================================================================
                if (expiryDays < 0 || "DISPOSE_OR_COMPOST".equalsIgnoreCase(assessment.getPriorityUsage())) {
                    Recommendation rExp = new Recommendation();
                    rExp.setFoodItemId(item.getId());
                    rExp.setFoodItemName(item.getName());
                    rExp.setCategory(Recommendation.Category.URGENT);
                    rExp.setRiskLevel(Recommendation.RiskLevel.HIGH);
                    rExp.setTitle("Halt production and dispose of expired " + item.getName());
                    rExp.setTitleEn(rExp.getTitle());
                    rExp.setDescription(String.format("Item has passed expiration date (%d day(s) ago). Do not serve to customers. Halt production and dispose of or compost safely.",
                            Math.max(1, Math.abs(expiryDays))));
                    rExp.setDescriptionEn(rExp.getDescription());
                    rExp.setReasoningDetails("Prolog Rule: assess_waste_risk/6 (High Risk: Expired) -> evaluate_priority_use/3 (DISPOSE_OR_COMPOST)");
                    rExp.setReasoningDetailsEn(rExp.getReasoningDetails());
                    rExp.setEstimatedSavings(BigDecimal.ZERO);
                    rExp.setStatus(Recommendation.Status.PENDING);
                    saveRecommendation(rExp);
                    generated.add(rExp);
                }

                // =========================================================================
                // 🔴 HIGH RISK DIRECTIVES (Near Expiry / Overstock / Critical Waste)
                // =========================================================================
                else if ("HIGH".equalsIgnoreCase(assessment.getRiskLevel())) {
                    // 1. Reduce next production batch
                    Recommendation rProd = new Recommendation();
                    rProd.setFoodItemId(item.getId());
                    rProd.setFoodItemName(item.getName());
                    rProd.setCategory(Recommendation.Category.URGENT);
                    rProd.setRiskLevel(Recommendation.RiskLevel.HIGH);
                    rProd.setTitle("Reduce next production batch for " + item.getName());
                    rProd.setTitleEn(rProd.getTitle());
                    rProd.setDescription(String.format("Stock is %.1f %s against %.1f %s expected demand with %d-day expiry remaining. Reduce next scheduled production batch by 15-25%% to prevent excess spoilage.",
                            assessment.getStock(), unit, assessment.getExpectedDemand(), unit, Math.max(0, expiryDays)));
                    rProd.setDescriptionEn(rProd.getDescription());
                    rProd.setReasoningDetails("Prolog Rule: assess_waste_risk/6 (High Risk) -> recommend_production/6 (Reduce production by 15-25%)");
                    rProd.setReasoningDetailsEn(rProd.getReasoningDetails());
                    double prodSavings = Math.max(15000.0, surplus * price * 0.70);
                    rProd.setEstimatedSavings(BigDecimal.valueOf(prodSavings).setScale(2, java.math.RoundingMode.HALF_UP));
                    rProd.setStatus(Recommendation.Status.PENDING);
                    saveRecommendation(rProd);
                    generated.add(rProd);

                    // 2. Redistribute excess inventory (if surplus is actionable and safe for donation)
                    if (assessment.isRecommendRedistribution() || (surplus >= 5.0 && expiryDays >= 1)) {
                        Recommendation rRedist = new Recommendation();
                        rRedist.setFoodItemId(item.getId());
                        rRedist.setFoodItemName(item.getName());
                        rRedist.setCategory(Recommendation.Category.REDISTRIBUTION);
                        rRedist.setRiskLevel(Recommendation.RiskLevel.HIGH);
                        rRedist.setTitle("Redistribute excess inventory for " + item.getName());
                        rRedist.setTitleEn(rRedist.getTitle());
                        rRedist.setDescription(String.format("Surplus stock (%.1f %s) detected near expiry. Dispatch to registered food bank or charity partner before expiry cutoff.",
                                surplus > 0 ? surplus : assessment.getStock() * 0.5, unit));
                        rRedist.setDescriptionEn(rRedist.getDescription());
                        rRedist.setReasoningDetails("Prolog Rule: evaluate_redistribution/6 -> Verified surplus eligible for emergency charity donation");
                        rRedist.setReasoningDetailsEn(rRedist.getReasoningDetails());
                        rRedist.setEstimatedSavings(BigDecimal.ZERO);
                        rRedist.setStatus(Recommendation.Status.PENDING);
                        saveRecommendation(rRedist);
                        generated.add(rRedist);
                    }

                    // 3. Prioritize usage today
                    Recommendation rUsage = new Recommendation();
                    rUsage.setFoodItemId(item.getId());
                    rUsage.setFoodItemName(item.getName());
                    rUsage.setCategory(Recommendation.Category.IMPORTANT);
                    rUsage.setRiskLevel(Recommendation.RiskLevel.HIGH);
                    rUsage.setTitle("Prioritize usage today for " + item.getName());
                    rUsage.setTitleEn(rUsage.getTitle());
                    rUsage.setDescription(String.format("Item expires in %d day(s). Prioritize in today's menu specials, meal prep, and kitchen consumption immediately.",
                            Math.max(0, expiryDays)));
                    rUsage.setDescriptionEn(rUsage.getDescription());
                    rUsage.setReasoningDetails("Prolog Rule: evaluate_priority_use/3 -> IMMEDIATE_USE required due to short shelf life");
                    rUsage.setReasoningDetailsEn(rUsage.getReasoningDetails());
                    rUsage.setEstimatedSavings(new BigDecimal("10000.00"));
                    rUsage.setStatus(Recommendation.Status.PENDING);
                    saveRecommendation(rUsage);
                    generated.add(rUsage);
                }

                // =========================================================================
                // 🟡 MEDIUM RISK DIRECTIVES (3 Directives)
                // =========================================================================
                else if ("MEDIUM".equalsIgnoreCase(assessment.getRiskLevel())) {
                    // 1. Monitor stock
                    Recommendation rMon = new Recommendation();
                    rMon.setFoodItemId(item.getId());
                    rMon.setFoodItemName(item.getName());
                    rMon.setCategory(Recommendation.Category.IMPORTANT);
                    rMon.setRiskLevel(Recommendation.RiskLevel.MEDIUM);
                    rMon.setTitle("Monitor stock for " + item.getName());
                    rMon.setTitleEn(rMon.getTitle());
                    rMon.setDescription(String.format("Item expires in %d days. Monitor stock velocity and turnover closely to avoid sudden overstock accumulation.",
                            Math.max(1, expiryDays)));
                    rMon.setDescriptionEn(rMon.getDescription());
                    rMon.setReasoningDetails("Prolog Rule: assess_waste_risk/6 (Medium Risk) -> evaluate_priority_use/3 (HIGH_PRIORITY)");
                    rMon.setReasoningDetailsEn(rMon.getReasoningDetails());
                    rMon.setEstimatedSavings(new BigDecimal("5000.00"));
                    rMon.setStatus(Recommendation.Status.PENDING);
                    saveRecommendation(rMon);
                    generated.add(rMon);

                    // 2. Adjust preparation quantity
                    Recommendation rAdj = new Recommendation();
                    rAdj.setFoodItemId(item.getId());
                    rAdj.setFoodItemName(item.getName());
                    rAdj.setCategory(Recommendation.Category.OPTIMIZATION);
                    rAdj.setRiskLevel(Recommendation.RiskLevel.MEDIUM);
                    rAdj.setTitle("Adjust preparation quantity for " + item.getName());
                    rAdj.setTitleEn(rAdj.getTitle());
                    rAdj.setDescription(String.format("Moderate waste risk detected. Adjust kitchen batch preparation down by 10-15%% according to expected demand (%.1f %s).",
                            assessment.getExpectedDemand(), unit));
                    rAdj.setDescriptionEn(rAdj.getDescription());
                    rAdj.setReasoningDetails("Prolog Rule: recommend_production/6 (Slightly reduce production by 10-15%)");
                    rAdj.setReasoningDetailsEn(rAdj.getReasoningDetails());
                    double adjSavings = Math.max(5000.0, surplus * price * 0.40);
                    rAdj.setEstimatedSavings(BigDecimal.valueOf(adjSavings).setScale(2, java.math.RoundingMode.HALF_UP));
                    rAdj.setStatus(Recommendation.Status.PENDING);
                    saveRecommendation(rAdj);
                    generated.add(rAdj);

                    // 3. Promote usage
                    Recommendation rProm = new Recommendation();
                    rProm.setFoodItemId(item.getId());
                    rProm.setFoodItemName(item.getName());
                    rProm.setCategory(Recommendation.Category.OPTIMIZATION);
                    rProm.setRiskLevel(Recommendation.RiskLevel.MEDIUM);
                    rProm.setTitle("Promote usage for " + item.getName());
                    rProm.setTitleEn(rProm.getTitle());
                    rProm.setDescription(String.format("Feature in chef's daily side dish, combo promotions, or lunch specials to accelerate inventory drawdown within %d days.",
                            Math.max(1, expiryDays)));
                    rProm.setDescriptionEn(rProm.getDescription());
                    rProm.setReasoningDetails("Prolog Rule: evaluate_priority_use/3 -> HIGH_PRIORITY usage to clear inventory within 3 days");
                    rProm.setReasoningDetailsEn(rProm.getReasoningDetails());
                    rProm.setEstimatedSavings(new BigDecimal("7500.00"));
                    rProm.setStatus(Recommendation.Status.PENDING);
                    saveRecommendation(rProm);
                    generated.add(rProm);
                }

                // =========================================================================
                // 🟢 LOW RISK DIRECTIVES (1 Directive)
                // =========================================================================
                else {
                    // 1. Maintain normal operation
                    Recommendation rNorm = new Recommendation();
                    rNorm.setFoodItemId(item.getId());
                    rNorm.setFoodItemName(item.getName());
                    rNorm.setCategory(Recommendation.Category.OPTIMIZATION);
                    rNorm.setRiskLevel(Recommendation.RiskLevel.LOW);
                    rNorm.setTitle("Maintain normal operation for " + item.getName());
                    rNorm.setTitleEn(rNorm.getTitle());
                    rNorm.setDescription(String.format("Safe shelf-life remaining (%d days) and balanced stock levels. Maintain standard scheduled production batch and regular replenishment cycle.",
                            Math.max(1, expiryDays)));
                    rNorm.setDescriptionEn(rNorm.getDescription());
                    rNorm.setReasoningDetails("Prolog Rule: assess_waste_risk/6 (Low Risk) -> recommend_production/6 (Maintain standard scheduled batch)");
                    rNorm.setReasoningDetailsEn(rNorm.getReasoningDetails());
                    rNorm.setEstimatedSavings(BigDecimal.ZERO);
                    rNorm.setStatus(Recommendation.Status.PENDING);
                    saveRecommendation(rNorm);
                    generated.add(rNorm);
                }
            }
        }

        return generated;
    }

    private void saveRecommendation(Recommendation r) throws SQLException {
        TranslationService translator = TranslationService.getInstance();
        if (r.getTitleEn() == null && r.getTitle() != null) {
            r.setTitleEn(r.getTitle());
        }
        if (r.getTitleMy() == null && r.getTitleEn() != null) {
            r.setTitleMy(translator.translateToMyanmar(r.getTitleEn()));
        }

        if (r.getDescriptionEn() == null && r.getDescription() != null) {
            r.setDescriptionEn(r.getDescription());
        }
        if (r.getDescriptionMy() == null && r.getDescriptionEn() != null) {
            r.setDescriptionMy(translator.translateToMyanmar(r.getDescriptionEn()));
        }

        if (r.getReasoningDetailsEn() == null && r.getReasoningDetails() != null) {
            r.setReasoningDetailsEn(r.getReasoningDetails());
        }
        if (r.getReasoningDetailsMy() == null && r.getReasoningDetailsEn() != null) {
            r.setReasoningDetailsMy(translator.translateToMyanmar(r.getReasoningDetailsEn()));
        }

        if (DatabaseConfig.isAvailable()) {
            recommendationDao.save(r);
        } else {
            long newId = recIdGen.incrementAndGet();
            r.setId(newId);
            r.setCreatedAt(LocalDateTime.now());
            memoryRecs.put(newId, r);
        }
    }
}
