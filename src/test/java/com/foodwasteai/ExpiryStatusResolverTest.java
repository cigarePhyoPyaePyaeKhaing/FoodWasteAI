package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.Recommendation;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.prolog.PrologService;
import com.foodwasteai.service.RecommendationService;
import com.foodwasteai.service.TranslationService;
import com.foodwasteai.util.ExpiryStatusResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test suite verifying:
 * 1. Unified shared expiry status policy (EXPIRED, SAME_DAY_EXPIRY, NEAR_EXPIRY, SAFE).
 * 2. Strict synchronization between UI status, backend resolvers, and SWI-Prolog reasoning.
 * 3. Unit preservation (liter, kg, pieces).
 */
public class ExpiryStatusResolverTest {

    @Test
    @DisplayName("1. Expiry date before today strictly returns EXPIRED with DISPOSE_OR_COMPOST")
    void testExpiryBeforeTodayReturnsExpired() {
        LocalDate today = LocalDate.of(2026, 8, 22);
        LocalDate expiredDate = LocalDate.of(2026, 8, 21); // Yesterday

        ExpiryStatusResolver.ExpiryState state = ExpiryStatusResolver.resolveState(expiredDate, today);
        assertEquals(ExpiryStatusResolver.ExpiryState.EXPIRED, state, "State must be EXPIRED");

        String status = ExpiryStatusResolver.resolveStatus(expiredDate, BigDecimal.valueOf(8.0), BigDecimal.valueOf(5.0), today);
        assertEquals(ExpiryStatusResolver.STATUS_EXPIRED, status, "Database status must be EXPIRED");

        int days = ExpiryStatusResolver.calculateDaysRemaining(expiredDate, today);
        assertTrue(days < 0, "Days remaining must be negative for expired item");

        String action = ExpiryStatusResolver.getStandardAction(state);
        assertTrue(action.toLowerCase().contains("dispose of expired") || action.toLowerCase().contains("halt production"),
                "Action must mandate disposal of expired inventory: " + action);

        String priority = ExpiryStatusResolver.getStandardPriority(state, days);
        assertEquals("DISPOSE_OR_COMPOST", priority);
    }

    @Test
    @DisplayName("2. Expiry date today follows defined SAME_DAY_EXPIRY policy (NEAR_EXPIRY, IMMEDIATE_USE)")
    void testExpiryTodayReturnsSameDayExpiry() {
        LocalDate today = LocalDate.of(2026, 8, 22);
        LocalDate todayExpiry = LocalDate.of(2026, 8, 22);

        ExpiryStatusResolver.ExpiryState state = ExpiryStatusResolver.resolveState(todayExpiry, today);
        assertEquals(ExpiryStatusResolver.ExpiryState.SAME_DAY_EXPIRY, state, "State must be SAME_DAY_EXPIRY");

        String status = ExpiryStatusResolver.resolveStatus(todayExpiry, BigDecimal.valueOf(8.0), BigDecimal.valueOf(5.0), today);
        assertEquals(ExpiryStatusResolver.STATUS_NEAR_EXPIRY, status, "Status for today must be NEAR_EXPIRY");

        int days = ExpiryStatusResolver.calculateDaysRemaining(todayExpiry, today);
        assertEquals(0, days, "Days remaining must be exactly 0");

        assertFalse(ExpiryStatusResolver.isExpired(todayExpiry, today), "Today's item must NOT be classified as expired");
        assertTrue(ExpiryStatusResolver.isSameDayExpiry(todayExpiry, today), "Today's item must be classified as same-day expiry");
        assertTrue(ExpiryStatusResolver.isNearExpiry(todayExpiry, today), "Today's item must be near expiry");

        String priority = ExpiryStatusResolver.getStandardPriority(state, days);
        assertEquals("IMMEDIATE_USE", priority);
    }

    @Test
    @DisplayName("3. Future expiry (1-3 days) returns NEAR_EXPIRY, while >3 days returns SAFE / OK")
    void testFutureExpiryReturnsCorrectStatus() {
        LocalDate today = LocalDate.of(2026, 8, 22);

        // 1 day remaining
        LocalDate d1 = LocalDate.of(2026, 8, 23);
        assertEquals(ExpiryStatusResolver.ExpiryState.NEAR_EXPIRY, ExpiryStatusResolver.resolveState(d1, today));
        assertEquals(ExpiryStatusResolver.STATUS_NEAR_EXPIRY, ExpiryStatusResolver.resolveStatus(d1, BigDecimal.TEN, BigDecimal.ONE, today));

        // 3 days remaining
        LocalDate d3 = LocalDate.of(2026, 8, 25);
        assertEquals(ExpiryStatusResolver.ExpiryState.NEAR_EXPIRY, ExpiryStatusResolver.resolveState(d3, today));
        assertEquals(ExpiryStatusResolver.STATUS_NEAR_EXPIRY, ExpiryStatusResolver.resolveStatus(d3, BigDecimal.TEN, BigDecimal.ONE, today));

        // 5 days remaining (safe)
        LocalDate d5 = LocalDate.of(2026, 8, 27);
        assertEquals(ExpiryStatusResolver.ExpiryState.SAFE, ExpiryStatusResolver.resolveState(d5, today));
        assertEquals(ExpiryStatusResolver.STATUS_OK, ExpiryStatusResolver.resolveStatus(d5, BigDecimal.TEN, BigDecimal.ONE, today));

        // 5 days remaining with low stock -> LOW_STOCK
        assertEquals(ExpiryStatusResolver.STATUS_LOW_STOCK, ExpiryStatusResolver.resolveStatus(d5, BigDecimal.valueOf(1.0), BigDecimal.valueOf(5.0), today));
    }

    @Test
    @DisplayName("4. Prolog reasoning strictly aligns with backend expiry state (Expired item received by Prolog)")
    void testPrologReasoningMatchesBackendExpiryState() {
        PrologService prologService = new PrologService();

        // Expired item (expiryDays = -1)
        PrologAssessment assessmentExpired = prologService.assessFoodItem(
                "Fresh Milk", "liter", 8.0, 0.6, -1, 0.05, 0.0
        );

        assertEquals("HIGH", assessmentExpired.getRiskLevel(), "Expired item must be HIGH risk");
        assertEquals(95.0, assessmentExpired.getRiskScore(), "Expired item risk score must be 95%");
        assertEquals("DISPOSE_OR_COMPOST", assessmentExpired.getPriorityUsage(), "Priority must be DISPOSE_OR_COMPOST");
        assertTrue(assessmentExpired.getRecommendation().toLowerCase().contains("dispose of expired") || assessmentExpired.getRecommendation().toLowerCase().contains("halt production"),
                "Recommendation must mandate disposal: " + assessmentExpired.getRecommendation());
        assertFalse(assessmentExpired.isRecommendRedistribution(), "Expired item must never be recommended for donation");
        assertEquals("liter", assessmentExpired.getUnit(), "Unit must remain 'liter'");
    }

    @Test
    @DisplayName("5. Unit preservation across liters, kg, pieces")
    void testUnitsPreservation() {
        PrologService prologService = new PrologService();

        PrologAssessment aLiter = prologService.assessFoodItem("Fresh Milk", "liter", 8.0, 1.0, 2, 0.05, 2.0);
        assertEquals("liter", aLiter.getUnit());

        PrologAssessment aKg = prologService.assessFoodItem("Chicken Breast", "kg", 15.0, 5.0, 2, 0.10, 5.0);
        assertEquals("kg", aKg.getUnit());

        PrologAssessment aPieces = prologService.assessFoodItem("Organic Eggs", "pieces", 60.0, 10.0, 5, 0.02, 10.0);
        assertEquals("pieces", aPieces.getUnit());
    }
}
