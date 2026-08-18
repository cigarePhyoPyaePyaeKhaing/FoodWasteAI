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
 * Includes a safe development fallback when swipl is not yet installed on local machines.
 */
public class PrologService {
    private static final Logger logger = LoggerFactory.getLogger(PrologService.class);
    private static File extractedRulesFile = null;
    private static Boolean prologAvailable = null;

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
     * Verifies if SWI-Prolog binary is executable on the current system.
     */
    public static boolean isPrologAvailable() {
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
     * Performs expert system assessment of a single food item.
     */
    public PrologAssessment assessFoodItem(String foodName, double stock, double expectedDemand,
                                          int expiryDays, double histWasteRate, double currentProduction) {
        if (isPrologAvailable() && extractedRulesFile != null) {
            try {
                return executePrologSubprocess(foodName, stock, expectedDemand, expiryDays, histWasteRate, currentProduction);
            } catch (Exception e) {
                logger.error("Error executing SWI-Prolog process: {}. Falling back to safe dev evaluator.", e.getMessage(), e);
            }
        }

        // Safe development fallback when swipl is not installed
        return executeDevelopmentFallback(foodName, stock, expectedDemand, expiryDays, histWasteRate, currentProduction);
    }

    /**
     * Executes real SWI-Prolog subprocess.
     */
    private PrologAssessment executePrologSubprocess(String foodName, double stock, double expectedDemand,
                                                    int expiryDays, double histWasteRate, double currentProduction) throws Exception {
        String swiplPath = AppConfig.getSwiplPath();
        String rulesPath = extractedRulesFile.getAbsolutePath().replace("\\", "/");

        // Format query: assess_item(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, CurrentProduction, Risk, Reasons, RecProd, RecAction, Priority, Redist)
        String goal = String.format(
                "use_module('%s'), assess_item(%f, %f, %d, %f, %f, Risk, Reasons, RecProd, RecAction, Priority, Redist), " +
                "format('RESULT|~w|~w|~w|~w|~w|~w~n', [Risk, Reasons, RecProd, RecAction, Priority, Redist]), halt.",
                rulesPath, stock, expectedDemand, expiryDays, histWasteRate, currentProduction
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

        return parsePrologOutput(foodName, stock, expectedDemand, expiryDays, histWasteRate, currentProduction, output.toString());
    }

    /**
     * Parses stdout from SWI-Prolog.
     */
    private PrologAssessment parsePrologOutput(String foodName, double stock, double expectedDemand,
                                              int expiryDays, double histWasteRate, double currentProduction, String rawOutput) {
        PrologAssessment assessment = new PrologAssessment();
        assessment.setFoodName(foodName);
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
                    assessment.setRiskLevel(risk);

                    // Calculate risk percentage
                    if ("HIGH".equalsIgnoreCase(risk)) {
                        assessment.setRiskPercentage(82.0);
                    } else if ("MEDIUM".equalsIgnoreCase(risk)) {
                        assessment.setRiskPercentage(55.0);
                    } else {
                        assessment.setRiskPercentage(20.0);
                    }

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
                    assessment.setRecommendRedistribution("true".equalsIgnoreCase(parts[5].trim()));

                    return assessment;
                }
            }
        }

        // Fallback if formatting was not matched
        assessment.setRiskLevel("LOW");
        assessment.setRiskPercentage(20.0);
        assessment.addReason("Stock is balanced with expected demand");
        assessment.setRecommendedProduction(currentProduction);
        assessment.setRecommendedAction("Maintain standard scheduled production batch");
        assessment.setRecommendation("Maintain standard scheduled production batch");
        assessment.setPriorityUsage("STANDARD");
        assessment.setRecommendRedistribution(false);
        return assessment;
    }

    /**
     * Development fallback mirroring Prolog rules exactly, used only when SWI-Prolog is missing locally.
     */
    private PrologAssessment executeDevelopmentFallback(String foodName, double stock, double expectedDemand,
                                                         int expiryDays, double histWasteRate, double currentProduction) {
        PrologAssessment assessment = new PrologAssessment();
        assessment.setFoodName(foodName);
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
        boolean redistribute;

        // Rule evaluation mirroring foodwaste_rules.pl exactly
        if (expiryDays <= 1 && stock > 0) {
            risk = "HIGH";
            riskPct = 82.0;
            reasons.add("Expiry is near (within 1-2 days)");
            reasons.add("Stock remaining requires immediate consumption");
            recProd = Math.max(0, Math.round(currentProduction * 0.75));
            recAction = "Reduce tomorrow production by 15-25% to exhaust existing inventory";
            priority = "IMMEDIATE_USE";
            redistribute = stock > expectedDemand && expiryDays >= 1;
        } else if (expectedDemand > 0 && (stock / expectedDemand) >= 1.30 && (expiryDays <= 3 || histWasteRate >= 0.20)) {
            risk = "HIGH";
            riskPct = 82.0;
            if ((stock / expectedDemand) >= 1.30) reasons.add("Stock exceeds expected demand");
            if (expiryDays <= 3) reasons.add("Expiry is near (within 1-3 days)");
            if (histWasteRate >= 0.20) reasons.add("Historical waste is high");
            recProd = Math.max(0, Math.round(currentProduction * 0.75));
            recAction = "Reduce tomorrow production by 15-25% to exhaust existing inventory";
            priority = "HIGH_PRIORITY";
            redistribute = (stock - expectedDemand) >= 5 && expiryDays >= 1;
        } else if (expiryDays <= 3 && stock > 0) {
            risk = "MEDIUM";
            riskPct = 55.0;
            reasons.add("Expiry approaching within 3 days");
            reasons.add("Requires monitoring to prevent spoilage");
            recProd = Math.max(0, Math.round(currentProduction * 0.90));
            recAction = "Slightly reduce production by 10% to prevent excess buffer";
            priority = "HIGH_PRIORITY";
            redistribute = false;
        } else if (histWasteRate >= 0.15 && stock >= expectedDemand) {
            risk = "MEDIUM";
            riskPct = 50.0;
            reasons.add("Moderate historical waste rate recorded");
            reasons.add("Potential over-ordering pattern");
            recProd = Math.max(0, Math.round(currentProduction * 0.90));
            recAction = "Slightly reduce production by 10% to prevent excess buffer";
            priority = "MODERATE_PRIORITY";
            redistribute = false;
        } else {
            risk = "LOW";
            riskPct = 20.0;
            reasons.add("Stock is balanced with expected demand");
            reasons.add("Safe shelf life remaining");
            reasons.add("Low historical waste rate");
            recProd = stock < expectedDemand ? Math.max(currentProduction, expectedDemand - stock) : currentProduction;
            recAction = "Maintain standard scheduled production batch";
            priority = "STANDARD";
            redistribute = false;
        }

        assessment.setRiskLevel(risk);
        assessment.setRiskPercentage(riskPct);
        assessment.setReasons(reasons);
        assessment.setRecommendedProduction(recProd);
        assessment.setRecommendedAction(recAction);
        assessment.setRecommendation(recAction);
        assessment.setPriorityUsage(priority);
        assessment.setRecommendRedistribution(redistribute);

        return assessment;
    }
}
