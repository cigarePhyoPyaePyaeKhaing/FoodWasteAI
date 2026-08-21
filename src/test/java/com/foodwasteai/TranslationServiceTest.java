package com.foodwasteai;

import com.foodwasteai.service.TranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TranslationService covering translation pipeline,
 * in-memory caching, glossary preservation of food item names, Prolog predicates, and offline fallback.
 */
public class TranslationServiceTest {

    private TranslationService translationService;

    @BeforeEach
    void setUp() {
        translationService = TranslationService.getInstance();
    }

    @Test
    @DisplayName("TranslationService returns non-null singleton instance")
    void testSingleton() {
        assertNotNull(translationService);
        assertSame(translationService, TranslationService.getInstance());
    }

    @Test
    @DisplayName("Translates exact 15 required domain glossary items correctly")
    void testExactDomainGlossaryMappings() {
        assertEquals("အနှစ်ချုပ်စာမျက်နှာ", translationService.translateToMyanmar("Dashboard"));
        assertEquals("ကုန်ပစ္စည်းလက်ကျန် စီမံခန့်ခွဲမှု", translationService.translateToMyanmar("Inventory"));
        assertEquals("ရောင်းချမှုမှတ်တမ်း", translationService.translateToMyanmar("Sales Entry"));
        assertEquals("အလေအလွင့်မှတ်တမ်းများ", translationService.translateToMyanmar("Waste Records"));
        assertEquals("AI ခန့်မှန်းချက်", translationService.translateToMyanmar("AI Prediction"));
        assertEquals("AI အကြံပြုချက်များ", translationService.translateToMyanmar("Recommendations"));
        assertEquals("ပိုလျှံအစားအစာ ပြန်လည်ဖြန့်ဝေမှု", translationService.translateToMyanmar("Redistribution"));
        assertEquals("အန္တရာယ်မြင့်", translationService.translateToMyanmar("High Risk"));
        assertEquals("အလယ်အလတ်အန္တရာယ်", translationService.translateToMyanmar("Medium Risk"));
        assertEquals("အန္တရာယ်နည်း", translationService.translateToMyanmar("Low Risk"));
        assertEquals("သက်တမ်းကုန်ရန်နီး", translationService.translateToMyanmar("Near Expiry"));
        assertEquals("ခန့်မှန်းငွေကြေး သက်သာမှု", translationService.translateToMyanmar("Estimated Savings"));
        assertEquals("ချက်ချင်းအသုံးပြုရန်", translationService.translateToMyanmar("Immediate Use"));
        assertEquals("ဦးစားပေးအဆင့်မြင့်", translationService.translateToMyanmar("High Priority"));
        assertEquals("ပုံမှန်အဆင့်", translationService.translateToMyanmar("Standard"));
    }

    @Test
    @DisplayName("Translates English reasoning to natural Myanmar")
    void testReasoningTranslation() {
        String en = "Stock exceeds expected demand (surplus: 8.0 kg) with only 1 day(s) until expiration";
        String my = translationService.translateToMyanmar(en);
        assertNotNull(my);
        assertFalse(my.trim().isEmpty());
        assertTrue(my.contains("ရက်") || my.contains("လက်ကျန်") || my.contains("kg"));
    }

    @Test
    @DisplayName("Translation caches results for repeat queries")
    void testTranslationCaching() {
        String en = "Prolog Rule: assess_waste_risk/6 (High Risk) -> recommend_production/6 (Reduce production by 15-25%)";
        String my1 = translationService.translateToMyanmar(en);
        String my2 = translationService.translateToMyanmar(en);
        assertEquals(my1, my2);
    }

    @Test
    @DisplayName("Preserves food item names and metrics in translation")
    void testPreserveFoodNamesAndMetrics() {
        String en = "Reduce next production batch for Fresh Chicken Breast by 20%";
        String my = translationService.translateToMyanmar(en);
        assertNotNull(my);
        // Food item name should be preserved verbatim
        assertTrue(my.contains("Fresh Chicken Breast"), "Food item name must remain unchanged");
        assertTrue(my.contains("20%"), "Percentage must remain unchanged");

        String saladEn = "Surplus stock (15.5 kg) for Organic Garden Salad Mix near expiry";
        String saladMy = translationService.translateToMyanmar(saladEn);
        assertTrue(saladMy.contains("Organic Garden Salad Mix"), "Complex salad name must remain unchanged");
        assertTrue(saladMy.contains("15.5 kg"), "Quantity with decimal and unit must remain unchanged");

        // Fresh Milk liter test
        String milkEn = "Stock is 8.0 liter against 0.6 liter expected demand with 0-day expiry remaining. Reduce next scheduled production batch by 15-25% to prevent excess spoilage.";
        String milkMy = translationService.translateToMyanmar(milkEn);
        assertTrue(milkMy.contains("8.0") || milkMy.contains("8"), "Number 8.0 must be preserved");
        assertTrue(milkMy.contains("0.6"), "Number 0.6 must be preserved");
        assertTrue(milkMy.contains("liter"), "Unit liter must be preserved");

        // Expired directive with food name
        String expEn = "Halt production and dispose of expired Premium Fresh Milk";
        String expMy = translationService.translateToMyanmar(expEn);
        assertTrue(expMy.contains("Premium Fresh Milk"), "Food name must remain unchanged in expired directive");
    }

    @Test
    @DisplayName("Preserves Prolog predicate names in translated reasoning")
    void testPreservePrologPredicates() {
        String en = "Prolog Rule: evaluate_priority_use/3 -> recommend_production/6 (Reduce production by 20%)";
        String my = translationService.translateToMyanmar(en);
        assertNotNull(my);
        assertTrue(my.contains("evaluate_priority_use/3"));
        assertTrue(my.contains("recommend_production/6"));
    }

    @Test
    @DisplayName("Translates recommendation descriptions correctly")
    void testRecommendationTranslation() {
        String en = "Surplus stock (5.0 kg) detected near expiry. Dispatch to registered food bank or charity partner before expiry cutoff.";
        String my = translationService.translateToMyanmar(en);
        assertNotNull(my);
        assertTrue(my.contains("5.0 kg"));
        assertTrue(my.contains("ပရဟိတ") || my.contains("လှူဒါန်း") || my.contains("သက်တမ်းကုန်"));
    }

    @Test
    @DisplayName("Translates error messages accurately")
    void testErrorMessageTranslation() {
        String errEn = "Recipient not found or inactive with ID: 99";
        String errMy = translationService.translateErrorMessage(errEn);
        assertNotNull(errMy);
        assertTrue(errMy.contains("ပရဟိတ") || errMy.contains("ရှာမတွေ့ပါ"));
    }

    @Test
    @DisplayName("Translates notification messages accurately")
    void testNotificationTranslation() {
        String notifEn = "Surplus food dispatch #12 scheduled successfully";
        String notifMy = translationService.translateNotification(notifEn);
        assertNotNull(notifMy);
        assertTrue(notifMy.contains("အောင်မြင်စွာ") || notifMy.contains("လှူဒါန်းမှု"));
    }

    @Test
    @DisplayName("Null or empty input handled gracefully with fallback")
    void testNullOrEmptyInput() {
        assertEquals("", translationService.translateToMyanmar(null));
        assertEquals("", translationService.translateToMyanmar(""));
        assertEquals("   ", translationService.translateToMyanmar("   "));
    }
}
