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
    private static final AtomicLong recIdGen = new AtomicLong(10);

    static {
        initFallbackRecommendations();
    }

    private static void initFallbackRecommendations() {
        Recommendation r1 = new Recommendation();
        r1.setId(1L);
        r1.setFoodItemId(1L);
        r1.setFoodItemName("Fresh Chicken Breast");
        r1.setCategory(Recommendation.Category.URGENT);
        r1.setRiskLevel(Recommendation.RiskLevel.HIGH);
        r1.setTitle("Reduce Tomorrow's Chicken Production by 15-25%");
        r1.setDescription("Current stock is 50.0 kg against 30.0 kg expected demand with 1-day expiry. Reducing scheduled morning prep exhausts stock safely.");
        r1.setReasoningDetails("Prolog Rule: assess_waste_risk(50, 30, 1, 0.22, high) -> recommend_production(50, 30, 33, high, 25, 'Reduce tomorrow production')");
        r1.setEstimatedSavings(new BigDecimal("25000.00"));
        r1.setStatus(Recommendation.Status.PENDING);
        r1.setCreatedAt(LocalDateTime.now().minusHours(2));

        Recommendation r2 = new Recommendation();
        r2.setId(2L);
        r2.setFoodItemId(2L);
        r2.setFoodItemName("Organic Garden Salad Mix");
        r2.setCategory(Recommendation.Category.IMPORTANT);
        r2.setRiskLevel(Recommendation.RiskLevel.HIGH);
        r2.setTitle("Prioritize Salad in Tomorrow's Lunch Menu Specials");
        r2.setDescription("18.5 kg fresh salad mix expires in 2 days. Feature salad combo specials during lunch rush to accelerate kitchen turnover.");
        r2.setReasoningDetails("Prolog Rule: evaluate_priority_use(2, high, IMMEDIATE_USE)");
        r2.setEstimatedSavings(new BigDecimal("10000.00"));
        r2.setStatus(Recommendation.Status.PENDING);
        r2.setCreatedAt(LocalDateTime.now().minusHours(3));

        Recommendation r3 = new Recommendation();
        r3.setId(3L);
        r3.setFoodItemId(1L);
        r3.setFoodItemName("Fresh Chicken Breast");
        r3.setCategory(Recommendation.Category.REDISTRIBUTION);
        r3.setRiskLevel(Recommendation.RiskLevel.MEDIUM);
        r3.setTitle("Dispatch Surplus Chicken to Hope Food Bank");
        r3.setDescription("If 10+ kg portion remains by 16:00, dispatch verified charity donation batch to Hope Community Food Bank before expiry cutoff.");
        r3.setReasoningDetails("Prolog Rule: evaluate_redistribution(50, 30, 1, 20, true, Reason)");
        r3.setEstimatedSavings(new BigDecimal("0.00"));
        r3.setStatus(Recommendation.Status.PENDING);
        r3.setCreatedAt(LocalDateTime.now().minusHours(4));

        Recommendation r4 = new Recommendation();
        r4.setId(4L);
        r4.setFoodItemId(4L);
        r4.setFoodItemName("Premium Jasmine Rice");
        r4.setCategory(Recommendation.Category.OPTIMIZATION);
        r4.setRiskLevel(Recommendation.RiskLevel.LOW);
        r4.setTitle("Maintain Standard Production Batches for Jasmine Rice");
        r4.setDescription("Safe shelf-life (> 60 days) and regular consumption. Reorder point is optimally calibrated.");
        r4.setReasoningDetails("Prolog Rule: recommend_production(120, 72, 80, low, 80, 'Maintain standard scheduled batch')");
        r4.setEstimatedSavings(new BigDecimal("5000.00"));
        r4.setStatus(Recommendation.Status.PENDING);
        r4.setCreatedAt(LocalDateTime.now().minusHours(5));

        memoryRecs.put(1L, r1);
        memoryRecs.put(2L, r2);
        memoryRecs.put(3L, r3);
        memoryRecs.put(4L, r4);
    }

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

                // =========================================================================
                // 🔴 HIGH RISK DIRECTIVES (3 Directives)
                // =========================================================================
                if ("HIGH".equalsIgnoreCase(assessment.getRiskLevel())) {
                    // 1. Reduce next production batch
                    Recommendation rProd = new Recommendation();
                    rProd.setFoodItemId(item.getId());
                    rProd.setFoodItemName(item.getName());
                    rProd.setCategory(Recommendation.Category.URGENT);
                    rProd.setRiskLevel(Recommendation.RiskLevel.HIGH);
                    rProd.setTitle("Reduce next production batch for " + item.getName());
                    rProd.setDescription(String.format("Stock is %.1f %s against %.1f %s expected demand with %d-day expiry remaining. Reduce next scheduled production batch by 15-25%% to prevent excess spoilage.",
                            assessment.getStock(), unit, assessment.getExpectedDemand(), unit, assessment.getExpiryDays()));
                    rProd.setReasoningDetails("Prolog Rule: assess_waste_risk/6 (High Risk) -> recommend_production/6 (Reduce production by 15-25%)");
                    double prodSavings = Math.max(15000.0, surplus * price * 0.70);
                    rProd.setEstimatedSavings(BigDecimal.valueOf(prodSavings).setScale(2, java.math.RoundingMode.HALF_UP));
                    rProd.setStatus(Recommendation.Status.PENDING);
                    saveRecommendation(rProd);
                    generated.add(rProd);

                    // 2. Redistribute excess inventory
                    Recommendation rRedist = new Recommendation();
                    rRedist.setFoodItemId(item.getId());
                    rRedist.setFoodItemName(item.getName());
                    rRedist.setCategory(Recommendation.Category.REDISTRIBUTION);
                    rRedist.setRiskLevel(Recommendation.RiskLevel.HIGH);
                    rRedist.setTitle("Redistribute excess inventory for " + item.getName());
                    rRedist.setDescription(String.format("Surplus stock (%.1f %s) detected near expiry. Dispatch to registered food bank or charity partner before expiry cutoff.",
                            surplus > 0 ? surplus : assessment.getStock() * 0.5, unit));
                    rRedist.setReasoningDetails("Prolog Rule: evaluate_redistribution/6 -> Verified surplus eligible for emergency charity donation");
                    rRedist.setEstimatedSavings(BigDecimal.ZERO);
                    rRedist.setStatus(Recommendation.Status.PENDING);
                    saveRecommendation(rRedist);
                    generated.add(rRedist);

                    // 3. Prioritize usage today
                    Recommendation rUsage = new Recommendation();
                    rUsage.setFoodItemId(item.getId());
                    rUsage.setFoodItemName(item.getName());
                    rUsage.setCategory(Recommendation.Category.IMPORTANT);
                    rUsage.setRiskLevel(Recommendation.RiskLevel.HIGH);
                    rUsage.setTitle("Prioritize usage today for " + item.getName());
                    rUsage.setDescription(String.format("Item expires in %d day(s). Prioritize in today's menu specials, meal prep, and kitchen consumption immediately.",
                            assessment.getExpiryDays()));
                    rUsage.setReasoningDetails("Prolog Rule: evaluate_priority_use/3 -> IMMEDIATE_USE required due to 1-day shelf life");
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
                    rMon.setDescription(String.format("Item expires in %d days. Monitor stock velocity and turnover closely to avoid sudden overstock accumulation.",
                            assessment.getExpiryDays()));
                    rMon.setReasoningDetails("Prolog Rule: assess_waste_risk/6 (Medium Risk) -> evaluate_priority_use/3 (HIGH_PRIORITY)");
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
                    rAdj.setDescription(String.format("Moderate waste risk detected. Adjust kitchen batch preparation down by 10-15%% according to expected demand (%.1f %s).",
                            assessment.getExpectedDemand(), unit));
                    rAdj.setReasoningDetails("Prolog Rule: recommend_production/6 (Slightly reduce production by 10-15%)");
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
                    rProm.setDescription(String.format("Feature in chef's daily side dish, combo promotions, or lunch specials to accelerate inventory drawdown within %d days.",
                            assessment.getExpiryDays()));
                    rProm.setReasoningDetails("Prolog Rule: evaluate_priority_use/3 -> HIGH_PRIORITY usage to clear inventory within 3 days");
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
                    rNorm.setDescription(String.format("Safe shelf-life remaining (%d days) and balanced stock levels. Maintain standard scheduled production batch and regular replenishment cycle.",
                            assessment.getExpiryDays()));
                    rNorm.setReasoningDetails("Prolog Rule: assess_waste_risk/6 (Low Risk) -> recommend_production/6 (Maintain standard scheduled batch)");
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
