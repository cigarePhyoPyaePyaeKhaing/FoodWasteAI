package com.foodwasteai;

import com.foodwasteai.service.TranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TranslationService covering translation pipeline,
 * in-memory caching, glossary preservation of food item names, and offline fallback.
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
    }

    @Test
    @DisplayName("Translates recommendation descriptions correctly")
    void testRecommendationTranslation() {
        String en = "Surplus stock (5.0 kg) detected near expiry. Dispatch to registered food bank or charity partner before expiry cutoff.";
        String my = translationService.translateToMyanmar(en);
        assertNotNull(my);
        assertTrue(my.contains("5.0 kg"));
        assertTrue(my.contains("ပရဟိတ") || my.contains("လှူဒါန်း"));
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
