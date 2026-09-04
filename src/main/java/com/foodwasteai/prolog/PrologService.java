package com.foodwasteai.prolog;

import com.foodwasteai.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated integration service for SWI-Prolog expert reasoning system.
 * Executes Prolog knowledge base via controlled subprocess with structured output.
 * In production (APP_ENV=production), SWI-Prolog is strictly authoritative:
 * failure or unavailability returns a safe reasoning-unavailable state without
 * generating new redistribution recommendations.
 *
 * Deterministic Java evaluation is isolated solely as a non-authoritative fallback
 * for development/test environments.
 */
public class PrologService {
    private static final Logger logger = LoggerFactory.getLogger(PrologService.class);
    private static File extractedRulesFile = null;
    private static Boolean prologAvailable = null;
    private static Boolean testOverridePrologAvailable = null;

    static {
        initPrologRules();
    }

    /**
     * Initializes and extracts the Prolog rules file to a temporary location if running from JAR.
     */
    private static synchronized void initPrologRules() {
        try {
            // First check direct filesystem path
            File localFile = new File("src/main/resources/prolog/foodwaste_rules.pl");
            if (localFile.exists() && localFile.canRead()) {
                extractedRulesFile = localFile;
                logger.info("Found local Prolog rules file: {}", localFile.getAbsolutePath());
                return;
            }

            // Otherwise extract from classpath resources
            try (InputStream in = PrologService.class.getResourceAsStream("/prolog/foodwaste_rules.pl")) {
                if (in != null) {
                    File tempFile = File.createTempFile("foodwaste_rules_", ".pl");
                    tempFile.deleteOnExit();
                    Files.copy(in, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    extractedRulesFile = tempFile;
                    logger.info("Extracted Prolog rules file to temp: {}", tempFile.getAbsolutePath());
                } else {
                    logger.warn("Prolog rules file '/prolog/foodwaste_rules.pl' not found on classpath.");
                }
            }
        } catch (Exception e) {
            logger.error("Failed to initialize Prolog rules file: {}", e.getMessage(), e);
        }
    }

    /**
     * Testing hook to allow unit and regression tests to simulate Prolog presence/absence.
     */
    public static void setPrologAvailableForTesting(Boolean available) {
        testOverridePrologAvailable = available;
    }

    /**
     * Resets testing override for Prolog availability.
     */
    public static void resetPrologAvailableForTesting() {
        testOverridePrologAvailable = null;
    }

    /**
     * Verifies if SWI-Prolog binary is executable on the current system.
     */
    public static boolean isPrologAvailable() {
        if (testOverridePrologAvailable != null) {
            return testOverridePrologAvailable;
        }

        if (prologAvailable != null) {
            return prologAvailable;
        }

        String swiplPath = AppConfig.getSwiplPath();
        try {
            ProcessBuilder pb = new ProcessBuilder(swiplPath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                logger.info("SWI-Prolog is available at '{}'", swiplPath);
                prologAvailable = true;
                return true;
            }
        } catch (Exception e) {
            logger.warn("SWI-Prolog is NOT available at '{}' ({}). Safe fallback mode active.", swiplPath, e.getMessage());
        }
        prologAvailable = false;
        return false;
    }

    /**
     * Overload for assessing food item defaulting unit to "kg" and isSafe to true.
     */
    public PrologAssessment assessFoodItem(String foodName, double stock, double expectedDemand,
                                          int expiryDays, double histWasteRate, double currentProduction) {
        return assessFoodItem(foodName, "kg", stock, expectedDemand, expiryDays, histWasteRate, currentProduction, true);
    }

    /**
     * Overload for assessing food item with specified unit and defaulting isSafe to true.
     */
    public PrologAssessment assessFoodItem(String foodName, String unit, double stock, double expectedDemand,
                                          int expiryDays, double histWasteRate, double currentProduction) {
        return assessFoodItem(foodName, unit, stock, expectedDemand, expiryDays, histWasteRate, currentProduction, true);
    }

    /**
     * Performs expert system assessment of a single food item.
     * In production, SWI-Prolog is strictly authoritative. If unavailable or fails, returns a safe
     * reasoning-unavailable state without generating synthetic recommendations.
     */
    public PrologAssessment assessFoodItem(String foodName, String unit, double stock, double expectedDemand,
                                          int expiryDays, double histWasteRate, double currentProduction, boolean isSafe) {
        boolean isProd = AppConfig.isProduction();

        if (isPrologAvailable() && extractedRulesFile != null) {
            try {
                return executePrologSubprocess(foodName, unit, stock, expectedDemand, expiryDays, histWasteRate, currentProduction, isSafe);
            } catch (Exception e) {
                logger.error("[PRODUCTION PROLOG FAILURE] SWI-Prolog execution error for item '{}': {}. Refusing to silently fallback to Java in production.",
                        foodName, e.getMessage(), e);
                if (isProd) {
                    return buildProductionUnavailableAssessment(foodName, unit, stock, expectedDemand, expiryDays, histWasteRate, currentProduction);
                }
            }
        } else if (isProd) {
            logger.error("[PRODUCTION PROLOG UNAVAILABLE] SWI-Prolog binary or knowledge base unavailable in production (APP_ENV=production). Returning safe reasoning-unavailable state.");
            return buildProductionUnavailableAssessment(foodName, unit, stock, expectedDemand, expiryDays, histWasteRate, currentProduction);
        }

        // Isolated Development / Test Fallback (Non-authoritative mirror for offline developer machines)
        logger.debug("[DEV SAFE EVALUATION] Using non-authoritative development mirror for item '{}' (APP_ENV={})", foodName, AppConfig.getAppEnv());
        return executeDevelopmentFallback(foodName, unit, stock, expectedDemand, expiryDays, histWasteRate, currentProduction, isSafe);
    }

    /**
     * Dedicated method evaluating redistribution eligibility and candidate status via Prolog rules.
     */
    public PrologAssessment evaluateRedistributionCandidate(String foodName, String unit, double stock, double expectedDemand, int expiryDays) {
        return evaluateRedistributionCandidate(foodName, unit, stock, expectedDemand, expiryDays, true);
    }

    /**
     * Dedicated method evaluating redistribution eligibility with explicit food safety status via Prolog rules.
     */
    public PrologAssessment evaluateRedistributionCandidate(String foodName, String unit, double stock, double expectedDemand, int expiryDays, boolean isSafe) {
        return assessFoodItem(foodName, unit, stock, expectedDemand, expiryDays, 0.05, 0.0, isSafe);
    }

    /**
     * Executes real SWI-Prolog subprocess.
     */
    private PrologAssessment executePrologSubprocess(String foodName, String unit, double stock, double expectedDemand,
                                                    int expiryDays, double histWasteRate, double currentProduction, boolean isSafe) throws Exception {
        String swiplPath = AppConfig.getSwiplPath();
        String rulesPath = extractedRulesFile.getAbsolutePath().replace("\\", "/");

        // Format query: assess_item + evaluate_redistribution_policy
        String goal = String.format(
                "use_module('%s'), " +
                "assess_item(%f, %f, %d, %f, %f, Risk, Reasons, RecProd, RecAction, Priority, Redist), " +
                "Surplus is max(0, %f - %f), " +
                "evaluate_redistribution_policy(%f, %f, %d, Surplus, %s, RedistStatus, RedistPriority, RedistEligible, RedistReasonEn, RedistReasonMy), " +
                "format('RESULT|~w|~w|~w|~w|~w|~w|~w|~w|~w|~w|~w~n', [Risk, Reasons, RecProd, RecAction, Priority, Redist, RedistStatus, RedistPriority, RedistEligible, RedistReasonEn, RedistReasonMy]), halt.",
                rulesPath, stock, expectedDemand, expiryDays, histWasteRate, currentProduction,
                stock, expectedDemand,
                stock, expectedDemand, expiryDays, isSafe ? "true" : "false"
        );

        ProcessBuilder pb = new ProcessBuilder(swiplPath, "-q", "-g", goal);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("SWI-Prolog subprocess timed out");
        }

        return parsePrologOutput(foodName, unit, stock, expectedDemand, expiryDays, histWasteRate, currentProduction, isSafe, output.toString());
    }

    /**
     * Builds safe reasoning-unavailable state when Prolog is unavailable in production.
     * Guarantees zero synthetic donation recommendations are generated without authoritative reasoning.
     */
    private PrologAssessment buildProductionUnavailableAssessment(String foodName, String unit, double stock, double expectedDemand,
                                                                  int expiryDays, double histWasteRate, double currentProduction) {
        PrologAssessment assessment = new PrologAssessment();
        assessment.setFoodName(foodName);
        assessment.setUnit(unit != null ? unit : "kg");
        assessment.setStock(stock);
        assessment.setExpectedDemand(expectedDemand);
        assessment.setExpiryDays(expiryDays);
        assessment.setHistoricalWasteRate(histWasteRate);
        assessment.setEngineUsed("Production Safe Guard (Prolog Reasoning Unavailable)");

        // Safe fallback metrics
        assessment.setRiskLevel("LOW");
        assessment.setRiskScore(0.0);
        assessment.setPredictedWasteQuantity(0.0);
        assessment.setRecommendedProduction(currentProduction);
        assessment.setRecommendedAction("Maintain standard operational inventory control");
        assessment.setRecommendation("Maintain standard operational inventory control");
        assessment.setPriorityUsage("STANDARD");

        // Strictly NO new donation recommendation generated in production when Prolog fails
        assessment.setRedistributionStatus("REASONING_UNAVAILABLE");
        assessment.setRedistributionPriority("NONE");
        assessment.setRecommendRedistribution(false);
        assessment.setRedistributionEligible(false);
        assessment.setProjectedSurplus(0.0);

        assessment.setRedistributionStatusLabelEn("Reasoning Unavailable");
        assessment.setRedistributionStatusLabelMy("ဆန်းစစ်ချက် မရယူနိုင်သေးပါ");

        assessment.setRedistributionReasonEn("Automated redistribution evaluation is temporarily unavailable. Please verify inventory manually.");
        assessment.setRedistributionReasonMy("အလိုအလျောက် ပြန်လည်ဖြန့်ဝေမှု ဆန်းစစ်ချက်ကို လောလောဆယ် မရယူနိုင်သေးပါ။ ကုန်ပစ္စည်းစာရင်းကို ကိုယ်တိုင် စစ်ဆေးပေးပါ။");

        assessment.setRedistributionSuggestedActionEn("Manual kitchen stock inspection required; do not dispatch without manual review.");
        assessment.setRedistributionSuggestedActionMy("မီးဖိုချောင် ကုန်ပစ္စည်းစာရင်းကို ကိုယ်တိုင် စစ်ဆေးပြီးမှသာ ဆောင်ရွက်ပါ။");

        return assessment;
    }

    /**
     * Parses stdout from SWI-Prolog.
     */
    private PrologAssessment parsePrologOutput(String foodName, String unit, double stock, double expectedDemand,
                                              int expiryDays, double histWasteRate, double currentProduction, boolean isSafe, String rawOutput) {
        PrologAssessment assessment = new PrologAssessment();
        assessment.setFoodName(foodName);
        assessment.setUnit(unit != null ? unit : "kg");
        assessment.setStock(stock);
        assessment.setExpectedDemand(expectedDemand);
        assessment.setExpiryDays(expiryDays);
        assessment.setHistoricalWasteRate(histWasteRate);
        assessment.setEngineUsed("SWI-Prolog Expert Engine");

        for (String line : rawOutput.split("\n")) {
            line = line.trim();
            if (line.startsWith("RESULT|")) {
                String[] parts = line.substring(7).split("\\|");
                if (parts.length >= 6) {
                    String risk = parts[0].trim().toUpperCase();

                    // Authoritative risk score matching Prolog rules
                    double riskScore;
                    if (stock <= 0) {
                        risk = "LOW";
                        riskScore = 0.0;
                    } else if (expiryDays < 0) {
                        risk = "HIGH";
                        riskScore = 95.0;
                    } else if (expiryDays <= 1) {
                        risk = "HIGH";
                        riskScore = 85.0;
                    } else if ("HIGH".equalsIgnoreCase(risk)) {
                        if (expectedDemand > 0 && (stock / expectedDemand) >= 1.50) {
                            riskScore = 82.0;
                        } else if (expectedDemand > 0 && (stock / expectedDemand) >= 1.30) {
                            riskScore = 80.0;
                        } else {
                            riskScore = 78.0;
                        }
                    } else if ("MEDIUM".equalsIgnoreCase(risk)) {
                        if (expiryDays <= 3) {
                            riskScore = 55.0;
                        } else if (expectedDemand > 0 && (stock / expectedDemand) >= 1.25) {
                            riskScore = 50.0;
                        } else {
                            riskScore = 45.0;
                        }
                    } else {
                        risk = "LOW";
                        riskScore = 18.0;
                    }
                    assessment.setRiskLevel(risk);
                    assessment.setRiskScore(riskScore);

                    // Parse reasons array from Prolog list notation [a, b]
                    String reasonsRaw = parts[1].trim();
                    if (reasonsRaw.startsWith("[") && reasonsRaw.endsWith("]")) {
                        reasonsRaw = reasonsRaw.substring(1, reasonsRaw.length() - 1);
                        String[] reasonTokens = reasonsRaw.split("',\\s*'");
                        for (String token : reasonTokens) {
                            token = token.replace("'", "").trim();
                            if (!token.isEmpty()) {
                                assessment.addReason(token);
                            }
                        }
                    }

                    try {
                        assessment.setRecommendedProduction(Double.parseDouble(parts[2].trim()));
                    } catch (NumberFormatException e) {
                        assessment.setRecommendedProduction(currentProduction);
                    }

                    String recAction = parts[3].trim().replace("'", "");
                    assessment.setRecommendedAction(recAction);
                    assessment.setRecommendation(recAction);
                    assessment.setPriorityUsage(parts[4].trim().replace("'", ""));

                    double surplus = Math.max(0.0, stock - expectedDemand);
                    assessment.setProjectedSurplus(surplus);

                    // Parse authoritative redistribution evaluation from Prolog
                    String redistStatus;
                    String redistPriority;
                    boolean isRedistEligible;
                    String redistReasonEn;
                    String redistReasonMy;

                    if (parts.length >= 11) {
                        redistStatus = parts[6].trim().replace("'", "");
                        redistPriority = parts[7].trim().replace("'", "");
                        isRedistEligible = "true".equalsIgnoreCase(parts[8].trim());
                        redistReasonEn = parts[9].trim().replace("'", "");
                        redistReasonMy = parts[10].trim().replace("'", "");
                    } else {
                        // Fallback derivation if 6-arg format was returned
                        if (stock <= 0) {
                            redistStatus = "OUT_OF_STOCK";
                            redistPriority = "NONE";
                            isRedistEligible = false;
                            redistReasonEn = "Zero remaining stock in inventory. Not eligible for redistribution.";
                            redistReasonMy = "လက်ကျန်ပစ္စည်း မရှိတော့သဖြင့် ပြန်လည်လှူဒါန်းရန် မဖြစ်နိုင်ပါ။";
                        } else if (expiryDays < 0) {
                            redistStatus = "EXPIRED_NOT_FOR_HUMAN_DONATION";
                            redistPriority = "BLOCKED";
                            isRedistEligible = false;
                            redistReasonEn = "Expired food is unsafe for human consumption. Never eligible for human donation. Use disposal workflow.";
                            redistReasonMy = "သက်တမ်းကုန်ဆုံးသွားသော အစားအစာဖြစ်၍ လူသားများ စားသုံးရန် လှူဒါန်းခွင့်မပြုပါ။ စွန့်ပစ်မှု လုပ်ငန်းစဉ်ကို အသုံးပြုပါ။";
                        } else if (!isSafe) {
                            redistStatus = "UNSAFE";
                            redistPriority = "BLOCKED";
                            isRedistEligible = false;
                            redistReasonEn = "Food safety cannot be confirmed. Not eligible for human donation.";
                            redistReasonMy = "အစားအသောက် ဘေးကင်းလုံခြုံမှု မသေချာသဖြင့် လူသားများ စားသုံးရန် လှူဒါန်းခွင့်မပြုပါ။";
                        } else if (surplus <= 0 || stock <= expectedDemand) {
                            redistStatus = "NO_SURPLUS";
                            redistPriority = "NONE";
                            isRedistEligible = false;
                            redistReasonEn = "Expected customer demand absorbs current inventory. No surplus available for external redistribution.";
                            redistReasonMy = "ခန့်မှန်းဝယ်လိုအားနှင့် လက်ကျန်မျှတနေပြီး အပြင်သို့ လှူဒါန်းရန် ပိုလျှံမှုမရှိပါ။";
                        } else if (expiryDays <= 7) {
                            redistStatus = "PRIORITY_DONATION";
                            redistPriority = "HIGH";
                            isRedistEligible = true;
                            redistReasonEn = "Priority donation — redistribute as soon as possible.";
                            redistReasonMy = "ဦးစားပေး လှူဒါန်းရန် — သက်တမ်းကုန်ရန် နီးကပ်နေသောကြောင့် အမြန်ဆုံး ပြန်လည်ဖြန့်ဝေသင့်ပါသည်။";
                        } else if (expiryDays <= 30) {
                            redistStatus = "DONATION_RECOMMENDED";
                            redistPriority = "RECOMMENDED";
                            isRedistEligible = true;
                            redistReasonEn = "Donation recommended.";
                            redistReasonMy = "လှူဒါန်းသင့်ပါသည်။";
                        } else {
                            redistStatus = "NOT_NEEDED_YET";
                            redistPriority = "LOW";
                            isRedistEligible = false;
                            redistReasonEn = "Redistribution is not necessary yet.";
                            redistReasonMy = "လောလောဆယ် လှူဒါန်းရန် မလိုသေးပါ။";
                        }
                    }

                    assessment.setRedistributionStatus(redistStatus);
                    assessment.setRedistributionPriority(redistPriority);
                    assessment.setRedistributionReason(redistReasonEn);
                    assessment.setRedistributionReasonEn(redistReasonEn);
                    assessment.setRedistributionReasonMy(redistReasonMy);
                    assessment.setRecommendRedistribution(isRedistEligible);
                    assessment.setRedistributionEligible(isRedistEligible);

                    applyRedistributionLabelsAndDirectives(assessment, expiryDays);

                    // Calculate bounded predicted waste quantity in actual item units
                    double predictedWaste;
                    if (expiryDays < 0) {
                        predictedWaste = stock;
                    } else if ("HIGH".equalsIgnoreCase(risk)) {
                        if (expiryDays <= 1) {
                            predictedWaste = Math.max(0.0, stock - (expectedDemand * 0.60));
                        } else {
                            predictedWaste = Math.max(0.0, stock - expectedDemand);
                        }
                    } else if ("MEDIUM".equalsIgnoreCase(risk)) {
                        predictedWaste = Math.max(0.0, (stock - expectedDemand) * 0.50);
                    } else {
                        predictedWaste = 0.0;
                    }
                    predictedWaste = Math.max(0.0, Math.min(stock, predictedWaste));
                    assessment.setPredictedWasteQuantity(predictedWaste);

                    return assessment;
                }
            }
        }

        // If stdout format could not be parsed in production, return safe unavailable state
        if (AppConfig.isProduction()) {
            return buildProductionUnavailableAssessment(foodName, unit, stock, expectedDemand, expiryDays, histWasteRate, currentProduction);
        }

        return executeDevelopmentFallback(foodName, unit, stock, expectedDemand, expiryDays, histWasteRate, currentProduction, isSafe);
    }

    /**
     * Isolated development fallback mirroring Prolog rules for non-production environments.
     * Explicitly marked non-authoritative.
     */
    private PrologAssessment executeDevelopmentFallback(String foodName, String unit, double stock, double expectedDemand,
                                                         int expiryDays, double histWasteRate, double currentProduction, boolean isSafe) {
        PrologAssessment assessment = new PrologAssessment();
        assessment.setFoodName(foodName);
        assessment.setUnit(unit != null ? unit : "kg");
        assessment.setStock(stock);
        assessment.setExpectedDemand(expectedDemand);
        assessment.setExpiryDays(expiryDays);
        assessment.setHistoricalWasteRate(histWasteRate);
        assessment.setEngineUsed("Development Fallback (SWI-Prolog rules mirror)");

        List<String> reasons = new ArrayList<>();
        String risk;
        double riskPct;
        double recProd;
        String recAction;
        String priority;

        // Rule evaluation mirroring foodwaste_rules.pl exactly
        if (stock <= 0) {
            risk = "LOW";
            riskPct = 0.0;
            reasons.add("Zero remaining stock in inventory. No active waste risk.");
            recProd = Math.max(0, currentProduction);
            recAction = "No active inventory remaining. Reorder if required.";
            priority = "STANDARD";
        } else if (expiryDays < 0) {
            risk = "HIGH";
            riskPct = 95.0;
            reasons.add("Item has passed expiration date. Do not serve to customers.");
            recProd = 0.0;
            recAction = "Halt production and dispose of expired inventory safely";
            priority = "DISPOSE_OR_COMPOST";
        } else if (expiryDays == 0) {
            risk = "HIGH";
            riskPct = 85.0;
            reasons.add("Product expires today. Immediate consumption or action required.");
            recProd = stock > expectedDemand ? Math.max(0, Math.round(currentProduction * 0.70)) : Math.max(0, Math.round(currentProduction * 0.50));
            recAction = "Reduce production or redistribute immediately";
            priority = "IMMEDIATE_USE";
        } else if (expiryDays == 1) {
            risk = "HIGH";
            riskPct = 85.0;
            reasons.add("Product expires within 24 hours. Immediate action recommended.");
            recProd = stock > expectedDemand ? Math.max(0, Math.round(currentProduction * 0.70)) : Math.max(0, Math.round(currentProduction * 0.50));
            recAction = "Reduce production or redistribute immediately";
            priority = "IMMEDIATE_USE";
        } else if (expectedDemand > 0 && (stock / expectedDemand) >= 1.50 && (expiryDays <= 3 || histWasteRate >= 0.20)) {
            risk = "HIGH";
            riskPct = 82.0;
            if ((stock / expectedDemand) >= 1.30) reasons.add("Stock significantly exceeds expected demand");
            if (expiryDays <= 3) reasons.add("Short remaining shelf life (<= 3 days)");
            if (histWasteRate >= 0.20) reasons.add("High historical waste rate recorded");
            recProd = Math.max(0, Math.round(currentProduction * 0.70));
            recAction = "Reduce production or redistribute immediately";
            priority = expiryDays <= 2 ? "IMMEDIATE_USE" : "HIGH_PRIORITY";
        } else if (expectedDemand > 0 && (stock / expectedDemand) >= 1.30 && (expiryDays <= 2 || histWasteRate >= 0.25)) {
            risk = "HIGH";
            riskPct = 80.0;
            if ((stock / expectedDemand) >= 1.30) reasons.add("Stock significantly exceeds expected demand");
            if (expiryDays <= 3) reasons.add("Short remaining shelf life (<= 3 days)");
            if (histWasteRate >= 0.20) reasons.add("High historical waste rate recorded");
            recProd = Math.max(0, Math.round(currentProduction * 0.70));
            recAction = "Reduce production or redistribute immediately";
            priority = "IMMEDIATE_USE";
        } else if (histWasteRate >= 0.30 && stock > expectedDemand) {
            risk = "HIGH";
            riskPct = 78.0;
            reasons.add("Historical waste rate is critical (>= 30%). Stock exceeds expected demand.");
            recProd = Math.max(0, Math.round(currentProduction * 0.70));
            recAction = "Reduce production or redistribute immediately";
            priority = "HIGH_PRIORITY";
        } else if (expiryDays > 1 && expiryDays <= 3 && stock > 0) {
            risk = "MEDIUM";
            riskPct = 55.0;
            reasons.add("Product expires within 2-3 days. Monitor stock velocity closely.");
            recProd = stock > expectedDemand ? Math.max(0, Math.round(currentProduction * 0.85)) : Math.max(0, Math.round(currentProduction * 0.90));
            recAction = stock > expectedDemand ? "Slightly reduce production by 10-15% and monitor inventory turnover" : "Feature in daily specials to accelerate turnover";
            priority = "HIGH_PRIORITY";
        } else if (expectedDemand > 0 && (stock / expectedDemand) >= 1.25) {
            risk = "MEDIUM";
            riskPct = 50.0;
            reasons.add("Current stock moderately exceeds forecasted demand");
            if (histWasteRate >= 0.15) reasons.add("Historical waste rate indicates slight overproduction");
            recProd = Math.max(0, Math.round(currentProduction * 0.85));
            recAction = "Slightly reduce production by 10-15% and monitor inventory turnover";
            priority = "MODERATE_PRIORITY";
        } else if (histWasteRate >= 0.15 && stock >= expectedDemand) {
            risk = "MEDIUM";
            riskPct = 45.0;
            reasons.add("Moderate historical waste rate recorded (>= 15%). Potential over-ordering pattern.");
            recProd = Math.max(0, Math.round(currentProduction * 0.90));
            recAction = "Feature in daily specials to accelerate turnover";
            priority = "MODERATE_PRIORITY";
        } else {
            risk = "LOW";
            riskPct = 18.0;
            reasons.add("Safe shelf life remaining (> 3 days) and stock is balanced with demand.");
            recProd = stock < expectedDemand ? Math.max(currentProduction, expectedDemand - stock) : currentProduction;
            recAction = stock < expectedDemand ? "Maintain optimal production aligned with customer demand" : "Maintain standard scheduled production batch";
            priority = "STANDARD";
        }

        double surplus = Math.max(0.0, stock - expectedDemand);
        assessment.setProjectedSurplus(surplus);

        // Authoritative Redistribution Policy mirroring evaluate_redistribution_policy/10 exactly
        String redistStatus;
        String redistPriority;
        boolean isRedistEligible;
        String redistReasonEn;
        String redistReasonMy;

        if (stock <= 0) {
            redistStatus = "OUT_OF_STOCK";
            redistPriority = "NONE";
            isRedistEligible = false;
            redistReasonEn = "Zero remaining stock in inventory. Not eligible for redistribution.";
            redistReasonMy = "လက်ကျန်ပစ္စည်း မရှိတော့သဖြင့် ပြန်လည်လှူဒါန်းရန် မဖြစ်နိုင်ပါ။";
        } else if (expiryDays < 0) {
            redistStatus = "EXPIRED_NOT_FOR_HUMAN_DONATION";
            redistPriority = "BLOCKED";
            isRedistEligible = false;
            redistReasonEn = "Expired food is unsafe for human consumption. Never eligible for human donation. Use disposal workflow.";
            redistReasonMy = "သက်တမ်းကုန်ဆုံးသွားသော အစားအစာဖြစ်၍ လူသားများ စားသုံးရန် လှူဒါန်းခွင့်မပြုပါ။ စွန့်ပစ်မှု လုပ်ငန်းစဉ်ကို အသုံးပြုပါ။";
        } else if (!isSafe) {
            redistStatus = "UNSAFE";
            redistPriority = "BLOCKED";
            isRedistEligible = false;
            redistReasonEn = "Food safety cannot be confirmed. Not eligible for human donation.";
            redistReasonMy = "အစားအသောက် ဘေးကင်းလုံခြုံမှု မသေချာသဖြင့် လူသားများ စားသုံးရန် လှူဒါန်းခွင့်မပြုပါ။";
        } else if (surplus <= 0 || stock <= expectedDemand) {
            redistStatus = "NO_SURPLUS";
            redistPriority = "NONE";
            isRedistEligible = false;
            redistReasonEn = "Expected customer demand absorbs current inventory. No surplus available for external redistribution.";
            redistReasonMy = "ခန့်မှန်းဝယ်လိုအားနှင့် လက်ကျန်မျှတနေပြီး အပြင်သို့ လှူဒါန်းရန် ပိုလျှံမှုမရှိပါ။";
        } else if (expiryDays == 0) {
            redistStatus = "PRIORITY_DONATION";
            redistPriority = "HIGH";
            isRedistEligible = true;
            redistReasonEn = "Priority donation — same-day expiry with verified safety and true surplus. Redistribute immediately.";
            redistReasonMy = "ဦးစားပေး လှူဒါန်းရန် — ယနေ့ သက်တမ်းကုန်မည်ဖြစ်ပြီး ဘေးကင်းမှုနှင့် ပိုလျှံမှု စစ်ဆေးပြီးဖြစ်၍ အမြန်ဆုံး လှူဒါန်းသင့်ပါသည်။";
        } else if (expiryDays <= 7) {
            redistStatus = "PRIORITY_DONATION";
            redistPriority = "HIGH";
            isRedistEligible = true;
            redistReasonEn = "Priority donation — redistribute as soon as possible.";
            redistReasonMy = "ဦးစားပေး လှူဒါန်းရန် — သက်တမ်းကုန်ရန် နီးကပ်နေသောကြောင့် အမြန်ဆုံး ပြန်လည်ဖြန့်ဝေသင့်ပါသည်။";
        } else if (expiryDays <= 30) {
            redistStatus = "DONATION_RECOMMENDED";
            redistPriority = "RECOMMENDED";
            isRedistEligible = true;
            redistReasonEn = "Donation recommended.";
            redistReasonMy = "လှူဒါန်းသင့်ပါသည်။";
        } else {
            redistStatus = "NOT_NEEDED_YET";
            redistPriority = "LOW";
            isRedistEligible = false;
            redistReasonEn = "Redistribution is not necessary yet.";
            redistReasonMy = "လောလောဆယ် လှူဒါန်းရန် မလိုသေးပါ။";
        }

        assessment.setRiskLevel(risk);
        assessment.setRiskScore(riskPct);
        assessment.setReasons(reasons);
        assessment.setRecommendedProduction(recProd);
        assessment.setRecommendedAction(recAction);
        assessment.setRecommendation(recAction);
        assessment.setPriorityUsage(priority);
        assessment.setRedistributionStatus(redistStatus);
        assessment.setRedistributionPriority(redistPriority);
        assessment.setRedistributionReason(redistReasonEn);
        assessment.setRedistributionReasonEn(redistReasonEn);
        assessment.setRedistributionReasonMy(redistReasonMy);
        assessment.setRecommendRedistribution(isRedistEligible);
        assessment.setRedistributionEligible(isRedistEligible);

        applyRedistributionLabelsAndDirectives(assessment, expiryDays);

        // Calculate bounded predicted waste quantity in actual item units
        double predictedWaste;
        if (expiryDays < 0) {
            predictedWaste = stock;
        } else if ("HIGH".equalsIgnoreCase(risk)) {
            if (expiryDays <= 1) {
                predictedWaste = Math.max(0.0, stock - (expectedDemand * 0.60));
            } else {
                predictedWaste = Math.max(0.0, stock - expectedDemand);
            }
        } else if ("MEDIUM".equalsIgnoreCase(risk)) {
            predictedWaste = Math.max(0.0, (stock - expectedDemand) * 0.50);
        } else {
            predictedWaste = 0.0;
        }
        predictedWaste = Math.max(0.0, Math.min(stock, predictedWaste));
        assessment.setPredictedWasteQuantity(predictedWaste);

        return assessment;
    }

    private void applyRedistributionLabelsAndDirectives(PrologAssessment assessment, int expiryDays) {
        String status = assessment.getRedistributionStatus();
        switch (status) {
            case "PRIORITY_DONATION":
                assessment.setRedistributionStatusLabelEn("Priority Donation");
                assessment.setRedistributionStatusLabelMy("ဦးစားပေး လှူဒါန်းရန်");
                assessment.setRedistributionSuggestedActionEn("Schedule priority dispatch to registered redistribution partner today.");
                assessment.setRedistributionSuggestedActionMy("မှတ်ပုံတင်ထားသော မိတ်ဖက်အဖွဲ့သို့ ယနေ့ ဦးစားပေး ပို့ဆောင်ရန် စီစဉ်ပါ။");
                if (expiryDays >= 0 && expiryDays <= 7) {
                    if (expiryDays == 0) {
                        assessment.setRedistributionReasonMy("ယနေ့ သက်တမ်းကုန်မည်ဖြစ်ပြီး လက်ရှိလိုအပ်ချက်ထက် ပိုလျှံမှုရှိသောကြောင့် အမြန်ဆုံး လှူဒါန်းရန် သင့်တော်ပါသည်။");
                    } else {
                        assessment.setRedistributionReasonMy(String.format("သက်တမ်းကုန်ရန် %d ရက်သာ ကျန်ပြီး လက်ရှိလိုအပ်ချက်ထက် ပိုလျှံမှုရှိသောကြောင့် ဦးစားပေး လှူဒါန်းရန် သင့်တော်ပါသည်။", expiryDays));
                    }
                }
                break;
            case "DONATION_RECOMMENDED":
                assessment.setRedistributionStatusLabelEn("Donation Recommended");
                assessment.setRedistributionStatusLabelMy("လှူဒါန်းသင့်");
                assessment.setRedistributionSuggestedActionEn("Review redistribution options and schedule dispatch before expiry.");
                assessment.setRedistributionSuggestedActionMy("သက်တမ်းမကုန်မီ ပြန်လည်လှူဒါန်းနိုင်မည့် အစီအစဉ်များကို ကြိုတင်သုံးသပ်ပါ။");
                if (expiryDays >= 8 && expiryDays <= 30) {
                    assessment.setRedistributionReasonMy(String.format("သက်တမ်းကုန်ရန် %d ရက် ကျန်ပြီး လိုအပ်ချက်ထက် ပိုလျှံမှုရှိသောကြောင့် လှူဒါန်းရန် အကြံပြုပါသည်။", expiryDays));
                }
                break;
            case "NOT_NEEDED_YET":
                assessment.setRedistributionStatusLabelEn("Not Needed Yet");
                assessment.setRedistributionStatusLabelMy("လောလောဆယ် လှူဒါန်းရန် မလိုသေး");
                assessment.setRedistributionSuggestedActionEn("No immediate action required. Standard storage and FIFO rotation.");
                assessment.setRedistributionSuggestedActionMy("လောလောဆယ် ဆောင်ရွက်ရန် မလိုသေးပါ။ ပုံမှန် သိုလှောင်၍ FIFO စနစ်ဖြင့် သုံးစွဲပါ။");
                if (expiryDays > 30) {
                    assessment.setRedistributionReasonMy(String.format("သက်တမ်းကုန်ရန် %d ရက် ကျန်ရှိသဖြင့် လောလောဆယ် ပြန်လည်လှူဒါန်းရန် မလိုသေးပါ။", expiryDays));
                }
                break;
            case "EXPIRED_NOT_FOR_HUMAN_DONATION":
                assessment.setRedistributionStatusLabelEn("Expired - Unsafe for Donation");
                assessment.setRedistributionStatusLabelMy("သက်တမ်းကုန်ဆုံး - လှူဒါန်းရန်မသင့်");
                assessment.setRedistributionSuggestedActionEn("Do not donate. Review item for safe disposal or waste logging.");
                assessment.setRedistributionSuggestedActionMy("လှူဒါန်းခြင်းမပြုပါနှင့်။ ဘေးကင်းစွာ စွန့်ပစ်ရန် သို့မဟုတ် စွန့်ပစ်ပစ္စည်းအဖြစ် မှတ်တမ်းတင်ပါ။");
                break;
            case "UNSAFE":
                assessment.setRedistributionStatusLabelEn("Unsafe for Donation");
                assessment.setRedistributionStatusLabelMy("လှူဒါန်းရန် မသင့်ပါ");
                assessment.setRedistributionSuggestedActionEn("Conduct food safety check or dispose safely.");
                assessment.setRedistributionSuggestedActionMy("အစားအသောက် ဘေးကင်းရေး ပြန်လည်စစ်ဆေးပါ သို့မဟုတ် ဘေးကင်းစွာ စွန့်ပစ်ပါ။");
                break;
            case "OUT_OF_STOCK":
                assessment.setRedistributionStatusLabelEn("Out of Stock");
                assessment.setRedistributionStatusLabelMy("လက်ကျန် မရှိပါ");
                assessment.setRedistributionSuggestedActionEn("No active inventory remaining. Reorder if required.");
                assessment.setRedistributionSuggestedActionMy("လက်ကျန်ပစ္စည်း မရှိပါ။ လိုအပ်ပါက အသစ်မှာယူပါ။");
                break;
            case "NO_SURPLUS":
            default:
                assessment.setRedistributionStatusLabelEn("No Surplus");
                assessment.setRedistributionStatusLabelMy("ပိုလျှံမှု မရှိပါ");
                assessment.setRedistributionSuggestedActionEn("Standard kitchen menu utilization; maintain normal stock monitoring.");
                assessment.setRedistributionSuggestedActionMy("မီးဖိုချောင်တွင် ပုံမှန်အသုံးပြုပြီး စတော့ကို စောင့်ကြည့်ပါ။");
                break;
        }
    }
}
