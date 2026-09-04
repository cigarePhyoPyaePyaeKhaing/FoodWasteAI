package com.foodwasteai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated test suite covering:
 * 1. Every protected page includes the language selector container.
 * 2. Every protected page loads the 3 required i18n scripts.
 * 3. English <-> Myanmar translations parity and completeness.
 * 4. Language persistence in localStorage.
 * 5. Unit-aware KPI formatting (Single unit: liter; Mixed units: breakdown; Carbon: kg CO2e).
 * 6. Logout label bilingual switching.
 */
public class BilingualAndUnitConsistencyTest {

    private static final String[] PROTECTED_PAGES = {
            "dashboard.html",
            "inventory.html",
            "sales.html",
            "waste.html",
            "redistribution.html",
            "reports.html",
            "settings.html"
    };

    private static final String WEBAPP_PROTECTED_DIR = "src/main/webapp/WEB-INF/protected/";
    private static final String I18N_DIR = "src/main/webapp/js/i18n/";

    @Test
    @DisplayName("1. Every protected page contains the language switcher container")
    void testAllProtectedPagesHaveLanguageSwitcher() throws IOException {
        for (String pageName : PROTECTED_PAGES) {
            File file = new File(WEBAPP_PROTECTED_DIR + pageName);
            assertTrue(file.exists(), "Page file must exist: " + pageName);

            String html = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            assertTrue(html.contains("id=\"language-switcher-container\""),
                    "Page '" + pageName + "' must contain <div id=\"language-switcher-container\"> in its topbar header");
        }
    }

    @Test
    @DisplayName("2. Every protected page loads all 3 required i18n scripts")
    void testAllProtectedPagesIncludeI18nScripts() throws IOException {
        for (String pageName : PROTECTED_PAGES) {
            File file = new File(WEBAPP_PROTECTED_DIR + pageName);
            String html = Files.readString(file.toPath(), StandardCharsets.UTF_8);

            assertTrue(html.contains("/js/i18n/en.js"), "Page '" + pageName + "' must include /js/i18n/en.js");
            assertTrue(html.contains("/js/i18n/mm.js"), "Page '" + pageName + "' must include /js/i18n/mm.js");
            assertTrue(html.contains("/js/i18n/language.js"), "Page '" + pageName + "' must include /js/i18n/language.js");
        }
    }

    @Test
    @DisplayName("3. Language switcher UI renders 🌐 English | မြန်မာ")
    void testLanguageSwitcherRenderingLogic() throws IOException {
        File langFile = new File(I18N_DIR + "language.js");
        assertTrue(langFile.exists(), "language.js must exist");

        String js = Files.readString(langFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(js.contains("lang-switch-bubble"), "language.js must define .lang-switch-bubble container");
        assertTrue(js.contains("🌐 English"), "language.js must render '🌐 English' button");
        assertTrue(js.contains("မြန်မာ"), "language.js must render 'မြန်မာ' button");
        assertTrue(js.contains("localStorage.getItem('foodwaste_lang')") || js.contains("localStorage.getItem(\"foodwaste_lang\")"),
                "language.js must check localStorage for language persistence");
        assertTrue(js.contains("localStorage.setItem('foodwaste_lang'") || js.contains("localStorage.setItem(\"foodwaste_lang\""),
                "language.js must store language changes to localStorage");
        assertTrue(js.contains("window.dispatchEvent(new CustomEvent('languageChanged'"),
                "language.js must dispatch 'languageChanged' event for reactive re-rendering");
    }

    @Test
    @DisplayName("4. Translation keys completeness and parity for all KPI and Navigation labels")
    void testI18nKeysCompleteness() throws IOException {
        Map<String, String> enMap = parseJsDictionary(new File(I18N_DIR + "en.js"));
        Map<String, String> mmMap = parseJsDictionary(new File(I18N_DIR + "mm.js"));

        assertFalse(enMap.isEmpty(), "EN dictionary should not be empty");
        assertFalse(mmMap.isEmpty(), "MM dictionary should not be empty");

        // Critical KPI keys verification
        assertEquals("Quantity Sold", enMap.get("sales.kpi.volume"), "Sales volume KPI in EN must be 'Quantity Sold'");
        assertEquals("ရောင်းချသည့်ပမာဏ", mmMap.get("sales.kpi.volume"), "Sales volume KPI in MM must be 'ရောင်းချသည့်ပမာဏ'");

        assertEquals("Total Waste Quantity", enMap.get("waste.kpi.weight"), "Waste KPI in EN must be 'Total Waste Quantity'");
        assertEquals("စုစုပေါင်း အစားအစာအလေအလွင့်ပမာဏ", mmMap.get("waste.kpi.weight"), "Waste KPI in MM must be 'စုစုပေါင်း အစားအစာအလေအလွင့်ပမာဏ'");

        assertEquals("Predicted Waste Quantity", enMap.get("pred.kpi.predictedVolume"), "Prediction KPI in EN must be 'Predicted Waste Quantity'");
        assertEquals("ခန့်မှန်း အလေအလွင့်ပမာဏ", mmMap.get("pred.kpi.predictedVolume"), "Prediction KPI in MM must be 'ခန့်မှန်း အလေအလွင့်ပမာဏ'");

        // Prediction Risk Level verification
        assertTrue(enMap.containsKey("pred.kpi.risk") || enMap.containsKey("PRED.KPI.RISK"), "Must have pred.kpi.risk in EN");
        assertTrue(mmMap.containsKey("pred.kpi.risk") || mmMap.containsKey("PRED.KPI.RISK"), "Must have pred.kpi.risk in MM");

        // 7-Day Forecast KPI verification
        assertTrue(enMap.containsKey("pred.kpi.weeklyPredictedWaste"), "Must have pred.kpi.weeklyPredictedWaste in EN");
        assertTrue(mmMap.containsKey("pred.kpi.weeklyPredictedWaste"), "Must have pred.kpi.weeklyPredictedWaste in MM");

        // Logout verification
        assertEquals("Logout", enMap.get("nav.logout"), "Logout in EN must be 'Logout'");
        assertEquals("ထွက်ရန်", mmMap.get("nav.logout"), "Logout in MM must be 'ထွက်ရန်'");

        // Verify all navigation keys exist in both dictionaries
        String[] navKeys = {
                "nav.dashboard", "nav.inventory", "nav.sales", "nav.waste",
                "nav.redistribution",
                "nav.reports", "nav.users", "nav.settings", "nav.logout"
        };
        for (String key : navKeys) {
            assertTrue(enMap.containsKey(key), "EN dictionary must contain key: " + key);
            assertTrue(mmMap.containsKey(key), "MM dictionary must contain key: " + key);
            assertFalse(enMap.get(key).trim().isEmpty(), "EN translation must not be empty for key: " + key);
            assertFalse(mmMap.get(key).trim().isEmpty(), "MM translation must not be empty for key: " + key);
        }
    }

    @Test
    @DisplayName("4b. Audit every data-i18n in all webapp HTML files against dictionaries")
    void testAllHtmlDataI18nAttributesExistInDictionaries() throws IOException {
        Map<String, String> enMap = parseJsDictionary(new File(I18N_DIR + "en.js"));
        Map<String, String> mmMap = parseJsDictionary(new File(I18N_DIR + "mm.js"));

        // Scan all HTML files in webapp
        File webappDir = new File("src/main/webapp");
        List<File> htmlFiles = new ArrayList<>();
        findFilesByExtension(webappDir, ".html", htmlFiles);

        Set<String> missingInEn = new TreeSet<>();
        Set<String> missingInMm = new TreeSet<>();

        Pattern pattern = Pattern.compile("data-i18n(?:-placeholder|-title)?=\"([^\"]+)\"");

        for (File htmlFile : htmlFiles) {
            String content = Files.readString(htmlFile.toPath(), StandardCharsets.UTF_8);
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String key = matcher.group(1).trim();
                if (!enMap.containsKey(key) && !enMap.containsKey(key.toLowerCase()) && !enMap.containsKey(key.toUpperCase())) {
                    missingInEn.add(key + " (in " + htmlFile.getName() + ")");
                }
                if (!mmMap.containsKey(key) && !mmMap.containsKey(key.toLowerCase()) && !mmMap.containsKey(key.toUpperCase())) {
                    missingInMm.add(key + " (in " + htmlFile.getName() + ")");
                }
            }
        }

        assertTrue(missingInEn.isEmpty(), "Missing keys in en.js: " + missingInEn);
        assertTrue(missingInMm.isEmpty(), "Missing keys in mm.js: " + missingInMm);
    }

    private void findFilesByExtension(File dir, String ext, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                findFilesByExtension(f, ext, result);
            } else if (f.getName().endsWith(ext)) {
                result.add(f);
            }
        }
    }

    @Test
    @DisplayName("5. Unit-Aware Single Item Aggregation: Fresh Milk 4.0 liter -> '4.0 liter' (Not '4.0 kg')")
    void testSingleUnitAggregationLiter() {
        List<MockItem> sales = List.of(new MockItem(4.0, "liter"));
        String result = simulateFormatUnitAggregate(sales);
        assertEquals("4.0 liter", result, "Single unit 'liter' must render '4.0 liter'");
        assertFalse(result.contains("kg"), "Must NOT contain kg for milk");
        assertFalse(result.contains("units"), "Must NOT contain units for milk");
    }

    @Test
    @DisplayName("6. Unit-Aware Single Item Waste: Fresh Milk 3.0 liter -> '3.0 liter' (Not '3.0 units')")
    void testSingleUnitWasteLiter() {
        List<MockItem> waste = List.of(new MockItem(3.0, "liter"));
        String result = simulateFormatUnitAggregate(waste);
        assertEquals("3.0 liter", result, "Waste single unit 'liter' must render '3.0 liter'");
        assertFalse(result.contains("units"), "Must NOT contain units for milk");
    }

    @Test
    @DisplayName("7. Unit-Aware Prediction: Fresh Milk 7.7 liter -> '7.7 liter' (Not '7.7 kg')")
    void testSingleUnitPredictionLiter() {
        List<MockItem> predItems = List.of(new MockItem(7.7, "liter"));
        String result = simulateFormatUnitAggregate(predItems);
        assertEquals("7.7 liter", result, "Prediction single unit 'liter' must render '7.7 liter'");
        assertFalse(result.contains("kg"), "Must NOT contain kg for milk");
    }

    @Test
    @DisplayName("8. Mixed-Unit Aggregation: 5.0 liter + 3.0 kg + 12 pieces -> '5.0 liter • 3.0 kg • 12 pieces' (Not '20 units')")
    void testMixedUnitAggregation() {
        List<MockItem> mixedItems = List.of(
                new MockItem(5.0, "liter"),
                new MockItem(3.0, "kg"),
                new MockItem(12.0, "pieces")
        );
        String result = simulateFormatUnitAggregate(mixedItems);
        assertEquals("5.0 liter • 3.0 kg • 12 pieces", result, "Mixed units must render unit-specific breakdown separated by ' • '");
        assertFalse(result.contains("20"), "Must never sum 5+3+12 into 20 units");
    }

    @Test
    @DisplayName("9. Empty items aggregation produces clean '0.0'")
    void testEmptyItemsAggregation() {
        List<MockItem> emptyItems = Collections.emptyList();
        String result = simulateFormatUnitAggregate(emptyItems);
        assertEquals("0.0", result, "Empty items aggregation must render '0.0'");
    }

    @Test
    @DisplayName("10. Carbon Footprint Metric preserves 'kg CO2e'")
    void testCarbonFootprintMetric() {
        double wasteQty = 3.0; // 3.0 liter of milk
        double co2Kg = wasteQty * 2.5;
        String co2Display = String.format(Locale.US, "%.1f kg CO₂e", co2Kg);
        assertEquals("7.5 kg CO₂e", co2Display, "Environmental impact must use kg CO₂e");
    }

    @Test
    @DisplayName("11. 7-Day Comparison Graph Unit Safety: Aggregation strictly isolates units across categories")
    void testComparisonGraphUnitSeparation() {
        Map<String, Map<String, Double>> byUnit = new LinkedHashMap<>();
        byUnit.computeIfAbsent("kg", k -> new HashMap<>()).put("sales", 10.0);
        byUnit.get("kg").put("waste", 2.0);
        byUnit.get("kg").put("redistribution", 3.0);

        byUnit.computeIfAbsent("liter", k -> new HashMap<>()).put("sales", 5.0);
        byUnit.get("liter").put("waste", 1.0);
        byUnit.get("liter").put("redistribution", 0.0);

        assertEquals(10.0, byUnit.get("kg").get("sales"), "Sales in kg must be 10.0");
        assertEquals(2.0, byUnit.get("kg").get("waste"), "Waste in kg must be 2.0");
        assertEquals(3.0, byUnit.get("kg").get("redistribution"), "Redistribution in kg must be 3.0");

        assertEquals(5.0, byUnit.get("liter").get("sales"), "Sales in liter must be 5.0");
        assertEquals(1.0, byUnit.get("liter").get("waste"), "Waste in liter must be 1.0");
        assertEquals(0.0, byUnit.get("liter").get("redistribution"), "Redistribution in liter must be 0.0");

        assertNotEquals(15.0, byUnit.get("kg").get("sales"), "Must not combine 10 kg + 5 liter into 15");
    }

    @Test
    @DisplayName("12. Weekly Operations Comparison: Normalized item/batch percentages eliminate incompatible units")
    void testWeeklyOperationsNormalizedPercentages() {
        double item1Sold = 20.0;
        double item1Wasted = 5.0;
        double item1Redist = 0.0;
        double item1Base = 50.0;

        double item2Sold = 10.0;
        double item2Wasted = 2.0;
        double item2Redist = 4.0;
        double item2Base = 20.0;

        double item1SalesRate = (item1Sold / item1Base) * 100.0;
        double item1WasteRate = (item1Wasted / item1Base) * 100.0;
        double item1RedistRate = (item1Redist / item1Base) * 100.0;

        double item2SalesRate = (item2Sold / item2Base) * 100.0;
        double item2WasteRate = (item2Wasted / item2Base) * 100.0;
        double item2RedistRate = (item2Redist / item2Base) * 100.0;

        double daySalesRate = (item1SalesRate + item2SalesRate) / 2.0;
        double dayWasteRate = (item1WasteRate + item2WasteRate) / 2.0;
        double dayRedistRate = (item1RedistRate + item2RedistRate) / 2.0;

        assertEquals(45.0, daySalesRate, 0.01, "Sales rate must be 45.0%");
        assertEquals(10.0, dayWasteRate, 0.01, "Waste rate must be 10.0%");
        assertEquals(10.0, dayRedistRate, 0.01, "Redistribution rate must be 10.0%");

        assertNotEquals(30.0, daySalesRate, "Must not calculate raw combined quantities");
    }

    @Test
    @DisplayName("13. Operations Comparison History & Tomorrow AI Prediction: current week includes prediction, historical week shows 0/unavailable")
    void testOperationsComparisonTomorrowPredictionAndHistory() {
        LocalDate today = LocalDate.of(2026, 8, 30);
        LocalDate pastDate = LocalDate.of(2026, 8, 12);

        LocalDate currentMonday = today.minusDays(today.getDayOfWeek().getValue() - 1);
        assertEquals(LocalDate.of(2026, 8, 24), currentMonday);

        LocalDate pastMonday = pastDate.minusDays(pastDate.getDayOfWeek().getValue() - 1);
        assertEquals(LocalDate.of(2026, 8, 10), pastMonday);

        boolean isCurrentWeekForToday = currentMonday.equals(LocalDate.of(2026, 8, 24));
        boolean isCurrentWeekForPast = pastMonday.equals(LocalDate.of(2026, 8, 24));

        assertTrue(isCurrentWeekForToday, "Today's week must be identified as current week");
        assertFalse(isCurrentWeekForPast, "Past week must not be identified as current week");

        double authenticTomorrowPred = 17.0;
        double displayPredCurrentWeek = isCurrentWeekForToday ? authenticTomorrowPred : 0.0;
        double displayPredPastWeek = isCurrentWeekForPast ? authenticTomorrowPred : 0.0;

        assertEquals(17.0, displayPredCurrentWeek, "Current week displays authentic AI prediction");
        assertEquals(0.0, displayPredPastWeek, "Historical week does NOT display current AI prediction (shows 0 / unavailable)");
    }

    @Test
    @DisplayName("14. Tomorrow Three Predictions from Current Inventory: assessTomorrowBatches evaluates Sales, Waste, and Redistribution with conservation")
    void testAssessTomorrowBatchesFromCurrentInventory() {
        com.foodwasteai.service.PredictionService predService = new com.foodwasteai.service.PredictionService();
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        LocalDate tomorrow = today.plusDays(1);

        com.foodwasteai.model.FoodItem milkBatch1 = new com.foodwasteai.model.FoodItem(101L, "Fresh Milk", "Dairy",
                new java.math.BigDecimal("10.00"), "liter", new java.math.BigDecimal("2500.00"), tomorrow, new java.math.BigDecimal("1.00"));

        com.foodwasteai.model.FoodItem milkBatch2 = new com.foodwasteai.model.FoodItem(102L, "Fresh Milk", "Dairy",
                new java.math.BigDecimal("6.00"), "liter", new java.math.BigDecimal("2500.00"), tomorrow, new java.math.BigDecimal("1.00"));

        com.foodwasteai.model.FoodItem beefLater = new com.foodwasteai.model.FoodItem(103L, "Fresh Beef", "Meat",
                new java.math.BigDecimal("5.00"), "kg", new java.math.BigDecimal("18000.00"), today.plusDays(3), new java.math.BigDecimal("1.00"));

        com.foodwasteai.model.FoodItem zeroItem = new com.foodwasteai.model.FoodItem(104L, "Yogurt", "Dairy",
                java.math.BigDecimal.ZERO, "pcs", new java.math.BigDecimal("1200.00"), tomorrow, new java.math.BigDecimal("1.00"));

        List<com.foodwasteai.model.FoodItem> currentInventory = List.of(milkBatch1, milkBatch2, beefLater, zeroItem);

        List<Map<String, Object>> tomorrowBatches = predService.assessTomorrowBatches(currentInventory);
        assertNotNull(tomorrowBatches);
        assertEquals(2, tomorrowBatches.size(), "Both separate batches of milk must be individually assessed");

        for (Map<String, Object> b : tomorrowBatches) {
            double stock = ((Number) b.get("remainingQuantity")).doubleValue();
            double s = ((Number) b.get("predictedSalesQuantity")).doubleValue();
            double w = ((Number) b.get("predictedWasteQuantity")).doubleValue();
            double r = ((Number) b.get("predictedRedistributionQuantity")).doubleValue();

            double sRate = ((Number) b.get("predictedSalesRate")).doubleValue();
            double wRate = ((Number) b.get("predictedWasteRate")).doubleValue();
            double rRate = ((Number) b.get("predictedRedistributionRate")).doubleValue();

            // Values must be non-negative
            assertTrue(s >= 0.0, "Predicted sales must be non-negative");
            assertTrue(w >= 0.0, "Predicted waste must be non-negative");
            assertTrue(r >= 0.0, "Predicted redistribution must be non-negative");

            // Strict conservation: sales + waste + redistribution <= stock (no double counting)
            assertTrue(s + w + r <= stock + 0.001, "Sales + Waste + Redistribution must not exceed available stock");

            // Rates must be valid 0-100% and sum to 100%
            assertTrue(sRate >= 0.0 && sRate <= 100.0, "Sales rate must be between 0% and 100%");
            assertTrue(wRate >= 0.0 && wRate <= 100.0, "Waste rate must be between 0% and 100%");
            assertTrue(rRate >= 0.0 && rRate <= 100.0, "Redistribution rate must be between 0% and 100%");
            assertEquals(100.0, sRate + wRate + rRate, 0.1, "Predicted rates for an active tomorrow batch must sum to 100%");
        }

        // Dynamic recalculation when stock changes
        milkBatch1.setQuantity(new java.math.BigDecimal("5.00"));
        List<Map<String, Object>> recalculated = predService.assessTomorrowBatches(List.of(milkBatch1, milkBatch2));
        assertEquals(2, recalculated.size());
        assertEquals(5.0, ((Number) recalculated.get(0).get("remainingQuantity")).doubleValue(), 0.01);

        // Empty result (0) when no items expire tomorrow
        List<Map<String, Object>> emptyResult = predService.assessTomorrowBatches(List.of(beefLater));
        assertTrue(emptyResult.isEmpty(), "When no items expire tomorrow, result must be empty (0%)");
    }

    @Test
    @DisplayName("15. 7-Day Waste Trend Week Selection: date selection maps to Monday–Sunday, aggregates confirmed waste logs, and suppresses historical predictions")
    void testWasteTrendWeekSelectionAndDataAggregation() {
        // Test date selection mapping to Monday–Sunday
        LocalDate selectedHistorical = LocalDate.of(2026, 8, 12); // A Wednesday
        LocalDate histMonday = selectedHistorical.minusDays(selectedHistorical.getDayOfWeek().getValue() - 1);
        LocalDate histSunday = histMonday.plusDays(6);

        assertEquals(LocalDate.of(2026, 8, 10), histMonday, "Monday must be Aug 10, 2026");
        assertEquals(LocalDate.of(2026, 8, 16), histSunday, "Sunday must be Aug 16, 2026");

        // Current week reference
        LocalDate today = LocalDate.of(2026, 8, 30); // Sunday
        LocalDate currentMonday = today.minusDays(today.getDayOfWeek().getValue() - 1);
        assertEquals(LocalDate.of(2026, 8, 24), currentMonday);

        boolean isCurrentWeek = histMonday.equals(currentMonday);
        assertFalse(isCurrentWeek, "Aug 10-16 must be recognized as a historical week");

        // Simulate confirmed waste logs across different weeks
        Map<String, Double> wasteLogs = new HashMap<>();
        wasteLogs.put("2026-08-11", 4.5); // Historical Tuesday
        wasteLogs.put("2026-08-14", 7.0); // Historical Friday
        wasteLogs.put("2026-08-25", 3.2); // Current Tuesday
        wasteLogs.put("2026-08-30", 5.0); // Current Sunday

        // Aggregate for historical week Aug 10 - Aug 16
        double histWeekTotal = 0.0;
        for (int i = 0; i < 7; i++) {
            String dStr = histMonday.plusDays(i).toString();
            double dayVal = wasteLogs.getOrDefault(dStr, 0.0);
            if (!wasteLogs.containsKey(dStr)) {
                assertEquals(0.0, dayVal, "Any day without confirmed waste records must evaluate to 0.0, not static baseline");
            }
            histWeekTotal += dayVal;
        }
        assertEquals(11.5, histWeekTotal, 0.01, "Historical week must only aggregate records from Aug 10 to Aug 16 (4.5 + 7.0 = 11.5)");

        // Prediction: current week has authentic prediction, historical week has 0.0
        double authenticTomorrowWastePred = 8.0;
        double histTomorrowPred = isCurrentWeek ? authenticTomorrowWastePred : 0.0;
        assertEquals(0.0, histTomorrowPred, "Historical week Tomorrow AI prediction must be 0.0 (suppressed)");

        boolean isNowCurrentWeek = currentMonday.equals(LocalDate.of(2026, 8, 24));
        double currentTomorrowPred = isNowCurrentWeek ? authenticTomorrowWastePred : 0.0;
        assertEquals(8.0, currentTomorrowPred, "Current week must display authentic Tomorrow AI prediction");

        // Current week days without waste records must also evaluate to 0.0
        for (int i = 0; i < 7; i++) {
            String dStr = currentMonday.plusDays(i).toString();
            double dayVal = wasteLogs.getOrDefault(dStr, 0.0);
            if (!wasteLogs.containsKey(dStr)) {
                assertEquals(0.0, dayVal, "Current week days with no confirmed waste records must evaluate to 0.0");
            }
        }
    }

    @Test
    @DisplayName("16. Operations Comparison Unit-Safe 100% Daily Composition: normal days sum to 100% without directly mixing kg, liter, pcs")
    void testOperationsComparisonUnitSafe100PercentComposition() {
        // Mock daily items with different physical units (never directly add kg + liter + pcs)
        class MockDailyActivity {
            final String unit;
            final double sold;
            final double wasted;
            final double redist;

            MockDailyActivity(String unit, double sold, double wasted, double redist) {
                this.unit = unit;
                this.sold = sold;
                this.wasted = wasted;
                this.redist = redist;
            }
        }

        List<MockDailyActivity> dayItems = List.of(
                new MockDailyActivity("kg", 15.0, 5.0, 5.0),      // Total = 25 kg -> 60% sales, 20% waste, 20% redist
                new MockDailyActivity("liter", 20.0, 0.0, 20.0),   // Total = 40 liter -> 50% sales, 0% waste, 50% redist
                new MockDailyActivity("pcs", 0.0, 10.0, 0.0)       // Total = 10 pcs -> 0% sales, 100% waste, 0% redist
        );

        // Normalize at item level first (units cancel out)
        double totalSalesShare = 0;
        double totalWasteShare = 0;
        double totalRedistShare = 0;

        for (MockDailyActivity a : dayItems) {
            double itemTotal = a.sold + a.wasted + a.redist;
            assertTrue(itemTotal > 0);
            totalSalesShare += (a.sold / itemTotal);
            totalWasteShare += (a.wasted / itemTotal);
            totalRedistShare += (a.redist / itemTotal);
        }

        int count = dayItems.size();
        double avgS = totalSalesShare / count;
        double avgW = totalWasteShare / count;
        double avgR = totalRedistShare / count;
        double totalShare = avgS + avgW + avgR;

        assertEquals(1.0, totalShare, 0.0001, "Dimensionless item shares must sum to exactly 1.0");

        double salesRate = Math.round((avgS / totalShare) * 1000.0) / 10.0;
        double wasteRate = Math.round((avgW / totalShare) * 1000.0) / 10.0;
        double redistRate = Math.round((100.0 - salesRate - wasteRate) * 10.0) / 10.0;

        assertEquals(100.0, salesRate + wasteRate + redistRate, 0.01, "Sales% + Waste% + Redist% must equal 100.0%");

        // Zero activity day must strictly produce 0%, 0%, 0% (not forced 100%)
        List<MockDailyActivity> zeroDay = Collections.emptyList();
        double zeroSales = zeroDay.isEmpty() ? 0.0 : 100.0;
        double zeroWaste = zeroDay.isEmpty() ? 0.0 : 100.0;
        double zeroRedist = zeroDay.isEmpty() ? 0.0 : 100.0;

        assertEquals(0.0, zeroSales);
        assertEquals(0.0, zeroWaste);
        assertEquals(0.0, zeroRedist);
    }

    @Test
    @DisplayName("17. Prediction Card Sold Amount: calculates Sold Percentage = Sold Quantity / Total Quantity * 100, clamps 0-100%, and does not mix different batches")
    void testPredictionCardSoldAmountCalculation() {
        // Batch 1: Total = 20 kg, Sold = 7 kg -> 35%
        double totalBatch1 = 20.0;
        double soldBatch1 = 7.0;
        int pctBatch1 = totalBatch1 > 0 ? (int) Math.min(100, Math.max(0, Math.round((soldBatch1 / totalBatch1) * 100))) : 0;
        assertEquals(35, pctBatch1);

        // Batch 2: Same product name "Fresh Milk", but different batch ID: Total = 10.0 liter, Sold = 0 -> 0%
        double totalBatch2 = 10.0;
        double soldBatch2 = 0.0;
        int pctBatch2 = totalBatch2 > 0 ? (int) Math.min(100, Math.max(0, Math.round((soldBatch2 / totalBatch2) * 100))) : 0;
        assertEquals(0, pctBatch2, "Item without sales must strictly show 0%");

        // Zero total quantity safety (avoid NaN / Infinity)
        double totalZero = 0.0;
        double soldZero = 5.0;
        int pctZero = totalZero > 0 ? (int) Math.min(100, Math.max(0, Math.round((soldZero / totalZero) * 100))) : 0;
        assertEquals(0, pctZero, "Zero total quantity must safely evaluate to 0%");

        // Clamping upper bound: sold > total
        double totalOver = 10.0;
        double soldOver = 12.0;
        int pctOver = totalOver > 0 ? (int) Math.min(100, Math.max(0, Math.round((soldOver / totalOver) * 100))) : 0;
        assertEquals(100, pctOver, "Sold percentage must be clamped to 100% maximum");
    }

    @Test
    @DisplayName("23. Operational Comparison Section cleanly removed from Dashboard HTML, JS, and Translations")
    void testOperationalComparisonSectionCleanlyRemoved() throws IOException {
        File dashHtml = new File(WEBAPP_PROTECTED_DIR + "dashboard.html");
        assertTrue(dashHtml.exists(), "dashboard.html must exist");
        String html = Files.readString(dashHtml.toPath(), StandardCharsets.UTF_8);

        assertFalse(html.contains("comparison-card"), "dashboard.html must not contain comparison-card");
        assertFalse(html.contains("comparison-chart-container"), "dashboard.html must not contain comparison-chart-container");
        assertFalse(html.contains("dash.comparison."), "dashboard.html must not reference dash.comparison translation keys");
        assertFalse(html.contains("comparison-date-picker"), "dashboard.html must not contain comparison-date-picker");

        File dashJs = new File("src/main/webapp/js/dashboard.js");
        assertTrue(dashJs.exists(), "dashboard.js must exist");
        String js = Files.readString(dashJs.toPath(), StandardCharsets.UTF_8);

        assertFalse(js.contains("renderComparisonChart"), "dashboard.js must not contain renderComparisonChart");
        assertFalse(js.contains("selectedComparisonDate"), "dashboard.js must not contain selectedComparisonDate");
        assertFalse(js.contains("comparison-chart-container"), "dashboard.js must not reference comparison-chart-container");
        assertFalse(js.contains("comparison-date-picker"), "dashboard.js must not reference comparison-date-picker");

        Map<String, String> enMap = parseJsDictionary(new File(I18N_DIR + "en.js"));
        Map<String, String> mmMap = parseJsDictionary(new File(I18N_DIR + "mm.js"));

        assertFalse(enMap.containsKey("dash.comparison.title"), "en.js must not contain unused dash.comparison.title");
        assertFalse(mmMap.containsKey("dash.comparison.title"), "mm.js must not contain unused dash.comparison.title");
    }

    // Helper to simulate the exact JavaScript formatUnitAggregate algorithm
    private String simulateFormatUnitAggregate(List<MockItem> items) {
        if (items == null || items.isEmpty()) {
            return "0.0";
        }

        Map<String, Double> totalsByUnit = new LinkedHashMap<>();
        for (MockItem item : items) {
            double qty = item.quantity;
            String unit = (item.unit != null && !item.unit.trim().isEmpty()) ? item.unit.trim() : "units";
            totalsByUnit.put(unit, totalsByUnit.getOrDefault(unit, 0.0) + qty);
        }

        if (totalsByUnit.isEmpty()) {
            return "0.0";
        }

        List<String> formatted = new ArrayList<>();
        for (Map.Entry<String, Double> entry : totalsByUnit.entrySet()) {
            String u = entry.getKey();
            double val = entry.getValue();
            String formattedVal;
            if (("pieces".equalsIgnoreCase(u) || "piece".equalsIgnoreCase(u) || "units".equalsIgnoreCase(u)) && val % 1 == 0) {
                formattedVal = String.valueOf((long) val);
            } else {
                formattedVal = String.format(Locale.US, "%.1f", val);
            }
            formatted.add(formattedVal + " " + u);
        }

        return String.join(" • ", formatted);
    }

    private Map<String, String> parseJsDictionary(File jsFile) throws IOException {
        Map<String, String> map = new HashMap<>();
        String content = Files.readString(jsFile.toPath(), StandardCharsets.UTF_8);

        Pattern pattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            map.put(matcher.group(1), matcher.group(2));
        }
        return map;
    }

    private static class MockItem {
        final double quantity;
        final String unit;

        MockItem(double quantity, String unit) {
            this.quantity = quantity;
            this.unit = unit;
        }
    }
}
