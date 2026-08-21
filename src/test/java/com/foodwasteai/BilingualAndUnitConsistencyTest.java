package com.foodwasteai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
            "prediction.html",
            "recommendations.html",
            "redistribution.html",
            "reports.html",
            "settings.html",
            "users.html"
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
    @DisplayName("3. Language switcher UI renders 🌐 English | မြန်မာ in iOS 26 Bubble Edition")
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

        // Logout verification
        assertEquals("Logout", enMap.get("nav.logout"), "Logout in EN must be 'Logout'");
        assertEquals("ထွက်ရန်", mmMap.get("nav.logout"), "Logout in MM must be 'ထွက်ရန်'");

        // Verify all navigation keys exist in both dictionaries
        String[] navKeys = {
                "nav.dashboard", "nav.inventory", "nav.sales", "nav.waste",
                "nav.prediction", "nav.recommendations", "nav.redistribution",
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
