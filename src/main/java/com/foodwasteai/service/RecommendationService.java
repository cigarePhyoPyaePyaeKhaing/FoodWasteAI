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
     */
    public List<Recommendation> generateRecommendationsFromProlog() throws SQLException {
        List<FoodItem> items = foodItemService.getAllFoodItems();
        List<Recommendation> generated = new ArrayList<>();

        for (FoodItem item : items) {
            Optional<PrologAssessment> opt = predictionService.assessFoodItemById(item.getId());
            if (opt.isPresent()) {
                PrologAssessment assessment = opt.get();
                
                // 1. Urgent Production Reduction if High Risk
                if ("HIGH".equalsIgnoreCase(assessment.getRiskLevel())) {
                    Recommendation r = new Recommendation();
                    r.setFoodItemId(item.getId());
                    r.setFoodItemName(item.getName());
                    r.setCategory(Recommendation.Category.URGENT);
                    r.setRiskLevel(Recommendation.RiskLevel.HIGH);
                    r.setTitle("Reduce Tomorrow's Production of " + item.getName() + " by 15-25%");
                    r.setDescription(String.format("Stock is %.1f %s against %.1f expected demand. %s",
                            assessment.getStock(), item.getUnit(), assessment.getExpectedDemand(), assessment.getRecommendation()));
                    r.setReasoningDetails("Prolog Rule: " + String.join(" | ", assessment.getReasons()));
                    
                    double surplus = Math.max(0, assessment.getStock() - assessment.getExpectedDemand());
                    double savings = surplus * (item.getPricePerUnit() != null ? item.getPricePerUnit().doubleValue() : 5000) * 0.70;
                    r.setEstimatedSavings(BigDecimal.valueOf(savings).setScale(2, java.math.RoundingMode.HALF_UP));
                    r.setStatus(Recommendation.Status.PENDING);
                    saveRecommendation(r);
                    generated.add(r);
                }

                // 2. Important Priority Use if Near Expiry
                if (assessment.getExpiryDays() <= 2) {
                    Recommendation r = new Recommendation();
                    r.setFoodItemId(item.getId());
                    r.setFoodItemName(item.getName());
                    r.setCategory(Recommendation.Category.IMPORTANT);
                    r.setRiskLevel(Recommendation.RiskLevel.HIGH);
                    r.setTitle("Prioritize " + item.getName() + " in Specials & Expedited Prep");
                    r.setDescription(String.format("Expires in %d day(s). Expedite preparation and offer promotional combo specials.", assessment.getExpiryDays()));
                    r.setReasoningDetails("Prolog Priority: " + assessment.getPriorityUsage());
                    r.setEstimatedSavings(new BigDecimal("10000.00"));
                    r.setStatus(Recommendation.Status.PENDING);
                    saveRecommendation(r);
                    generated.add(r);
                }

                // 3. Redistribution if surplus is eligible
                if (assessment.isRecommendRedistribution()) {
                    Recommendation r = new Recommendation();
                    r.setFoodItemId(item.getId());
                    r.setFoodItemName(item.getName());
                    r.setCategory(Recommendation.Category.REDISTRIBUTION);
                    r.setRiskLevel(Recommendation.RiskLevel.MEDIUM);
                    r.setTitle("Schedule Surplus Dispatch of " + item.getName() + " to Food Bank");
                    double surplus = Math.max(0, assessment.getStock() - assessment.getExpectedDemand());
                    r.setDescription(String.format("Verified donation eligible (%.1f %s surplus). Prevent landfill disposal.", surplus, item.getUnit()));
                    r.setReasoningDetails("Prolog Rule: evaluate_redistribution/6 -> Eligible for charity rescue");
                    r.setEstimatedSavings(BigDecimal.ZERO);
                    r.setStatus(Recommendation.Status.PENDING);
                    saveRecommendation(r);
                    generated.add(r);
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
