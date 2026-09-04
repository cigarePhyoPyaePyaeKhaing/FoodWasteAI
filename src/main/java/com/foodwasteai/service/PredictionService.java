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
     * Uses real historical daily sales if available, or a consistent canonical baseline (40% of stock).
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
        int expiryDays = com.foodwasteai.util.ExpiryStatusResolver.calculateDaysRemaining(item.getExpiryDate());

        double expectedDemand = calculateExpectedDailyDemand(item);

        double histWasteRate;
        if (item.getId() != null) {
            try {
                BigDecimal rate = wasteDao.calculateHistoricalWasteRate(item.getId(), 14);
                if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                    histWasteRate = rate.doubleValue();
                } else {
                    histWasteRate = getCategoryDefaultWasteRate(item.getCategory(), expiryDays);
                }
            } catch (Exception e) {
                histWasteRate = getCategoryDefaultWasteRate(item.getCategory(), expiryDays);
            }
        } else {
            histWasteRate = getCategoryDefaultWasteRate(item.getCategory(), expiryDays);
        }

        double currentProduction = expectedDemand * 1.1;

        PrologAssessment assessment = prologService.assessFoodItem(
                item.getName(), unit, stock, expectedDemand, expiryDays, histWasteRate, currentProduction
        );
        assessment.setFoodItemId(item.getId());
        assessment.setCategory(item.getCategory());
        assessment.setUnit(unit);
        assessment.setExpiryDate(item.getExpiryDate());
        int curDays = com.foodwasteai.util.ExpiryStatusResolver.calculateDaysRemaining(item.getExpiryDate());
        assessment.setCurrentDaysRemaining(curDays);
        assessment.setExpiryDaysRemaining(curDays);

        double surplus = Math.max(0.0, stock - expectedDemand);
        assessment.setExpectedDemand(expectedDemand);
        assessment.setProjectedSurplus(surplus);
        assessment.setSuggestedDonationQuantity(surplus);

        return Optional.of(assessment);
    }

    /**
     * Evaluates a provided inventory list and returns a comprehensive 7-Day AI Waste Forecast.
     * Grounded in current inventory, expiry progression, projected stock/demand, and SWI-Prolog reasoning.
     */
    public Map<String, Object> assessInventory(List<FoodItem> items) throws SQLException {
        if (items == null) items = Collections.emptyList();

        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
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
                double pricePerUnit = item.getPricePerUnit() != null ? item.getPricePerUnit().doubleValue() : 2000.0;

                double dailyDemand;
                if (item.getId() != null) {
                    try {
                        BigDecimal avgSales = salesDao.getHistoricalAverageDailySales(item.getId(), 7);
                        if (avgSales != null && avgSales.compareTo(BigDecimal.ZERO) > 0) {
                            dailyDemand = avgSales.doubleValue();
                        } else {
                            dailyDemand = Math.max(0.5, initialStock * 0.15);
                        }
                    } catch (Exception e) {
                        dailyDemand = Math.max(0.5, initialStock * 0.15);
                    }
                } else {
                    dailyDemand = Math.max(0.5, initialStock * 0.15);
                }

                int expiryDays = (int) ChronoUnit.DAYS.between(forecastDate, item.getExpiryDate());

                // Projected stock progression across the 7-day horizon:
                // Prior days consume projected daily demand; expired food is discarded
                double projectedStock = Math.max(0.0, initialStock - (dayIndex - 1) * dailyDemand);
                if (item.getExpiryDate().isBefore(forecastDate)) {
                    projectedStock = 0.0; // Already reached expiration and disposed on earlier date
                }

                if (projectedStock <= 0.0) {
                    // Depleted stock snapshot -> no active waste advice or risk
                    continue;
                }

                double histWasteRate;
                if (item.getId() != null) {
                    try {
                        BigDecimal rate = wasteDao.calculateHistoricalWasteRate(item.getId(), 14);
                        if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                            histWasteRate = rate.doubleValue();
                        } else {
                            histWasteRate = getCategoryDefaultWasteRate(item.getCategory(), Math.max(0, expiryDays));
                        }
                    } catch (Exception e) {
                        histWasteRate = getCategoryDefaultWasteRate(item.getCategory(), Math.max(0, expiryDays));
                    }
                } else {
                    histWasteRate = getCategoryDefaultWasteRate(item.getCategory(), Math.max(0, expiryDays));
                }

                double currentProduction = dailyDemand * 1.1;

                PrologAssessment a;
                if (expiryDays <= 0) {
                    // Item reaches expiration on this forecast day
                    a = prologService.assessFoodItem(item.getName(), unit, projectedStock, dailyDemand, 0, histWasteRate, currentProduction);
                } else {
                    a = prologService.assessFoodItem(item.getName(), unit, projectedStock, dailyDemand, expiryDays, histWasteRate, currentProduction);
                }

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
            dayMap.put("predictedWaste", dayUnitBreakdown.values().stream().mapToDouble(Double::doubleValue).sum());
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
        weeklySummary.put("predictedWaste", weeklyUnitBreakdown.values().stream().mapToDouble(Double::doubleValue).sum());
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
        report.put("items", forecastDays.isEmpty() ? Collections.emptyList() : forecastDays.get(0).get("items"));
        report.put("todayActualWaste", todayActualWaste);

        Map<String, Object> tomorrowPred = calculateTomorrowPrediction(items);
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
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
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
        Map<String, Object> result = new LinkedHashMap<>();
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        LocalDate tomorrow = today.plusDays(1);

        String tomorrowStr = tomorrow.toString();
        String tomorrowFormatted = tomorrow.format(DateTimeFormatter.ofPattern("MMM d", Locale.US));

        result.put("predictionDate", tomorrowStr);
        result.put("tomorrowDate", tomorrowStr);

        if (items == null || items.isEmpty()) {
            result.put("nearestExpiryDate", null);
            result.put("nearestExpiryFormatted", null);
            result.put("nearestExpiryDaysRemaining", null);
            result.put("unitBreakdown", Collections.emptyMap());
            result.put("quantities", Collections.emptyList());
            result.put("formattedTotalWaste", "0.0");
            result.put("items", Collections.emptyList());
            return result;
        }

        // 1. Filter strictly for active products with quantity > 0 and expiryDate == tomorrow (current_date + 1 day)
        // Exclude zero-stock, expired (< today), items expiring today (== today), and items expiring after tomorrow (> tomorrow)
        List<FoodItem> selectedProducts = items.stream()
                .filter(Objects::nonNull)
                .filter(i -> i.getQuantity() != null && i.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .filter(i -> i.getExpiryDate() != null && i.getExpiryDate().isEqual(tomorrow))
                .toList();

        if (selectedProducts.isEmpty()) {
            result.put("nearestExpiryDate", null);
            result.put("nearestExpiryFormatted", null);
            result.put("nearestExpiryDaysRemaining", null);
            result.put("unitBreakdown", Collections.emptyMap());
            result.put("quantities", Collections.emptyList());
            result.put("formattedTotalWaste", "0.0");
            result.put("items", Collections.emptyList());
            return result;
        }

        result.put("nearestExpiryDate", tomorrowStr);
        result.put("nearestExpiryFormatted", tomorrowFormatted);
        result.put("nearestExpiryDaysRemaining", 1L);

        // 2. Deduplicate selected products by normalized product name
        // (trim leading/trailing whitespace, compare case-insensitively e.g. "cheese", "Cheese", " CHEESE ")
        Map<String, FoodItem> canonicalProducts = new LinkedHashMap<>();
        for (FoodItem product : selectedProducts) {
            if (product == null) continue;
            String rawName = product.getName() != null ? product.getName() : "Item";
            String normKey = normalizeProductName(rawName);
            if (normKey.isEmpty()) {
                normKey = "item_" + (product.getId() != null ? product.getId() : UUID.randomUUID().toString());
            }

            if (!canonicalProducts.containsKey(normKey)) {
                canonicalProducts.put(normKey, product);
            } else {
                FoodItem existing = canonicalProducts.get(normKey);
                // Deterministic canonical choice:
                // Prefer entry with valid positive quantity; if quantities equal, prefer lowest ID
                BigDecimal exQty = existing.getQuantity() != null ? existing.getQuantity() : BigDecimal.ZERO;
                BigDecimal newQty = product.getQuantity() != null ? product.getQuantity() : BigDecimal.ZERO;
                boolean replace = false;
                if (newQty.compareTo(exQty) > 0) {
                    replace = true;
                } else if (newQty.compareTo(exQty) == 0) {
                    long exId = existing.getId() != null ? existing.getId() : Long.MAX_VALUE;
                    long newId = product.getId() != null ? product.getId() : Long.MAX_VALUE;
                    if (newId < exId) {
                        replace = true;
                    }
                }
                if (replace) {
                    canonicalProducts.put(normKey, product);
                }
            }
        }
        List<FoodItem> deduplicatedSelected = new ArrayList<>(canonicalProducts.values());

        // 3. Run authoritative backend AI/Prolog prediction logic for ONLY tomorrow's selected products
        List<PrologAssessment> tomorrowAssessments = new ArrayList<>();
        Map<String, Double> tomorrowUnitTotals = new LinkedHashMap<>();

        TranslationService translator = TranslationService.getInstance();
        for (FoodItem product : deduplicatedSelected) {
            Optional<PrologAssessment> opt = assessFoodItem(product);
            if (opt.isPresent()) {
                PrologAssessment a = opt.get();
                // Ensure bilingual reasoning is populated
                String reasoningEn = (a.getReasons() != null && !a.getReasons().isEmpty())
                        ? String.join(" | ", a.getReasons())
                        : (a.getReason() != null ? a.getReason() : "Prolog risk reasoning");
                String reasoningMy = translator.translateToMyanmar(reasoningEn);
                a.setReasonEn(reasoningEn);
                a.setReasonMy(reasoningMy);
                a.setReason(reasoningEn);
                a.setReasoning(reasoningEn);
                a.setExpiryDate(product.getExpiryDate());

                tomorrowAssessments.add(a);

                // 4. Calculate predicted waste quantity per product & group safely by unit
                double waste = a.getPredictedWasteQuantity();
                String unit = a.getUnit() != null && !a.getUnit().trim().isEmpty() ? a.getUnit().trim() : "units";
                tomorrowUnitTotals.put(unit, tomorrowUnitTotals.getOrDefault(unit, 0.0) + waste);
            }
        }

        // 5. Format displayed quantities by unit (preserving units, never combining incompatible units)
        List<String> quantities = new ArrayList<>();
        for (Map.Entry<String, Double> entry : tomorrowUnitTotals.entrySet()) {
            String u = entry.getKey();
            double val = Math.round(entry.getValue() * 10.0) / 10.0;
            String valStr = String.format(Locale.US, "%.1f", val);
            quantities.add(valStr + " " + u);
        }

        String formattedTotal = quantities.isEmpty() ? "0.0" : String.join("\n", quantities);

        result.put("unitBreakdown", tomorrowUnitTotals);
        result.put("quantities", quantities);
        result.put("formattedTotalWaste", formattedTotal);
        result.put("items", tomorrowAssessments);

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

        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
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

        List<Map<String, Object>> results = new ArrayList<>();
        for (FoodItem batch : tomorrowCandidates) {
            Optional<PrologAssessment> opt = assessFoodItem(batch);
            if (opt.isPresent()) {
                PrologAssessment a = opt.get();
                double stock = batch.getQuantity().doubleValue();
                double expectedDemand = a.getExpectedDemand();

                // 1. Predicted Sales: from real historical sales demand, bounded by available remaining stock
                double predictedSales = Math.min(stock, Math.max(0.0, expectedDemand));
                double remainingAfterSales = Math.max(0.0, stock - predictedSales);

                // 2. Predicted Waste: from existing Prolog rule, bounded by remaining unsold stock
                double rawWaste = Math.max(0.0, a.getPredictedWasteQuantity());
                double predictedWaste = Math.min(remainingAfterSales, rawWaste);
                double remainingAfterWaste = Math.max(0.0, remainingAfterSales - predictedWaste);

                // 3. Predicted Redistribution: from projected surplus and charity donation eligibility, bounded by remaining stock
                double surplus = Math.max(0.0, a.getProjectedSurplus());
                double predictedRedist = 0.0;
                if (surplus > 0 && remainingAfterWaste > 0) {
                    predictedRedist = Math.min(remainingAfterWaste, surplus);
                }

                // Strict conservation check: Sales + Waste + Redistribution <= stock
                double totalAllocated = predictedSales + predictedWaste + predictedRedist;
                if (totalAllocated > stock && stock > 0) {
                    predictedRedist = Math.max(0.0, stock - predictedSales - predictedWaste);
                    totalAllocated = predictedSales + predictedWaste + predictedRedist;
                }

                // 100% Composition: Each bar represents its share of predicted outcomes for this item
                double salesRate = 0.0;
                double wasteRate = 0.0;
                double redistRate = 0.0;
                if (totalAllocated > 0) {
                    salesRate = Math.round((predictedSales / totalAllocated) * 1000.0) / 10.0;
                    wasteRate = Math.round((predictedWaste / totalAllocated) * 1000.0) / 10.0;
                    redistRate = Math.round((100.0 - salesRate - wasteRate) * 10.0) / 10.0;
                }

                Map<String, Object> batchMap = new LinkedHashMap<>();
                batchMap.put("foodItemId", batch.getId());
                batchMap.put("name", batch.getName());
                batchMap.put("category", batch.getCategory());
                batchMap.put("remainingQuantity", stock);
                batchMap.put("quantity", stock);
                batchMap.put("unit", a.getUnit());
                batchMap.put("expiryDate", batch.getExpiryDate().toString());
                int curBatchDays = com.foodwasteai.util.ExpiryStatusResolver.calculateDaysRemaining(batch.getExpiryDate(), today);
                batchMap.put("currentDaysRemaining", curBatchDays);
                batchMap.put("expiryDaysRemaining", curBatchDays);
                batchMap.put("expectedDemand", expectedDemand);
                batchMap.put("predictedSalesQuantity", predictedSales);
                batchMap.put("predictedSalesRate", salesRate);
                batchMap.put("predictedWasteQuantity", predictedWaste);
                batchMap.put("predictedWasteRate", wasteRate);
                batchMap.put("predictedRedistributionQuantity", predictedRedist);
                batchMap.put("predictedRedistributionRate", redistRate);
                batchMap.put("riskScore", a.getRiskScore());
                batchMap.put("riskLevel", a.getRiskLevel());
                batchMap.put("reasonEn", a.getReasonEn());
                batchMap.put("reasonMy", a.getReasonMy());

                results.add(batchMap);
            }
        }

        return results;
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

        @SuppressWarnings("unchecked")
        List<PrologAssessment> assessments = (List<PrologAssessment>) report.get("items");
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
                pred.setPredictionDate(com.foodwasteai.util.ExpiryStatusResolver.getToday().plusDays(1));
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
        List<FoodItem> currentInventory = Collections.emptyList();
        try {
            currentInventory = foodItemService.getAllFoodItems();
        } catch (Exception e) {
            logger.warn("Could not fetch inventory for latest report: {}", e.getMessage());
        }

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

    public static String normalizeProductName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
