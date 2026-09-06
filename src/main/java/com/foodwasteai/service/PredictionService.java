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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service orchestrating AI waste prediction and SWI-Prolog expert system rule evaluation.
 * Architecture: Controller -> Service -> PrologService -> SWI-Prolog -> Result -> Java -> JSON
 * Preserves food item units and guarantees authoritative risk-score synchronization across all layers.
 */
public class PredictionService {
    private static final Logger logger = LoggerFactory.getLogger(PredictionService.class);
    private final PrologService prologService;
    private final FoodItemService foodItemService;
    private final SalesDao salesDao;
    private final WasteRecordDao wasteDao;
    private final PredictionDao predictionDao;
    private final WasteService wasteService;
    private java.time.Clock clock = java.time.Clock.system(com.foodwasteai.util.ExpiryStatusResolver.ZONE_YANGON);

    public PredictionService() {
        this.prologService = new PrologService();
        this.foodItemService = new FoodItemService();
        this.salesDao = new SalesDao();
        this.wasteDao = new WasteRecordDao();
        this.predictionDao = new PredictionDao();
        this.wasteService = new WasteService(this.wasteDao, this.foodItemService);
    }

    public PredictionService(PrologService prologService, FoodItemService foodItemService,
                             SalesDao salesDao, WasteRecordDao wasteDao, PredictionDao predictionDao) {
        this.prologService = prologService;
        this.foodItemService = foodItemService;
        this.salesDao = salesDao;
        this.wasteDao = wasteDao;
        this.predictionDao = predictionDao;
        this.wasteService = new WasteService(wasteDao, foodItemService);
    }

    public PredictionService(PrologService prologService, FoodItemService foodItemService,
                             SalesDao salesDao, WasteRecordDao wasteDao, PredictionDao predictionDao,
                             WasteService wasteService) {
        this.prologService = prologService;
        this.foodItemService = foodItemService;
        this.salesDao = salesDao;
        this.wasteDao = wasteDao;
        this.predictionDao = predictionDao;
        this.wasteService = wasteService != null ? wasteService : new WasteService(wasteDao, foodItemService);
    }

    public PredictionService(PrologService prologService, FoodItemService foodItemService,
                             SalesDao salesDao, WasteRecordDao wasteDao, PredictionDao predictionDao,
                             WasteService wasteService, java.time.Clock clock) {
        this(prologService, foodItemService, salesDao, wasteDao, predictionDao, wasteService);
        this.clock = Objects.requireNonNull(clock).withZone(com.foodwasteai.util.ExpiryStatusResolver.ZONE_YANGON);
    }
    /**
     * Assesses a food item by passing raw metrics to Prolog.
     */
    public PrologAssessment assessFoodItem(String foodName, double stock, double expectedDemand,
                                          int expiryDays, double histWasteRate, double currentProduction) {
        return assessFoodItem(foodName, "kg", stock, expectedDemand, expiryDays, histWasteRate, currentProduction);
    }

    /**
     * Assesses a food item with specified unit.
     */
    public PrologAssessment assessFoodItem(String foodName, String unit, double stock, double expectedDemand,
                                          int expiryDays, double histWasteRate, double currentProduction) {
        logger.info("Evaluating item '{}' ({}) via PrologService (stock={}, demand={}, expiryDays={}, wasteRate={})",
                foodName, unit, stock, expectedDemand, expiryDays, histWasteRate);
        return prologService.assessFoodItem(foodName, unit, stock, expectedDemand, expiryDays, histWasteRate, currentProduction);
    }

    /**
     * Assesses a specific food item by its ID, pulling real data from the database/inventory.
     */
    public Optional<PrologAssessment> assessFoodItemById(Long foodItemId) throws SQLException {
        if (foodItemId == null) return Optional.empty();
        Optional<FoodItem> itemOpt = foodItemService.getFoodItemById(foodItemId);
        if (itemOpt.isEmpty()) {
            return Optional.empty();
        }
        return assessFoodItem(itemOpt.get());
    }

    /**
     * Single Source of Truth for daily demand calculation across all services.
     * Uses real historical daily sales if available, or a consistent canonical baseline (85% of stock).
     */
    public double calculateExpectedDailyDemand(FoodItem item) {
        if (item == null) return 0.0;
        double stock = item.getQuantity() != null ? Math.max(0.0, item.getQuantity().doubleValue()) : 0.0;
        return calculateExpectedDailyDemand(item.getId(), stock);
    }

    public double calculateExpectedDailyDemand(Long itemId, double stock) {
        if (stock <= 0.0) return 0.0;
        if (itemId != null) {
            try {
                BigDecimal avgSales = salesDao.getHistoricalAverageDailySales(itemId, 7);
                if (avgSales != null && avgSales.compareTo(BigDecimal.ZERO) > 0) {
                    return avgSales.doubleValue();
                }
            } catch (Exception ignored) {}
        }
        return Math.max(1.0, stock * 0.85);
    }

    /**
     * Assesses a specific food item instance directly without requiring a DB reload.
     */
    public Optional<PrologAssessment> assessFoodItem(FoodItem item) {
        if (item == null) return Optional.empty();
        double stock = item.getQuantity() != null ? Math.max(0.0, item.getQuantity().doubleValue()) : 0.0;
        String unit = item.getUnit() != null && !item.getUnit().trim().isEmpty() ? item.getUnit().trim() : "kg";
        int expiryDays = com.foodwasteai.util.ExpiryStatusResolver.calculateDaysRemaining(item.getExpiryDate(), LocalDate.now(clock));

        double expectedDemand = calculateExpectedDailyDemand(item);

        double histWasteRate = stock > 0 ? calculateConfirmedHistoricalWasteRate(item.getId()) : 0.0;

        double currentProduction = expectedDemand * 1.1;

        PrologAssessment assessment = prologService.assessFoodItem(
                item.getName(), unit, stock, expectedDemand, expiryDays, histWasteRate, currentProduction
        );
        assessment.setFoodItemId(item.getId());
        assessment.setCategory(item.getCategory());
        assessment.setUnit(unit);
        assessment.setExpiryDate(item.getExpiryDate());
        int curDays = com.foodwasteai.util.ExpiryStatusResolver.calculateDaysRemaining(item.getExpiryDate(), LocalDate.now(clock));
        assessment.setCurrentDaysRemaining(curDays);
        assessment.setExpiryDaysRemaining(curDays);

        double surplus = Math.max(0.0, stock - expectedDemand);
        assessment.setExpectedDemand(expectedDemand);
        assessment.setProjectedSurplus(surplus);
        assessment.setSuggestedDonationQuantity(surplus);

        String reasoning = String.join(" | ", assessment.getReasons());
        assessment.setReasonEn(reasoning);
        assessment.setReasonMy(TranslationService.getInstance().translateToMyanmar(reasoning));
        assessment.setReason(reasoning);
        assessment.setReasoning(reasoning);

        return Optional.of(assessment);
    }

    /**
     * Evaluates a provided inventory list and returns a comprehensive 7-Day AI Waste Forecast.
     * Grounded in current inventory, expiry progression, projected stock/demand, and SWI-Prolog reasoning.
     */
    public Map<String, Object> assessInventory(List<FoodItem> items) throws SQLException {
        if (items == null) items = Collections.emptyList();

        LocalDate today = LocalDate.now(clock);
        LocalDate forecastStartDate = today.plusDays(1);
        LocalDate forecastEndDate = today.plusDays(7);

        // Exclude zero-stock items and items already reaching end of usable life today/past (handled as actual waste)
        List<FoodItem> activeItems = items.stream()
                .filter(Objects::nonNull)
                .filter(i -> i.getQuantity() != null && i.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .filter(i -> i.getExpiryDate() != null && i.getExpiryDate().isAfter(today))
                .toList();

        // 7-Day Forecast Evaluation Engine
        List<Map<String, Object>> forecastDays = new ArrayList<>();
        Map<String, Double> weeklyUnitBreakdown = new LinkedHashMap<>();
        double weeklyEstimatedLoss = 0.0;
        double weeklyPotentialSavings = 0.0;
        double weeklyTotalKg = 0.0;
        double totalRiskSum = 0.0;
        int activeRiskDayCount = 0;
        String highestRiskDay = forecastStartDate.toString();
        double highestRiskScore = -1.0;
        List<PrologAssessment> allAssessments = new ArrayList<>();

        TranslationService translator = TranslationService.getInstance();

        Map<FoodItem, Double> closingStocks = new IdentityHashMap<>();
        for (int dayIndex = 1; dayIndex <= 7; dayIndex++) {
            LocalDate forecastDate = today.plusDays(dayIndex);
            String dateStr = forecastDate.toString();
            String dayName = forecastDate.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.US);
            String dayFormatted = forecastDate.format(DateTimeFormatter.ofPattern("MMM d", Locale.US));

            List<PrologAssessment> dayAssessments = new ArrayList<>();
            Map<String, Double> dayUnitBreakdown = new LinkedHashMap<>();
            double dayLoss = 0.0;
            double daySavings = 0.0;
            double dayRiskSum = 0.0;
            int dayHighRiskCount = 0;

            for (FoodItem item : activeItems) {
                double initialStock = item.getQuantity() != null ? Math.max(0.0, item.getQuantity().doubleValue()) : 0.0;
                String unit = item.getUnit() != null && !item.getUnit().trim().isEmpty() ? item.getUnit().trim() : "kg";
                double pricePerUnit = item.getPricePerUnit() != null ? item.getPricePerUnit().doubleValue() : 0.0;

                double dailyDemand = calculateExpectedDailyDemand(item);

                int expiryDays = (int) ChronoUnit.DAYS.between(forecastDate, item.getExpiryDate());

                // Projected stock progression across the 7-day horizon:
                // Prior days consume projected daily demand; expired food is discarded
                double projectedStock = closingStocks.getOrDefault(item, initialStock);
                if (item.getExpiryDate().isBefore(forecastDate)) {
                    projectedStock = 0.0; // Already reached expiration and disposed on earlier date
                }

                if (projectedStock <= 0.0) {
                    // Depleted stock snapshot -> no active waste advice or risk
                    continue;
                }

                double histWasteRate = calculateConfirmedHistoricalWasteRate(item.getId());

                double currentProduction = dailyDemand * 1.1;

                PrologAssessment a;
                if (expiryDays <= 0) {
                    // Item reaches expiration on this forecast day
                    a = prologService.assessFoodItem(item.getName(), unit, projectedStock, dailyDemand, 0, histWasteRate, currentProduction);
                } else {
                    a = prologService.assessFoodItem(item.getName(), unit, projectedStock, dailyDemand, expiryDays, histWasteRate, currentProduction);
                }

                // No replenishment is modeled. Sales and waste cannot consume the same stock.
                double predictedSales = Math.min(projectedStock, Math.max(0, dailyDemand));
                double predictedWaste = expiryDays <= 0 ? Math.max(0, projectedStock-predictedSales) : Math.min(a.getPredictedWasteQuantity(), Math.max(0, projectedStock-predictedSales));
                double closingStock = Math.max(0, projectedStock-predictedSales-predictedWaste);
                closingStocks.put(item, closingStock);
                a.setProjectedOpeningStock(projectedStock);
                a.setPredictedSalesQuantity(predictedSales);
                a.setPredictedWasteQuantity(predictedWaste);
                a.setProjectedClosingStock(closingStock);

                a.setFoodItemId(item.getId());
                a.setCategory(item.getCategory());
                a.setUnit(unit);
                a.setExpiryDate(item.getExpiryDate());
                int curDays = com.foodwasteai.util.ExpiryStatusResolver.calculateDaysRemaining(item.getExpiryDate(), today);
                a.setCurrentDaysRemaining(curDays);
                a.setExpiryDaysRemaining(curDays);
                a.setStock(projectedStock);
                a.setRemainingQuantity(projectedStock);
                double daySurplus = Math.max(0.0, projectedStock - dailyDemand);
                a.setExpectedDemand(dailyDemand);
                a.setProjectedSurplus(daySurplus);
                a.setSuggestedDonationQuantity(daySurplus);

                // Populate bilingual reasoning and directives
                String reasoningEn = (a.getReasons() != null && !a.getReasons().isEmpty())
                        ? String.join(" | ", a.getReasons())
                        : (a.getReason() != null ? a.getReason() : "Prolog risk reasoning");
                String reasoningMy = translator.translateToMyanmar(reasoningEn);
                a.setReasonEn(reasoningEn);
                a.setReasonMy(reasoningMy);
                a.setReason(reasoningEn);
                a.setReasoning(reasoningEn);
                if (a.getRecommendation() != null) {
                    a.setRecommendationEn(a.getRecommendation());
                    a.setRecommendationMy(translator.translateToMyanmar(a.getRecommendation()));
                }
                if (a.getReasons() != null) {
                    List<String> rMy = new ArrayList<>();
                    for (String r : a.getReasons()) {
                        rMy.add(translator.translateToMyanmar(r));
                    }
                    a.setReasonsMy(rMy);
                }

                dayAssessments.add(a);
                allAssessments.add(a);

                double waste = a.getPredictedWasteQuantity();
                if (waste > 0) {
                    dayUnitBreakdown.put(unit, dayUnitBreakdown.getOrDefault(unit, 0.0) + waste);
                    weeklyUnitBreakdown.put(unit, weeklyUnitBreakdown.getOrDefault(unit, 0.0) + waste);

                    String lowerUnit = unit.toLowerCase();
                    if (lowerUnit.equals("kg") || lowerUnit.equals("kilogram") || lowerUnit.equals("kilograms")) {
                        weeklyTotalKg += waste;
                    } else if (lowerUnit.equals("g") || lowerUnit.equals("gram") || lowerUnit.equals("grams")) {
                        weeklyTotalKg += waste / 1000.0;
                    }
                }

                double loss = waste * pricePerUnit;
                dayLoss += loss;
                weeklyEstimatedLoss += loss;

                if ("HIGH".equalsIgnoreCase(a.getRiskLevel())) {
                    dayHighRiskCount++;
                    double sav = loss * 0.70;
                    daySavings += sav;
                    weeklyPotentialSavings += sav;
                } else if ("MEDIUM".equalsIgnoreCase(a.getRiskLevel())) {
                    double sav = loss * 0.50;
                    daySavings += sav;
                    weeklyPotentialSavings += sav;
                }
                dayRiskSum += a.getRiskScore();
            }

            double dayRiskScore = dayAssessments.isEmpty() ? 0.0 : Math.round((dayRiskSum / dayAssessments.size()) * 10.0) / 10.0;
            String dayRiskLevel = dayHighRiskCount > 0 || dayRiskScore >= 65.0 ? "HIGH" : (dayRiskScore >= 35.0 ? "MEDIUM" : "LOW");

            if (dayRiskScore > highestRiskScore) {
                highestRiskScore = dayRiskScore;
                highestRiskDay = dateStr;
            }
            if (!dayAssessments.isEmpty()) {
                totalRiskSum += dayRiskScore;
                activeRiskDayCount++;
            }

            List<String> dayQuantities = formatUnitBreakdownList(dayUnitBreakdown);
            String dayFormattedWaste = formatUnitBreakdownString(dayUnitBreakdown);

            Map<String, Object> dayMap = new LinkedHashMap<>();
            dayMap.put("date", dateStr);
            dayMap.put("dayName", dayName);
            dayMap.put("dayFormatted", dayFormatted);
            dayMap.put("dayIndex", dayIndex);
            dayMap.put("riskScore", dayRiskScore);
            dayMap.put("riskLevel", dayRiskLevel);
            dayMap.put("predictedWaste", new LinkedHashMap<>(dayUnitBreakdown));
            dayMap.put("predictedWasteByUnit", dayUnitBreakdown);
            dayMap.put("unitBreakdown", dayUnitBreakdown);
            dayMap.put("quantities", dayQuantities);
            dayMap.put("formattedTotalWaste", dayFormattedWaste);
            dayMap.put("estimatedLoss", Math.round(dayLoss));
            dayMap.put("potentialSavings", Math.round(daySavings));
            dayMap.put("highRiskItemCount", dayHighRiskCount);
            dayMap.put("totalItemsEvaluated", dayAssessments.size());
            dayMap.put("items", dayAssessments);

            forecastDays.add(dayMap);
        }

        double overallRiskScore = activeRiskDayCount > 0 ? Math.round((totalRiskSum / activeRiskDayCount) * 10.0) / 10.0 : 0.0;
        double validHighestRiskScore = highestRiskScore >= 0 ? highestRiskScore : 0.0;
        List<String> weeklyQuantities = formatUnitBreakdownList(weeklyUnitBreakdown);
        String weeklyFormattedWaste = formatUnitBreakdownString(weeklyUnitBreakdown);

        Map<String, Object> weeklySummary = new LinkedHashMap<>();
        weeklySummary.put("forecastStartDate", forecastStartDate.toString());
        weeklySummary.put("forecastEndDate", forecastEndDate.toString());
        weeklySummary.put("highestRiskScore", validHighestRiskScore);
        weeklySummary.put("highestRiskDate", highestRiskDay);
        weeklySummary.put("highestRiskDay", highestRiskDay);
        weeklySummary.put("predictedWaste", new LinkedHashMap<>(weeklyUnitBreakdown));
        weeklySummary.put("predictedWasteByUnit", weeklyUnitBreakdown);
        weeklySummary.put("unitBreakdown", weeklyUnitBreakdown);
        weeklySummary.put("quantities", weeklyQuantities);
        weeklySummary.put("formattedTotalWaste", weeklyFormattedWaste);
        weeklySummary.put("overallRiskScore", overallRiskScore);
        weeklySummary.put("estimatedLoss", Math.round(weeklyEstimatedLoss));
        weeklySummary.put("potentialSavings", Math.round(weeklyPotentialSavings));
        weeklySummary.put("totalItemsEvaluated", activeItems.size());

        String predictionTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US));

        Map<String, Object> todayActualWaste = calculateTodayActualWaste(items);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", clock.instant().toString());
        report.put("evaluatedItemCount", activeItems.size());
        report.put("forecastDayCount", 7);
        report.put("forecastStartDate", forecastStartDate.toString());
        report.put("forecastEndDate", forecastEndDate.toString());
        report.put("activeInventoryCount", activeItems.size());
        report.put("hasActiveInventory", !activeItems.isEmpty());
        report.put("days", forecastDays);
        report.put("weeklySummary", weeklySummary);
        report.put("weeklyTotals", weeklySummary);
        report.put("overallRiskScore", overallRiskScore);
        report.put("highestRiskScore", validHighestRiskScore);
        report.put("highestRiskDate", highestRiskDay);
        report.put("highestRiskDay", highestRiskDay);
        report.put("expectedTotalWasteKg", Math.round(weeklyTotalKg * 100.0) / 100.0);
        report.put("estimatedMoneyLost", Math.round(weeklyEstimatedLoss));
        report.put("potentialSavings", Math.round(weeklyPotentialSavings));
        report.put("highRiskCount", forecastDays.stream().mapToInt(d -> (Integer) d.get("highRiskItemCount")).sum());
        report.put("totalItemsEvaluated", activeItems.size());
        report.put("predictedWasteByUnit", weeklyUnitBreakdown);
        report.put("unitBreakdown", weeklyUnitBreakdown);
        report.put("quantities", weeklyQuantities);
        report.put("formattedTotalWaste", weeklyFormattedWaste);
        report.put("predictionDate", forecastStartDate.toString());
        report.put("predictionTime", predictionTime);
        report.put("engine", PrologService.isPrologAvailable() ? "SWI-Prolog Expert Engine" : "SWI-Prolog Rules Knowledge Base");
        // Dashboard current risk must never inherit a future forecast assessment.
        List<PrologAssessment> currentAssessments = new ArrayList<>();
        for (FoodItem item : activeItems) {
            assessFoodItem(item).ifPresent(currentAssessments::add);
        }
        report.put("items", currentAssessments);
        List<Map<String, Object>> forecastItems = new ArrayList<>();
        for (PrologAssessment current : currentAssessments) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("foodItemId", current.getFoodItemId());
            detail.put("forecastStartDate",forecastStartDate.toString()); detail.put("forecastEndDate",forecastEndDate.toString());
            detail.put("name", current.getFoodName()); detail.put("category", current.getCategory());
            detail.put("unit", current.getUnit()); detail.put("currentStock", current.getStock());
            detail.put("expiryDate", current.getExpiryDate()); detail.put("currentDaysRemaining", current.getCurrentDaysRemaining());
            detail.put("riskLevel", current.getRiskLevel()); detail.put("riskScore", current.getRiskScore());
            detail.put("expectedDailyDemand", current.getExpectedDemand());
            detail.put("historicalWasteRate", current.getHistoricalWasteRate());
            detail.put("currentRiskLevel", current.getRiskLevel());
            detail.put("currentRiskScore", current.getRiskScore());
            detail.put("reason", current.getReasonEn());
            detail.put("reasonMy", current.getReasonMy());
            detail.put("recommendedActionMy", translator.translateToMyanmar(current.getRecommendedAction()));
            detail.put("redistributionStatusLabelEn", current.getRedistributionStatusLabelEn());
            detail.put("redistributionStatusLabelMy", current.getRedistributionStatusLabelMy());
            detail.put("projectedSurplus", current.getProjectedSurplus());
            detail.put("suggestedDonationQuantity", current.isRedistributionEligible() ? current.getSuggestedDonationQuantity() : 0);
            detail.put("redistributionStatus", current.getRedistributionStatus());
            detail.put("recommendedAction", current.getRecommendedAction());
            List<Map<String,Object>> daily = new ArrayList<>();
            double totalWaste = 0, savings = 0;
            for (Map<String,Object> day : forecastDays) {
                @SuppressWarnings("unchecked") List<PrologAssessment> rows = (List<PrologAssessment>)day.get("items");
                for (PrologAssessment row : rows) {
                    if (!Objects.equals(current.getFoodItemId(), row.getFoodItemId()) || !Objects.equals(current.getFoodName(), row.getFoodName())) continue;
                    Map<String,Object> entry = new LinkedHashMap<>();
                    entry.put("date",day.get("date")); entry.put("projectedOpeningStock",row.getProjectedOpeningStock());
                    entry.put("expectedDemand",row.getExpectedDemand()); entry.put("predictedSales",row.getPredictedSalesQuantity());
                    entry.put("daysToExpiry",row.getExpiryDays()); entry.put("riskLevel",row.getRiskLevel()); entry.put("riskScore",row.getRiskScore());
                    entry.put("predictedWaste",row.getPredictedWasteQuantity()); entry.put("projectedClosingStock",row.getProjectedClosingStock());
                    entry.put("reason",row.getReasonEn()); entry.put("reasonMy",row.getReasonMy());
                    entry.put("recommendedAction",row.getRecommendedAction()); daily.add(entry);
                    totalWaste += row.getPredictedWasteQuantity();
                    double price = activeItems.stream().filter(i -> Objects.equals(i.getId(),current.getFoodItemId())).findFirst()
                        .map(i -> i.getPricePerUnit() == null ? 0.0 : i.getPricePerUnit().doubleValue()).orElse(0.0);
                    savings += row.getPredictedWasteQuantity() * price * ("HIGH".equals(row.getRiskLevel()) ? 0.70 : "MEDIUM".equals(row.getRiskLevel()) ? 0.50 : 0);
                }
            }
            BigDecimal itemPrice = activeItems.stream().filter(i -> Objects.equals(i.getId(), current.getFoodItemId()))
                .findFirst().map(FoodItem::getPricePerUnit).orElse(null);
            detail.put("estimatedPotentialLoss", itemPrice == null ? null : itemPrice.multiply(BigDecimal.valueOf(totalWaste)).setScale(2, RoundingMode.HALF_UP));
            detail.put("sevenDayPredictedWaste",totalWaste); detail.put("potentialSavings",Math.round(savings));
            detail.put("dailyForecast",daily); forecastItems.add(detail);
        }
        report.put("forecastItems",forecastItems);
        report.put("forecastStatus", activeItems.isEmpty() ? "NO_INVENTORY" : weeklyUnitBreakdown.isEmpty() ? "ZERO_FORECAST" : "READY");
        report.put("forecastContractVersion",2);
        if (currentAssessments.stream().anyMatch(a -> "REASONING_UNAVAILABLE".equals(a.getRedistributionStatus()))) report.put("forecastStatus", "PARTIAL");

        report.put("todayActualWaste", todayActualWaste);

        Map<String, Object> tomorrowPred = summarizeTomorrow(forecastDays);
        report.put("tomorrowPrediction", tomorrowPred);
        report.put("tomorrowDate", forecastStartDate.toString());
        report.put("nearestExpiryDate", tomorrowPred.get("nearestExpiryDate"));
        report.put("nearestExpiryFormatted", tomorrowPred.get("nearestExpiryFormatted"));
        report.put("nearestExpiryDaysRemaining", tomorrowPred.get("nearestExpiryDaysRemaining"));
        report.put("tomorrowItems", tomorrowPred.get("items"));
        report.put("tomorrowQuantities", tomorrowPred.get("quantities"));
        report.put("tomorrowUnitBreakdown", tomorrowPred.get("unitBreakdown"));
        report.put("tomorrowFormattedWaste", tomorrowPred.get("formattedTotalWaste"));

        return report;
    }

    /**
     * Calculates Today's Actual / Confirmed Waste according to the project's expiry rule:
     * For products that reach the end of their usable life TODAY (expiry_date == today, quantity > 0):
     * remaining unsold quantity -> today's actual waste.
     * Past expired items (expiry_date < today, quantity > 0) are also treated as actual waste.
     */
    public Map<String, Object> calculateTodayActualWaste(List<FoodItem> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(clock);
        String todayStr = today.toString();

        result.put("date", todayStr);
        result.put("todayDate", todayStr);

        if (items == null || items.isEmpty()) {
            result.put("unitBreakdown", Collections.emptyMap());
            result.put("quantities", Collections.emptyList());
            result.put("formattedTotalWaste", "0.0");
            result.put("items", Collections.emptyList());
            result.put("totalLoss", 0.0);
            result.put("formattedLoss", "0 MMK");
            result.put("carbonKg", 0.0);
            result.put("formattedCarbon", "0.0 kg CO₂e");
            return result;
        }

        // Select items reaching end of usable life today (or past expired with remaining stock)
        List<FoodItem> todayWasteItems = items.stream()
                .filter(Objects::nonNull)
                .filter(i -> i.getQuantity() != null && i.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .filter(i -> i.getExpiryDate() != null && !i.getExpiryDate().isAfter(today))
                .toList();

        Map<String, Double> unitTotals = new LinkedHashMap<>();
        double totalMonetaryLoss = 0.0;
        double totalKgForCarbon = 0.0;

        List<Map<String, Object>> itemOutputs = new ArrayList<>();
        for (FoodItem fi : todayWasteItems) {
            double qty = fi.getQuantity().doubleValue();
            String unit = fi.getUnit() != null && !fi.getUnit().trim().isEmpty() ? fi.getUnit().trim() : "units";
            unitTotals.put(unit, unitTotals.getOrDefault(unit, 0.0) + qty);

            double price = fi.getPricePerUnit() != null ? fi.getPricePerUnit().doubleValue() : 0.0;
            double loss = qty * price;
            totalMonetaryLoss += loss;

            String lowerUnit = unit.toLowerCase();
            if (lowerUnit.equals("kg") || lowerUnit.equals("kilogram")) {
                totalKgForCarbon += qty;
            } else if (lowerUnit.equals("g") || lowerUnit.equals("gram")) {
                totalKgForCarbon += qty / 1000.0;
            } else {
                totalKgForCarbon += qty; // default ratio
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", fi.getId());
            out.put("name", fi.getName());
            out.put("quantity", qty);
            out.put("unit", unit);
            out.put("pricePerUnit", price);
            out.put("monetaryLoss", Math.round(loss * 100.0) / 100.0);
            out.put("expiryDate", fi.getExpiryDate().toString());
            itemOutputs.add(out);
        }

        List<String> quantities = new ArrayList<>();
        for (Map.Entry<String, Double> entry : unitTotals.entrySet()) {
            String u = entry.getKey();
            double val = Math.round(entry.getValue() * 10.0) / 10.0;
            String lower = u.toLowerCase();
            if ((lower.equals("pieces") || lower.equals("piece") || lower.equals("pcs") || lower.equals("units") || lower.equals("pack")) && val % 1 == 0) {
                quantities.add((long) val + " " + u);
            } else {
                quantities.add(val + " " + u);
            }
        }

        double carbonKg = Math.round(totalKgForCarbon * 2.5 * 10.0) / 10.0;

        result.put("items", itemOutputs);
        result.put("unitBreakdown", unitTotals);
        result.put("quantities", quantities);
        result.put("formattedTotalWaste", quantities.isEmpty() ? "0.0" : String.join("\n", quantities));
        result.put("totalLoss", totalMonetaryLoss);
        result.put("formattedLoss", Math.round(totalMonetaryLoss) + " MMK");
        result.put("carbonKg", carbonKg);
        result.put("formattedCarbon", carbonKg + " kg CO₂e");
        return result;
    }

    /**
     * Calculates the Predicted Tomorrow metrics based strictly on active products
     * expiring exactly TOMORROW (current_date + 1 day).
     */
    public Map<String, Object> calculateTomorrowPrediction(List<FoodItem> items) {
        try {
            return (Map<String,Object>) assessInventory(items).get("tomorrowPrediction");
        } catch (SQLException e) { throw new IllegalStateException("Forecast unavailable",e); }
    }

    /** Expiring-tomorrow subset of the same day-one projection, preserving batch identity. */
    private Map<String,Object> summarizeTomorrow(List<Map<String,Object>> forecastDays) {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        List<PrologAssessment> rows = (List<PrologAssessment>) forecastDays.get(0).get("items");
        List<PrologAssessment> selected = rows.stream().filter(a -> tomorrow.equals(a.getExpiryDate())).toList();
        Map<String,Double> units = new LinkedHashMap<>();
        for (PrologAssessment row : selected) units.merge(row.getUnit(),row.getPredictedWasteQuantity(),Double::sum);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("predictionDate",tomorrow.toString()); result.put("tomorrowDate",tomorrow.toString());
        result.put("nearestExpiryDate",selected.isEmpty() ? null : tomorrow.toString());
        result.put("nearestExpiryFormatted",selected.isEmpty() ? null : tomorrow.format(DateTimeFormatter.ofPattern("MMM d",Locale.US)));
        result.put("nearestExpiryDaysRemaining",selected.isEmpty() ? null : 1L);
        result.put("items",selected); result.put("unitBreakdown",units);
        result.put("quantities",formatUnitBreakdownList(units)); result.put("formattedTotalWaste",formatUnitBreakdownString(units));
        return result;
    }

    /**
     * Assesses relevant tomorrow items strictly from current inventory batches.
     * Preserves exact food_item_id / batch relationships without merging different batches.
     * Evaluates each active batch expiring tomorrow via existing PrologService / SWI-Prolog rules.
     * Completely read-only (no database writes).
     */
    public List<Map<String, Object>> assessTomorrowBatches(List<FoodItem> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate tomorrow = today.plusDays(1);

        // Filter strictly for active items with remainingQuantity > 0 and expiryDate == tomorrow
        List<FoodItem> tomorrowCandidates = items.stream()
                .filter(Objects::nonNull)
                .filter(i -> i.getQuantity() != null && i.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .filter(i -> i.getExpiryDate() != null && i.getExpiryDate().isEqual(tomorrow))
                .toList();

        if (tomorrowCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            Map<String,Object> report = assessInventory(tomorrowCandidates);
            @SuppressWarnings("unchecked") List<Map<String,Object>> details=(List<Map<String,Object>>)report.get("forecastItems");
            List<Map<String,Object>> result=new ArrayList<>();
            for (Map<String,Object> detail:details) {
                @SuppressWarnings("unchecked") List<Map<String,Object>> daily=(List<Map<String,Object>>)detail.get("dailyForecast");
                if(daily.isEmpty()) continue;
                Map<String,Object> row=new LinkedHashMap<>(detail), first=daily.get(0);
                double stock=((Number)detail.get("currentStock")).doubleValue();
                double sold=((Number)first.get("predictedSales")).doubleValue(), waste=((Number)first.get("predictedWaste")).doubleValue();
                row.put("remainingQuantity",stock); row.put("quantity",stock); row.put("expectedDemand",detail.get("expectedDailyDemand"));
                row.put("expiryDaysRemaining",detail.get("currentDaysRemaining"));
                row.put("predictedSalesQuantity",sold); row.put("predictedWasteQuantity",waste);
                row.put("predictedRedistributionQuantity",0.0);
                row.put("predictedSalesRate",stock>0 ? 100*sold/stock:0); row.put("predictedWasteRate",stock>0?100*waste/stock:0);
                row.put("predictedRedistributionRate",0.0); row.put("reasonEn",first.get("reason")); row.put("reasonMy",first.get("reasonMy"));
                result.add(row);
            }
            return result;
        } catch(SQLException e) { throw new IllegalStateException("Forecast unavailable",e); }
    }

    /**
     * Evaluates all items in the inventory and returns a comprehensive batch AI prediction report.
     */
    public Map<String, Object> assessAllInventory() throws SQLException {
        // Automatic expiry-driven transition:
        // Convert any unsold expired inventory (expiry_date <= today and quantity > 0)
        // into confirmed waste records and deduct inventory to exactly 0.00 atomically.
        if (wasteService != null) {
            try {
                wasteService.convertExpiredInventoryToWaste(1L);
            } catch (Exception e) {
                logger.warn("Could not automatically convert expired inventory to waste: {}", e.getMessage());
            }
        }

        List<FoodItem> items = foodItemService.getAllFoodItems();
        Map<String, Object> report = assessInventory(items);

        // Persist tomorrow's forecast under tomorrow's prediction date, while the
        // response's top-level items remain current Dashboard assessments.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) report.get("days");
        @SuppressWarnings("unchecked")
        List<PrologAssessment> assessments = days.isEmpty() ? Collections.emptyList()
                : (List<PrologAssessment>) days.get(0).get("items");
        Double avgRisk = (Double) report.get("overallRiskScore");
        Double expectedTotalWasteKg = (Double) report.get("expectedTotalWasteKg");
        Double estimatedMoneyLost = ((Number) report.get("estimatedMoneyLost")).doubleValue();
        Double potentialSavings = ((Number) report.get("potentialSavings")).doubleValue();
        Integer highRiskCount = (Integer) report.get("highRiskCount");
        @SuppressWarnings("unchecked")
        Map<String, Double> unitBreakdown = (Map<String, Double>) report.get("unitBreakdown");
        String formattedTotalWaste = (String) report.get("formattedTotalWaste");

        Long savedId = null;
        LocalDateTime createdAt = LocalDateTime.now();

        // Persist to MySQL predictions and prediction_items tables
        if (DatabaseConfig.isAvailable() && assessments != null && !assessments.isEmpty()) {
            try {
                Prediction pred = new Prediction();
                pred.setPredictionDate(LocalDate.now(clock).plusDays(1));
                pred.setOverallRiskScore(BigDecimal.valueOf(avgRisk).setScale(2, RoundingMode.HALF_UP));
                pred.setExpectedTotalWasteKg(BigDecimal.valueOf(expectedTotalWasteKg).setScale(2, RoundingMode.HALF_UP));
                pred.setEstimatedMoneyLost(BigDecimal.valueOf(estimatedMoneyLost).setScale(2, RoundingMode.HALF_UP));
                pred.setPotentialSavings(BigDecimal.valueOf(potentialSavings).setScale(2, RoundingMode.HALF_UP));
                pred.setStatus(Prediction.Status.GENERATED);
                Prediction savedPred = predictionDao.savePrediction(pred);
                savedId = savedPred.getId();
                if (savedPred.getCreatedAt() != null) {
                    createdAt = savedPred.getCreatedAt();
                }

                List<PredictionItem> pItems = new ArrayList<>();
                for (PrologAssessment a : assessments) {
                    PredictionItem pi = new PredictionItem();
                    pi.setPredictionId(savedPred.getId());
                    pi.setFoodItemId(a.getFoodItemId());
                    pi.setUnit(a.getUnit());
                    pi.setCurrentStock(BigDecimal.valueOf(a.getStock()).setScale(2, RoundingMode.HALF_UP));
                    pi.setExpectedDemand(BigDecimal.valueOf(a.getExpectedDemand()).setScale(2, RoundingMode.HALF_UP));
                    pi.setExpiryDays(a.getExpiryDays());
                    pi.setHistoricalWasteRate(BigDecimal.valueOf(a.getHistoricalWasteRate()).setScale(4, RoundingMode.HALF_UP));
                    pi.setRiskLevel(PredictionItem.RiskLevel.valueOf(a.getRiskLevel()));
                    pi.setRiskScore(BigDecimal.valueOf(a.getRiskScore()).setScale(2, RoundingMode.HALF_UP));
                    pi.setRiskPercentage(BigDecimal.valueOf(a.getRiskScore()).setScale(2, RoundingMode.HALF_UP));
                    pi.setPredictedWasteQty(BigDecimal.valueOf(a.getPredictedWasteQuantity()).setScale(2, RoundingMode.HALF_UP));
                    pi.setRecommendedProduction(BigDecimal.valueOf(a.getRecommendedProduction()).setScale(2, RoundingMode.HALF_UP));
                    pi.setPriorityUsage(a.getPriorityUsage());
                    pi.setReasoningText(a.getReasonEn());
                    pi.setReasoningTextEn(a.getReasonEn());
                    pi.setReasoningTextMy(a.getReasonMy());
                    pItems.add(pi);
                }
                predictionDao.savePredictionItems(savedPred.getId(), pItems);
            } catch (Exception e) {
                logger.warn("Could not persist predictions to MySQL: {}", e.getMessage());
            }
        }

        String formattedTime = createdAt.format(DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US));

        Map<String, Object> result = new LinkedHashMap<>(report);
        if (savedId != null) {
            result.put("id", savedId);
        }
        result.put("predictionTime", formattedTime);
        result.put("createdAt", createdAt.toString());
        return result;
    }

    /**
     * Retrieves the latest persisted AI prediction report from MySQL.
     * If no prediction exists yet or DB is not available, executes a fresh 7-day evaluation.
     */
    public Map<String, Object> getLatestPredictionReport() throws SQLException {
        List<FoodItem> currentInventory = foodItemService.getAllFoodItems();

        Map<String, Object> freshForecast = assessInventory(currentInventory);

        if (DatabaseConfig.isAvailable()) {
            Optional<Prediction> latestOpt = predictionDao.findLatestPrediction();
            if (latestOpt.isPresent()) {
                freshForecast.put("id", latestOpt.get().getId());
                if (latestOpt.get().getCreatedAt() != null) {
                    freshForecast.put("createdAt", latestOpt.get().getCreatedAt().toString());
                    freshForecast.put("predictionTime", latestOpt.get().getCreatedAt().format(DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US)));
                }
            }
        }
        return freshForecast;
    }

    public static List<String> formatUnitBreakdownList(Map<String, Double> unitBreakdown) {
        if (unitBreakdown == null || unitBreakdown.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, Double> entry : unitBreakdown.entrySet()) {
            String u = entry.getKey();
            double val = Math.round(entry.getValue() * 10.0) / 10.0;
            String lower = u.toLowerCase();
            if ((lower.equals("pieces") || lower.equals("piece") || lower.equals("pcs") || lower.equals("units") || lower.equals("pack")) && val % 1 == 0) {
                list.add((long) val + " " + u);
            } else {
                list.add(String.format(Locale.US, "%.1f %s", val, u));
            }
        }
        return list;
    }

    public static String formatUnitBreakdownString(Map<String, Double> unitBreakdown) {
        if (unitBreakdown == null || unitBreakdown.isEmpty()) {
            return "0.0";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Double> entry : unitBreakdown.entrySet()) {
            String u = entry.getKey();
            double val = Math.round(entry.getValue() * 10.0) / 10.0;
            String valStr;
            String lower = u.toLowerCase();
            if ((lower.equals("pieces") || lower.equals("piece") || lower.equals("pcs") || lower.equals("units")) && val % 1 == 0) {
                valStr = String.valueOf((long) val);
            } else {
                valStr = String.format(Locale.US, "%.1f", val);
            }
            parts.add(valStr + " " + u);
        }
        return String.join(" • ", parts);
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

    /** Confirmed per-item history only. Zero waste is a valid measured rate. */
    private double calculateConfirmedHistoricalWasteRate(Long itemId) {
        if (itemId == null) return 0.0;
        try {
            BigDecimal rate = wasteDao.calculateHistoricalWasteRate(itemId, 14);
            return rate != null ? rate.doubleValue() : 0.0;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot assess waste risk without confirmed waste history for item " + itemId, e);
        }
    }

    public static String normalizeProductName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
