package com.foodwasteai;

import com.foodwasteai.model.Recommendation;
import com.foodwasteai.service.TranslationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test verifying dynamic AI recommendation translation:
 * - Bilingual fields (title_en/title_my, description_en/description_my, reasoning_en/reasoning_my).
 * - Exact Myanmar translations for production dynamic strings.
 * - Units (liter, kg, pieces) and numbers preserved.
 * - Technical Prolog predicates preserved verbatim.
 * - Fallbacks never expose raw translation keys.
 */
public class DynamicRecommendationBilingualTest {

    private final TranslationService translator = TranslationService.getInstance();

    @Test
    @DisplayName("1. Fresh Milk recommendation description translates fluently to Myanmar while preserving liter")
    void testFreshMilkRecommendationDescriptionMyanmar() {
        String enDesc = "Stock is 8.0 liter against 0.6 liter expected demand with 0-day expiry remaining. Reduce next scheduled production batch by 15-25% to prevent excess spoilage.";
        String myDesc = translator.translateToMyanmar(enDesc);

        assertNotNull(myDesc);
        assertNotEquals(enDesc, myDesc, "Myanmar mode must not return untouched English");
        assertTrue(myDesc.contains("liter"), "Unit 'liter' must be preserved: " + myDesc);
        assertTrue(myDesc.contains("8.0") || myDesc.contains("8"), "Number 8.0 must be preserved: " + myDesc);
        assertTrue(myDesc.contains("0.6"), "Number 0.6 must be preserved: " + myDesc);
        assertTrue(myDesc.contains("၁၅-၂၅%") || myDesc.contains("15-25%"), "Percentage must be translated or preserved: " + myDesc);
        assertTrue(myDesc.contains("လျှော့ချ") || myDesc.contains("ကာကွယ်"), "Must contain natural Myanmar terms: " + myDesc);
    }

    @Test
    @DisplayName("2. Recommendation titles translate accurately to Myanmar")
    void testRecommendationTitlesMyanmar() {
        String t1 = "Reduce next production batch for Fresh Milk";
        String t1My = translator.translateToMyanmar(t1);
        assertTrue(t1My.contains("Fresh Milk") && t1My.contains("လျှော့ချပါ"), "Fresh Milk title must translate: " + t1My);

        String t2 = "Halt production and dispose of expired Fresh Milk";
        String t2My = translator.translateToMyanmar(t2);
        assertTrue(t2My.contains("Fresh Milk") && (t2My.contains("သက်တမ်းကုန်ဆုံး") || t2My.contains("စွန့်ပစ်")), "Expired title must translate: " + t2My);

        String t3 = "Redistribute excess inventory for Fresh Chicken Breast";
        String t3My = translator.translateToMyanmar(t3);
        assertTrue(t3My.contains("Fresh Chicken Breast") && (t3My.contains("လှူဒါန်း") || t3My.contains("ပိုလျှံ")), "Redistribution title must translate: " + t3My);

        String t4 = "Prioritize usage today for Organic Eggs";
        String t4My = translator.translateToMyanmar(t4);
        assertTrue(t4My.contains("Organic Eggs") && (t4My.contains("ဦးစားပေး") || t4My.contains("သုံးစွဲ")), "Priority usage title must translate: " + t4My);
    }

    @Test
    @DisplayName("3. Prolog rules preserve technical predicates verbatim in Myanmar mode")
    void testPrologPredicatesPreservedInMyanmarMode() {
        String p1 = "Prolog Rule: assess_waste_risk/6 (High Risk) -> recommend_production/6 (Reduce production by 15-25%)";
        String p1My = translator.translateToMyanmar(p1);
        assertTrue(p1My.contains("assess_waste_risk/6"), "Predicate assess_waste_risk/6 must be preserved");
        assertTrue(p1My.contains("recommend_production/6"), "Predicate recommend_production/6 must be preserved");
        assertTrue(p1My.contains("Prolog စည်းမျဉ်း"), "Must translate label to Myanmar");

        String p2 = "Prolog Rule: evaluate_redistribution/6 -> Verified surplus eligible for emergency charity donation";
        String p2My = translator.translateToMyanmar(p2);
        assertTrue(p2My.contains("evaluate_redistribution/6"), "Predicate evaluate_redistribution/6 must be preserved");

        String p3 = "Prolog Rule: assess_waste_risk/6 (High Risk: Expired) -> evaluate_priority_use/3 (DISPOSE_OR_COMPOST)";
        String p3My = translator.translateToMyanmar(p3);
        assertTrue(p3My.contains("assess_waste_risk/6") && p3My.contains("evaluate_priority_use/3"), "Both predicates must be preserved");
    }

    @Test
    @DisplayName("4. Recommendation model returns localized fields based on requested language")
    void testRecommendationModelLanguageAccess() {
        Recommendation r = new Recommendation();
        r.setTitleEn("Reduce next production batch for Fresh Milk");
        r.setTitleMy("Fresh Milk အတွက် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို လျှော့ချပါ");
        r.setDescriptionEn("Stock is 8.0 liter against 0.6 liter expected demand.");
        r.setDescriptionMy("လက်ကျန် 8.0 liter ရှိပြီး ခန့်မှန်းဝယ်လိုအား 0.6 liter သာရှိပါသည်။");
        r.setReasoningDetailsEn("Prolog Rule: assess_waste_risk/6");
        r.setReasoningDetailsMy("Prolog စည်းမျဉ်း: assess_waste_risk/6");

        // English Mode
        assertEquals("Reduce next production batch for Fresh Milk", r.getTitle("en"));
        assertEquals("Stock is 8.0 liter against 0.6 liter expected demand.", r.getDescription("en"));
        assertEquals("Prolog Rule: assess_waste_risk/6", r.getReasoningDetails("en"));

        // Myanmar Mode
        assertEquals("Fresh Milk အတွက် နောက်တစ်ကြိမ် ထုတ်လုပ်မှုပမာဏကို လျှော့ချပါ", r.getTitle("mm"));
        assertEquals("လက်ကျန် 8.0 liter ရှိပြီး ခန့်မှန်းဝယ်လိုအား 0.6 liter သာရှိပါသည်။", r.getDescription("mm"));
        assertEquals("Prolog စည်းမျဉ်း: assess_waste_risk/6", r.getReasoningDetails("mm"));

        // Fallback when Myanmar is empty
        Recommendation rEmptyMy = new Recommendation();
        rEmptyMy.setTitleEn("English Title");
        assertEquals("English Title", rEmptyMy.getTitle("mm"), "Missing Myanmar should fall back to English title");
        assertFalse(rEmptyMy.getTitle("mm").startsWith("KEY_") || rEmptyMy.getTitle("mm").startsWith("pred."), "Never show raw translation keys");
    }
}
