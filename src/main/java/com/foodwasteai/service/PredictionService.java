package com.foodwasteai.service;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.PredictionDao;
import com.foodwasteai.dao.SalesDao;
import com.foodwasteai.dao.WasteRecordDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.Prediction;
import com.foodwasteai.model.PredictionItem;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.prolog.PrologService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service orchestrating AI waste prediction and SWI-Prolog expert system rule evaluation.
 * Architecture: Controller -> Service -> PrologService -> SWI-Prolog -> Result -> Java -> JSON
 */
public class PredictionService {
    private static final Logger logger = LoggerFactory.getLogger(PredictionService.class);
    private final PrologService prologService;
    private final FoodItemService foodItemService;
    private final SalesDao salesDao;
    private final WasteRecordDao wasteDao;
    private final PredictionDao predictionDao;

    public PredictionService() {
        this.prologService = new PrologService();
        this.foodItemService = new FoodItemService();
        this.salesDao = new SalesDao();
        this.wasteDao = new WasteRecordDao();
        this.predictionDao = new PredictionDao();
    }

    public PredictionService(PrologService prologService, FoodItemService foodItemService,
                             SalesDao salesDao, WasteRecordDao wasteDao, PredictionDao predictionDao) {
        this.prologService = prologService;
        this.foodItemService = foodItemService;
        this.salesDao = salesDao;
        this.wasteDao = wasteDao;
        this.predictionDao = predictionDao;
    }

    /**
     * Assesses a food item by passing raw metrics to Prolog.
     */
    public PrologAssessment assessFoodItem(String foodName, double stock, double expectedDemand,
                                          int expiryDays, double histWasteRate, double currentProduction) {
        logger.info("Evaluating item '{}' via PrologService (stock={}, demand={}, expiryDays={}, wasteRate={})",
                foodName, stock, expectedDemand, expiryDays, histWasteRate);
        return prologService.assessFoodItem(foodName, stock, expectedDemand, expiryDays, histWasteRate, currentProduction);
    }

    /**
     * Assesses a specific food item by its ID, pulling real data from the database/inventory.
     */
    public Optional<PrologAssessment> assessFoodItemById(Long foodItemId) throws SQLException {
        Optional<FoodItem> itemOpt = foodItemService.getFoodItemById(foodItemId);
        if (itemOpt.isEmpty()) {
            return Optional.empty();
        }

        FoodItem item = itemOpt.get();
        double stock = item.getQuantity() != null ? item.getQuantity().doubleValue() : 0.0;
        
        // Calculate days to expiry
        int expiryDays = (int) ChronoUnit.DAYS.between(LocalDate.now(), item.getExpiryDate());
        if (expiryDays < 0) expiryDays = 0;

        // Fetch real historical sales demand
        double expectedDemand;
        try {
            BigDecimal avgSales = salesDao.getHistoricalAverageDailySales(foodItemId, 7);
            if (avgSales != null && avgSales.compareTo(BigDecimal.ZERO) > 0) {
                expectedDemand = avgSales.doubleValue();
            } else {
                // Fallback estimated demand: 85% of current stock or min threshold
                expectedDemand = Math.max(5.0, stock * 0.85);
            }
        } catch (Exception e) {
            expectedDemand = Math.max(5.0, stock * 0.85);
        }

        // Fetch real historical waste rate
        double histWasteRate;
        try {
            BigDecimal rate = wasteDao.calculateHistoricalWasteRate(foodItemId, 14);
            if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                histWasteRate = rate.doubleValue();
            } else {
                // Category-specific empirical baseline
                histWasteRate = getCategoryDefaultWasteRate(item.getCategory(), expiryDays);
            }
        } catch (Exception e) {
            histWasteRate = getCategoryDefaultWasteRate(item.getCategory(), expiryDays);
        }

        double currentProduction = expectedDemand * 1.1; // Default planned production

        PrologAssessment assessment = prologService.assessFoodItem(
                item.getName(), stock, expectedDemand, expiryDays, histWasteRate, currentProduction
        );
        assessment.setFoodItemId(item.getId());

        return Optional.of(assessment);
    }

    /**
     * Evaluates all items in the inventory and returns a comprehensive batch AI prediction report.
     */
    public Map<String, Object> assessAllInventory() throws SQLException {
        List<FoodItem> items = foodItemService.getAllFoodItems();
        List<PrologAssessment> assessments = new ArrayList<>();

        double totalRiskScore = 0.0;
        double expectedTotalWasteKg = 0.0;
        double estimatedMoneyLost = 0.0;
        double potentialSavings = 0.0;
        int highRiskCount = 0;

        for (FoodItem item : items) {
            Optional<PrologAssessment> opt = assessFoodItemById(item.getId());
            if (opt.isPresent()) {
                PrologAssessment a = opt.get();
                assessments.add(a);
                totalRiskScore += a.getRiskPercentage();

                if ("HIGH".equalsIgnoreCase(a.getRiskLevel())) {
                    highRiskCount++;
                    double projectedWaste = Math.max(0, a.getStock() - a.getExpectedDemand());
                    expectedTotalWasteKg += projectedWaste;
                    double loss = projectedWaste * (item.getPricePerUnit() != null ? item.getPricePerUnit().doubleValue() : 5000);
                    estimatedMoneyLost += loss;
                    potentialSavings += loss * 0.70; // 70% preventable via AI actions
                } else if ("MEDIUM".equalsIgnoreCase(a.getRiskLevel())) {
                    double projectedWaste = Math.max(0, (a.getStock() - a.getExpectedDemand()) * 0.30);
                    expectedTotalWasteKg += projectedWaste;
                    double loss = projectedWaste * (item.getPricePerUnit() != null ? item.getPricePerUnit().doubleValue() : 5000);
                    estimatedMoneyLost += loss;
                    potentialSavings += loss * 0.50;
                }
            }
        }

        double avgRisk = items.isEmpty() ? 0.0 : (totalRiskScore / items.size());

        // Persist to MySQL predictions and prediction_items tables
        if (DatabaseConfig.isAvailable() && !items.isEmpty()) {
            try {
                Prediction pred = new Prediction();
                pred.setPredictionDate(LocalDate.now().plusDays(1));
                pred.setOverallRiskScore(BigDecimal.valueOf(avgRisk).setScale(2, RoundingMode.HALF_UP));
                pred.setExpectedTotalWasteKg(BigDecimal.valueOf(expectedTotalWasteKg).setScale(2, RoundingMode.HALF_UP));
                pred.setEstimatedMoneyLost(BigDecimal.valueOf(estimatedMoneyLost).setScale(2, RoundingMode.HALF_UP));
                pred.setPotentialSavings(BigDecimal.valueOf(potentialSavings).setScale(2, RoundingMode.HALF_UP));
                pred.setStatus(Prediction.Status.GENERATED);
                Prediction savedPred = predictionDao.savePrediction(pred);

                List<PredictionItem> pItems = new ArrayList<>();
                for (PrologAssessment a : assessments) {
                    PredictionItem pi = new PredictionItem();
                    pi.setPredictionId(savedPred.getId());
                    pi.setFoodItemId(a.getFoodItemId());
                    pi.setCurrentStock(BigDecimal.valueOf(a.getStock()).setScale(2, RoundingMode.HALF_UP));
                    pi.setExpectedDemand(BigDecimal.valueOf(a.getExpectedDemand()).setScale(2, RoundingMode.HALF_UP));
                    pi.setExpiryDays(a.getExpiryDays());
                    pi.setHistoricalWasteRate(BigDecimal.valueOf(a.getHistoricalWasteRate()).setScale(4, RoundingMode.HALF_UP));
                    pi.setRiskLevel(PredictionItem.RiskLevel.valueOf(a.getRiskLevel()));
                    pi.setRiskPercentage(BigDecimal.valueOf(a.getRiskPercentage()).setScale(2, RoundingMode.HALF_UP));
                    pi.setPredictedWasteQty(BigDecimal.valueOf(Math.max(0, a.getStock() - a.getExpectedDemand())).setScale(2, RoundingMode.HALF_UP));
                    pi.setRecommendedProduction(BigDecimal.valueOf(a.getRecommendedProduction()).setScale(2, RoundingMode.HALF_UP));
                    pi.setPriorityUsage(a.getPriorityUsage());
                    pi.setReasoningText(a.getReasons() != null ? String.join(" | ", a.getReasons()) : "Prolog risk reasoning");
                    pItems.add(pi);
                }
                predictionDao.savePredictionItems(savedPred.getId(), pItems);
            } catch (Exception e) {
                logger.warn("Could not persist predictions to MySQL: {}", e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("predictionDate", LocalDate.now().plusDays(1).toString());
        result.put("overallRiskScore", BigDecimal.valueOf(avgRisk).setScale(1, RoundingMode.HALF_UP));
        result.put("highRiskItemCount", highRiskCount);
        result.put("totalItemsEvaluated", items.size());
        result.put("expectedTotalWasteKg", BigDecimal.valueOf(expectedTotalWasteKg).setScale(2, RoundingMode.HALF_UP));
        result.put("estimatedMoneyLost", BigDecimal.valueOf(estimatedMoneyLost).setScale(2, RoundingMode.HALF_UP));
        result.put("potentialSavings", BigDecimal.valueOf(potentialSavings).setScale(2, RoundingMode.HALF_UP));
        result.put("items", assessments);
        result.put("engine", PrologService.isPrologAvailable() ? "SWI-Prolog Expert Engine" : "SWI-Prolog Rules Knowledge Base");

        return result;
    }

    public PredictionDao getPredictionDao() {
        return predictionDao;
    }

    public List<PredictionItem> getLatestPredictionItems() throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            Optional<Prediction> latest = predictionDao.findLatestPrediction();
            if (latest.isPresent()) {
                return predictionDao.findItemsByPredictionId(latest.get().getId());
            }
        }
        return Collections.emptyList();
    }

    private double getCategoryDefaultWasteRate(String category, int expiryDays) {
        if (expiryDays > 14) {
            return 0.02; // Long shelf-life / frozen / stable storage baseline
        }
        if (category == null) return 0.05;
        switch (category.toLowerCase()) {
            case "poultry":
            case "meat":
                return 0.22;
            case "produce":
            case "salad":
                return 0.18;
            case "seafood":
                return 0.12;
            case "bakery":
                return 0.15;
            case "dairy":
                return 0.08;
            case "grains":
            default:
                return 0.02;
        }
    }
}
