package com.foodwasteai.service;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.RecommendationDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.Recommendation;
import com.foodwasteai.prolog.PrologAssessment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
            List<Recommendation> list = recommendationDao.findAll();
            if (list.isEmpty()) {
                return generateRecommendationsFromProlog();
            }
            return list;
        }
        if (memoryRecs.isEmpty()) {
            return generateRecommendationsFromProlog();
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
     * Generates fresh actionable recommendations by running Prolog reasoning across active inventory.
     * Enforces exactly ONE deduplicated, authoritative recommendation per active item,
     * synchronizes estimated savings with PredictionService, and excludes zero-stock items.
     */
    public List<Recommendation> generateRecommendationsFromProlog() throws SQLException {
        // 1. Run SWI-Prolog prediction assessment across all inventory items
        Map<String, Object> predictionReport = predictionService.assessAllInventory();

        List<FoodItem> items = foodItemService.getAllFoodItems();
        List<Recommendation> generated = new ArrayList<>();

        if (DatabaseConfig.isAvailable()) {
            recommendationDao.clearPendingRecommendations();
        } else {
            memoryRecs.clear();
        }

        TranslationService translator = TranslationService.getInstance();

        for (FoodItem item : items) {
            // Exclude items with zero or negative remaining stock from active recommendation generation
            if (item.getQuantity() == null || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            Optional<PrologAssessment> opt = predictionService.assessFoodItemById(item.getId());
            if (opt.isPresent()) {
                PrologAssessment assessment = opt.get();
                String unit = item.getUnit() != null ? item.getUnit() : "kg";
                double price = item.getPricePerUnit() != null ? item.getPricePerUnit().doubleValue() : 5000.0;
                double surplus = assessment.getProjectedSurplus();
                if (surplus <= 0.0 && assessment.getStock() > assessment.getExpectedDemand()) {
                    surplus = Math.max(0.0, assessment.getStock() - assessment.getExpectedDemand());
                }
                int expiryDays = assessment.getCurrentDaysRemaining();
                double projectedWaste = assessment.getPredictedWasteQuantity() > 0 ?
                        assessment.getPredictedWasteQuantity() : surplus;

                // 1. EXPIRED ACTIVE ITEM (Stock > 0, Expiry < 0)
                if (expiryDays < 0 || "DISPOSE_OR_COMPOST".equalsIgnoreCase(assessment.getPriorityUsage())) {
                    Recommendation rec = new Recommendation();
                    rec.setFoodItemId(item.getId());
                    rec.setFoodItemName(item.getName());
                    rec.setStatus(Recommendation.Status.PENDING);
                    rec.setCategory(Recommendation.Category.URGENT);
                    rec.setRiskLevel(Recommendation.RiskLevel.HIGH);
                    rec.setTitle("Halt production and dispose of expired " + item.getName());
                    rec.setTitleEn(rec.getTitle());
                    rec.setTitleMy(item.getName() + " သက်တမ်းကုန်ဆုံးသဖြင့် ထုတ်လုပ်မှုရပ်ဆိုင်းပြီး စွန့်ပစ်ပါ");
                    rec.setDescription(String.format("Item has passed expiration date (%d day(s) ago). Do not serve to customers. Halt production and dispose of or compost safely.",
                            Math.max(1, Math.abs(expiryDays))));
                    rec.setDescriptionEn(rec.getDescription());
                    rec.setDescriptionMy(String.format("အစားအစာ သက်တမ်းကုန်ဆုံးခဲ့သည်မှာ %d ရက်ရှိပါပြီ။ ဧည့်သည်များကို မကျွေးမွေးပါနှင့်။ ဘေးကင်းစွာ စွန့်ပစ်ပါ သို့မဟုတ် မြေဆွေးပြုလုပ်ပါ။",
                            Math.max(1, Math.abs(expiryDays))));
                    rec.setReasoningDetails("Prolog Rule: assess_waste_risk/6 (High Risk: Expired) -> evaluate_priority_use/3 (DISPOSE_OR_COMPOST)");
                    rec.setReasoningDetailsEn(rec.getReasoningDetails());
                    rec.setReasoningDetailsMy(translator.translateToMyanmar(rec.getReasoningDetails()));
                    rec.setEstimatedSavings(BigDecimal.ZERO);
                    saveRecommendation(rec);
                    generated.add(rec);
                }
                // 2. HIGH RISK ACTIVE ITEM
                else if ("HIGH".equalsIgnoreCase(assessment.getRiskLevel())) {
                    double itemSavings = projectedWaste * price * 0.70;

                    // Action 1: Reduce next batch
                    Recommendation rProd = new Recommendation();
                    rProd.setFoodItemId(item.getId());
                    rProd.setFoodItemName(item.getName());
                    rProd.setStatus(Recommendation.Status.PENDING);
                    rProd.setCategory(Recommendation.Category.URGENT);
                    rProd.setRiskLevel(Recommendation.RiskLevel.HIGH);
                    rProd.setTitle("Reduce next production batch for " + item.getName());
                    rProd.setTitleEn(rProd.getTitle());
                    rProd.setTitleMy(item.getName() + " အတွက် နောက်ထုတ်လုပ်မှု ပမာဏကို လျှော့ချပါ");
                    rProd.setDescription(String.format("Stock is %.1f %s against %.1f %s expected demand with %d-day expiry remaining. Reduce next scheduled production batch by 15-25%% to prevent excess spoilage.",
                            assessment.getStock(), unit, assessment.getExpectedDemand(), unit, Math.max(0, expiryDays)));
                    rProd.setDescriptionEn(rProd.getDescription());
                    rProd.setDescriptionMy(String.format("လက်ကျန် %.1f %s သည် ခန့်မှန်းဝယ်လိုအား %.1f %s ထက် များနေပါသဖြင့် နောက်တစ်သုတ် ထုတ်လုပ်မှုကို ၁၅-၂၅%% လျှော့ချပါ။",
                            assessment.getStock(), unit, assessment.getExpectedDemand(), unit));
                    rProd.setReasoningDetails("Prolog Rule: assess_waste_risk/6 (High Risk) -> recommend_production/6 (Reduce production by 15-25%)");
                    rProd.setReasoningDetailsEn(rProd.getReasoningDetails());
                    rProd.setReasoningDetailsMy(translator.translateToMyanmar(rProd.getReasoningDetails()));
                    rProd.setEstimatedSavings(BigDecimal.valueOf(itemSavings).setScale(2, RoundingMode.HALF_UP));
                    saveRecommendation(rProd);
                    generated.add(rProd);

                    // Action 2: Redistribute excess inventory (if surplus is actionable)
                    if (assessment.isRecommendRedistribution() || surplus >= 5.0) {
                        Recommendation rRedist = new Recommendation();
                        rRedist.setFoodItemId(item.getId());
                        rRedist.setFoodItemName(item.getName());
                        rRedist.setStatus(Recommendation.Status.PENDING);
                        rRedist.setCategory(Recommendation.Category.REDISTRIBUTION);
                        rRedist.setRiskLevel(Recommendation.RiskLevel.HIGH);
                        rRedist.setTitle("Redistribute excess inventory for " + item.getName());
                        rRedist.setTitleEn(rRedist.getTitle());
                        rRedist.setTitleMy(item.getName() + " ပိုလျှံလက်ကျန်ကို ပြန်လည်ဖြန့်ဝေလှူဒါန်းပါ");
                        rRedist.setDescription(String.format("Projected surplus (%.1f %s) detected near expiry (%d day(s) remaining). Dispatch to registered food bank or partner organization before expiry cutoff.",
                                surplus, unit, expiryDays));
                        rRedist.setDescriptionEn(rRedist.getDescription());
                        rRedist.setDescriptionMy(String.format("သက်တမ်းကုန်ဆုံးရန် %d ရက်သာ လိုတော့သည့်အတွက် ခန့်မှန်းပိုလျှံပမာဏ (%.1f %s) ကို မိတ်ဖက်အဖွဲ့အစည်းသို့ အချိန်မီ လှူဒါန်းပါ။",
                                expiryDays, surplus, unit));
                        rRedist.setReasoningDetails("Prolog Rule: evaluate_redistribution/6 -> Surplus eligible for emergency partner donation");
                        rRedist.setReasoningDetailsEn(rRedist.getReasoningDetails());
                        rRedist.setReasoningDetailsMy(translator.translateToMyanmar(rRedist.getReasoningDetails()));
                        rRedist.setEstimatedSavings(BigDecimal.ZERO);
                        saveRecommendation(rRedist);
                        generated.add(rRedist);
                    }

                    // Action 3: Prioritize usage today (if near expiry)
                    if ("IMMEDIATE_USE".equalsIgnoreCase(assessment.getPriorityUsage()) || expiryDays <= 2) {
                        Recommendation rUsage = new Recommendation();
                        rUsage.setFoodItemId(item.getId());
                        rUsage.setFoodItemName(item.getName());
                        rUsage.setStatus(Recommendation.Status.PENDING);
                        rUsage.setCategory(Recommendation.Category.IMPORTANT);
                        rUsage.setRiskLevel(Recommendation.RiskLevel.HIGH);
                        rUsage.setTitle("Prioritize usage today for " + item.getName());
                        rUsage.setTitleEn(rUsage.getTitle());
                        rUsage.setTitleMy(item.getName() + " ကို ယနေ့ မီးဖိုချောင်တွင် ဦးစားပေး သုံးစွဲပါ");
                        rUsage.setDescription(String.format("Item expires in %d day(s). Prioritize in today's menu specials, meal prep, and kitchen consumption immediately.",
                                Math.max(0, expiryDays)));
                        rUsage.setDescriptionEn(rUsage.getDescription());
                        rUsage.setDescriptionMy(String.format("သက်တမ်းကုန်ဆုံးရန် %d ရက်သာ ကျန်ရှိသဖြင့် ယနေ့ မီးဖိုချောင် အထူးဟင်းလျာများတွင် ချက်ချင်း ထည့်သွင်းချက်ပြုတ်ပါ။",
                                Math.max(0, expiryDays)));
                        rUsage.setReasoningDetails("Prolog Rule: evaluate_priority_use/3 -> IMMEDIATE_USE required due to short shelf life");
                        rUsage.setReasoningDetailsEn(rUsage.getReasoningDetails());
                        rUsage.setReasoningDetailsMy(translator.translateToMyanmar(rUsage.getReasoningDetails()));
                        rUsage.setEstimatedSavings(BigDecimal.ZERO);
                        saveRecommendation(rUsage);
                        generated.add(rUsage);
                    }
                }
                // 3. MEDIUM RISK ACTIVE ITEM
                else if ("MEDIUM".equalsIgnoreCase(assessment.getRiskLevel())) {
                    double itemSavings = projectedWaste * price * 0.50;

                    // Action 1: Monitor stock
                    Recommendation rMon = new Recommendation();
                    rMon.setFoodItemId(item.getId());
                    rMon.setFoodItemName(item.getName());
                    rMon.setStatus(Recommendation.Status.PENDING);
                    rMon.setCategory(Recommendation.Category.IMPORTANT);
                    rMon.setRiskLevel(Recommendation.RiskLevel.MEDIUM);
                    rMon.setTitle("Monitor stock for " + item.getName());
                    rMon.setTitleEn(rMon.getTitle());
                    rMon.setTitleMy(item.getName() + " လက်ကျန်ကို စောင့်ကြည့်ပါ");
                    rMon.setDescription(String.format("Item expires in %d days. Monitor stock turnover closely to avoid sudden accumulation.",
                            Math.max(1, expiryDays)));
                    rMon.setDescriptionEn(rMon.getDescription());
                    rMon.setDescriptionMy(String.format("သက်တမ်း %d ရက် ကျန်ရှိသဖြင့် ပစ္စည်းလက်ကျန် အခြေအနေကို စောင့်ကြည့်ပါ။",
                            Math.max(1, expiryDays)));
                    rMon.setReasoningDetails("Prolog Rule: assess_waste_risk/6 (Medium Risk) -> evaluate_priority_use/3 (HIGH_PRIORITY)");
                    rMon.setReasoningDetailsEn(rMon.getReasoningDetails());
                    rMon.setReasoningDetailsMy(translator.translateToMyanmar(rMon.getReasoningDetails()));
                    rMon.setEstimatedSavings(BigDecimal.valueOf(itemSavings).setScale(2, RoundingMode.HALF_UP));
                    saveRecommendation(rMon);
                    generated.add(rMon);

                    // Action 2: Adjust preparation quantity
                    Recommendation rAdj = new Recommendation();
                    rAdj.setFoodItemId(item.getId());
                    rAdj.setFoodItemName(item.getName());
                    rAdj.setStatus(Recommendation.Status.PENDING);
                    rAdj.setCategory(Recommendation.Category.OPTIMIZATION);
                    rAdj.setRiskLevel(Recommendation.RiskLevel.MEDIUM);
                    rAdj.setTitle("Adjust preparation quantity for " + item.getName());
                    rAdj.setTitleEn(rAdj.getTitle());
                    rAdj.setTitleMy(item.getName() + " ပြင်ဆင်ချက်ပြုတ်မှု ပမာဏကို အနည်းငယ် ညှိယူပါ");
                    rAdj.setDescription(String.format("Moderate waste risk detected. Adjust kitchen batch preparation down by 10-15%% according to expected demand (%.1f %s).",
                            assessment.getExpectedDemand(), unit));
                    rAdj.setDescriptionEn(rAdj.getDescription());
                    rAdj.setDescriptionMy(String.format("အလယ်အလတ် အန္တရာယ်ရှိသဖြင့် ခန့်မှန်းဝယ်လိုအား (%.1f %s) အတိုင်း ချက်ပြုတ်မှုပမာဏကို ၁၀-၁၅%% လျှော့ချညှိယူပါ။",
                            assessment.getExpectedDemand(), unit));
                    rAdj.setReasoningDetails("Prolog Rule: recommend_production/6 (Slightly reduce production by 10-15%)");
                    rAdj.setReasoningDetailsEn(rAdj.getReasoningDetails());
                    rAdj.setReasoningDetailsMy(translator.translateToMyanmar(rAdj.getReasoningDetails()));
                    rAdj.setEstimatedSavings(BigDecimal.ZERO);
                    saveRecommendation(rAdj);
                    generated.add(rAdj);

                    // Action 3: Promote usage
                    Recommendation rProm = new Recommendation();
                    rProm.setFoodItemId(item.getId());
                    rProm.setFoodItemName(item.getName());
                    rProm.setStatus(Recommendation.Status.PENDING);
                    rProm.setCategory(Recommendation.Category.OPTIMIZATION);
                    rProm.setRiskLevel(Recommendation.RiskLevel.MEDIUM);
                    rProm.setTitle("Promote usage for " + item.getName());
                    rProm.setTitleEn(rProm.getTitle());
                    rProm.setTitleMy(item.getName() + " သုံးစွဲမှုကို မြှင့်တင်ပါ");
                    rProm.setDescription(String.format("Promote in daily specials or set menus to accelerate inventory drawdown within %d days.",
                            Math.max(1, expiryDays)));
                    rProm.setDescriptionEn(rProm.getDescription());
                    rProm.setDescriptionMy(String.format("သက်တမ်း %d ရက်အတွင်း ကုန်စေရန် မီးဖိုချောင် အထူးဟင်းလျာတွင် ထည့်သွင်းပါ။",
                            Math.max(1, expiryDays)));
                    rProm.setReasoningDetails("Prolog Rule: assess_waste_risk/6 (Medium Risk) -> Promote usage");
                    rProm.setReasoningDetailsEn(rProm.getReasoningDetails());
                    rProm.setReasoningDetailsMy(translator.translateToMyanmar(rProm.getReasoningDetails()));
                    rProm.setEstimatedSavings(BigDecimal.ZERO);
                    saveRecommendation(rProm);
                    generated.add(rProm);
                }
                // 4. LOW RISK ACTIVE ITEM
                else {
                    Recommendation rec = new Recommendation();
                    rec.setFoodItemId(item.getId());
                    rec.setFoodItemName(item.getName());
                    rec.setStatus(Recommendation.Status.PENDING);
                    rec.setCategory(Recommendation.Category.OPTIMIZATION);
                    rec.setRiskLevel(Recommendation.RiskLevel.LOW);
                    rec.setTitle("Maintain normal operation for " + item.getName());
                    rec.setTitleEn(rec.getTitle());
                    rec.setTitleMy(item.getName() + " ပုံမှန်အတိုင်း ဆက်လက်ထုတ်လုပ်သုံးစွဲပါ");
                    rec.setDescription(String.format("Stock (%.1f %s) and demand (%.1f %s) are well-balanced with safe shelf-life (%d days). Maintain standard scheduled production batch and regular replenishment cycle.",
                            assessment.getStock(), unit, assessment.getExpectedDemand(), unit, Math.max(1, expiryDays)));
                    rec.setDescriptionEn(rec.getDescription());
                    rec.setDescriptionMy(String.format("လက်ကျန် (%.1f %s) နှင့် ဝယ်လိုအား (%.1f %s) မျှတပြီး သက်တမ်း %d ရက် ကျန်ရှိသဖြင့် ပုံမှန်အတိုင်း ဆက်လက်လုပ်ဆောင်ပါ။",
                            assessment.getStock(), unit, assessment.getExpectedDemand(), unit, Math.max(1, expiryDays)));
                    rec.setReasoningDetails("Prolog Rule: assess_waste_risk/6 (Low Risk) -> Standard operational parameters");
                    rec.setReasoningDetailsEn(rec.getReasoningDetails());
                    rec.setReasoningDetailsMy(translator.translateToMyanmar(rec.getReasoningDetails()));
                    rec.setEstimatedSavings(BigDecimal.ZERO);
                    saveRecommendation(rec);
                    generated.add(rec);
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
