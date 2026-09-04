package com.foodwasteai;

import com.foodwasteai.dao.FoodItemDao;
import com.foodwasteai.dao.SalesDao;
import com.foodwasteai.dao.WasteRecordDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.prolog.PrologService;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.RedistributionService;
import com.foodwasteai.util.ExpiryStatusResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression and boundary verification test suite for Surplus Food Redistribution Expiry-Window Classification.
 * Validates the strict policy:
 *  - 0–7 days remaining -> PRIORITY_DONATION (HIGH priority)
 *  - 8–30 days remaining -> DONATION_RECOMMENDED (RECOMMENDED priority) [e.g. 15-day Chicken scenario]
 *  - >30 days remaining -> NOT_NEEDED_YET (LOW priority, not actionable yet)
 *  - <0 days remaining (expired) -> EXPIRED_NOT_FOR_HUMAN_DONATION (BLOCKED)
 */
public class RedistributionExpiryClassificationBoundaryTest {

    private PrologService prologService;
    private FoodItemService foodItemService;
    private RedistributionService redistributionService;

    @BeforeEach
    public void setUp() {
        prologService = new PrologService();
        foodItemService = new FoodItemService();
        redistributionService = new RedistributionService();
    }

    @Test
    @DisplayName("Yangon Timezone & Date Math Verification: Chicken 2026-09-17 expiry calculation")
    public void testYangonTimezoneAndChickenExpiryCalculation() {
        ZoneId yangonZone = ExpiryStatusResolver.ZONE_YANGON;
        assertEquals("Asia/Yangon", yangonZone.getId(), "Must strictly use Asia/Yangon timezone");

        LocalDate yangonToday = ExpiryStatusResolver.getToday();
        assertNotNull(yangonToday);

        // Verification with reference date 2026-09-02 and expiry 2026-09-17
        LocalDate refToday = LocalDate.of(2026, 9, 2);
        LocalDate chickenExpiry = LocalDate.of(2026, 9, 17);

        int computedDays = ExpiryStatusResolver.calculateDaysRemaining(chickenExpiry, refToday);
        assertEquals(15, computedDays, "Days between 2026-09-02 and 2026-09-17 must be exactly 15 whole calendar days");

        // 15 days is within the [8, 30] window
        assertTrue(computedDays >= 8 && computedDays <= 30, "15 days must fall strictly into the 8–30 day window");
    }

    @Test
    @DisplayName("Boundary Matrix: today - 1 day (-1) -> EXPIRED_NOT_FOR_HUMAN_DONATION, BLOCKED")
    public void testBoundary_Minus1Day_Expired() {
        PrologAssessment assessment = prologService.evaluateRedistributionCandidate("Generic Food", "kg", 20.0, 5.0, -1, true);
        assertNotNull(assessment);
        assertEquals("EXPIRED_NOT_FOR_HUMAN_DONATION", assessment.getRedistributionStatus());
        assertEquals("BLOCKED", assessment.getRedistributionPriority());
        assertFalse(assessment.isRedistributionEligible());
        assertFalse(assessment.isRecommendRedistribution());
    }

    @Test
    @DisplayName("Boundary Matrix: today + 0 days (0) -> PRIORITY_DONATION, HIGH")
    public void testBoundary_0Days_PriorityDonation() {
        PrologAssessment assessment = prologService.evaluateRedistributionCandidate("Generic Food", "kg", 20.0, 5.0, 0, true);
        assertNotNull(assessment);
        assertEquals("PRIORITY_DONATION", assessment.getRedistributionStatus());
        assertEquals("HIGH", assessment.getRedistributionPriority());
        assertTrue(assessment.isRedistributionEligible());
        assertTrue(assessment.isRecommendRedistribution());
        assertEquals(15.0, assessment.getProjectedSurplus(), 0.01);
    }

    @Test
    @DisplayName("Boundary Matrix: today + 1 day (1) -> PRIORITY_DONATION, HIGH")
    public void testBoundary_1Day_PriorityDonation() {
        PrologAssessment assessment = prologService.evaluateRedistributionCandidate("Generic Food", "kg", 20.0, 5.0, 1, true);
        assertNotNull(assessment);
        assertEquals("PRIORITY_DONATION", assessment.getRedistributionStatus());
        assertEquals("HIGH", assessment.getRedistributionPriority());
        assertTrue(assessment.isRedistributionEligible());
        assertTrue(assessment.isRecommendRedistribution());
    }

    @Test
    @DisplayName("Boundary Matrix: today + 7 days (7) -> PRIORITY_DONATION, HIGH (Upper 7-day boundary)")
    public void testBoundary_7Days_PriorityDonation() {
        PrologAssessment assessment = prologService.evaluateRedistributionCandidate("Generic Food", "kg", 20.0, 5.0, 7, true);
        assertNotNull(assessment);
        assertEquals("PRIORITY_DONATION", assessment.getRedistributionStatus());
        assertEquals("HIGH", assessment.getRedistributionPriority());
        assertTrue(assessment.isRedistributionEligible());
        assertTrue(assessment.isRecommendRedistribution());
    }

    @Test
    @DisplayName("Boundary Matrix: today + 8 days (8) -> DONATION_RECOMMENDED, RECOMMENDED (Lower 8-30 boundary)")
    public void testBoundary_8Days_DonationRecommended() {
        PrologAssessment assessment = prologService.evaluateRedistributionCandidate("Generic Food", "kg", 20.0, 5.0, 8, true);
        assertNotNull(assessment);
        assertEquals("DONATION_RECOMMENDED", assessment.getRedistributionStatus());
        assertEquals("RECOMMENDED", assessment.getRedistributionPriority());
        assertTrue(assessment.isRedistributionEligible());
        assertTrue(assessment.isRecommendRedistribution());
    }

    @Test
    @DisplayName("Boundary Matrix: today + 15 days (15) -> DONATION_RECOMMENDED, RECOMMENDED (Chicken scenario)")
    public void testBoundary_15Days_ChickenScenario_DonationRecommended() {
        PrologAssessment assessment = prologService.evaluateRedistributionCandidate("chicken", "kg", 25.0, 5.0, 15, true);
        assertNotNull(assessment);
        assertEquals("DONATION_RECOMMENDED", assessment.getRedistributionStatus());
        assertEquals("RECOMMENDED", assessment.getRedistributionPriority());
        assertTrue(assessment.isRedistributionEligible());
        assertTrue(assessment.isRecommendRedistribution());
        assertEquals(20.0, assessment.getProjectedSurplus(), 0.01);
        assertFalse("PRIORITY_DONATION".equals(assessment.getRedistributionStatus()), "15-day item must NOT be classified as PRIORITY_DONATION");
        assertFalse("HIGH".equals(assessment.getRedistributionPriority()), "15-day item must NOT have HIGH priority");
    }

    @Test
    @DisplayName("Boundary Matrix: today + 30 days (30) -> DONATION_RECOMMENDED, RECOMMENDED (Upper 8-30 boundary)")
    public void testBoundary_30Days_DonationRecommended() {
        PrologAssessment assessment = prologService.evaluateRedistributionCandidate("Generic Food", "kg", 20.0, 5.0, 30, true);
        assertNotNull(assessment);
        assertEquals("DONATION_RECOMMENDED", assessment.getRedistributionStatus());
        assertEquals("RECOMMENDED", assessment.getRedistributionPriority());
        assertTrue(assessment.isRedistributionEligible());
        assertTrue(assessment.isRecommendRedistribution());
    }

    @Test
    @DisplayName("Boundary Matrix: today + 31 days (31) -> NOT_NEEDED_YET, LOW (Lower boundary beyond 30 days)")
    public void testBoundary_31Days_NotNeededYet() {
        PrologAssessment assessment = prologService.evaluateRedistributionCandidate("Generic Food", "kg", 20.0, 5.0, 31, true);
        assertNotNull(assessment);
        assertEquals("NOT_NEEDED_YET", assessment.getRedistributionStatus());
        assertEquals("LOW", assessment.getRedistributionPriority());
        assertFalse(assessment.isRedistributionEligible());
        assertFalse(assessment.isRecommendRedistribution());
    }

    @Test
    @DisplayName("Boundary Matrix: today + 60 days (60) -> NOT_NEEDED_YET, LOW")
    public void testBoundary_60Days_NotNeededYet() {
        PrologAssessment assessment = prologService.evaluateRedistributionCandidate("Generic Food", "kg", 20.0, 5.0, 60, true);
        assertNotNull(assessment);
        assertEquals("NOT_NEEDED_YET", assessment.getRedistributionStatus());
        assertEquals("LOW", assessment.getRedistributionPriority());
        assertFalse(assessment.isRedistributionEligible());
        assertFalse(assessment.isRecommendRedistribution());
    }

    @Test
    @DisplayName("End-to-End Pipeline: 15-day item is partitioned into redistributionCandidates and NOT priorityCandidates")
    public void testEndToEndCandidatePartitioningFor15DayItem() throws SQLException {
        LocalDate todayYangon = ExpiryStatusResolver.getToday();
        LocalDate expiry15Days = todayYangon.plusDays(15);

        String uniqueName = "Test Chicken Scenario " + System.currentTimeMillis();
        FoodItem item = new FoodItem(null, uniqueName, "Poultry",
                new BigDecimal("25.00"), "kg", new BigDecimal("5000.00"),
                expiry15Days, new BigDecimal("2.00"));
        FoodItem saved = foodItemService.createFoodItem(item, 1L);
        assertNotNull(saved.getId());

        Map<String, Object> candidateMap = redistributionService.evaluateRedistributionCandidates();
        assertNotNull(candidateMap);

        @SuppressWarnings("unchecked")
        List<RedistributionService.CandidateItem> priority = (List<RedistributionService.CandidateItem>) candidateMap.get("priorityCandidates");
        @SuppressWarnings("unchecked")
        List<RedistributionService.CandidateItem> recommended = (List<RedistributionService.CandidateItem>) candidateMap.get("redistributionCandidates");
        @SuppressWarnings("unchecked")
        List<RedistributionService.CandidateItem> notEligible = (List<RedistributionService.CandidateItem>) candidateMap.get("notEligible");

        assertNotNull(priority);
        assertNotNull(recommended);
        assertNotNull(notEligible);

        // Verify it is present in recommended (Recommended Donation section)
        assertTrue(recommended.stream().anyMatch(c -> uniqueName.equals(c.getFoodName())),
                "15-day item must appear in redistributionCandidates (Recommended Donation section)");

        // Verify it is NOT in priority (Priority Donation section)
        assertFalse(priority.stream().anyMatch(c -> uniqueName.equals(c.getFoodName())),
                "15-day item must NEVER appear in priorityCandidates (Priority Donation section)");

        // Verify candidate attributes
        RedistributionService.CandidateItem candidate = recommended.stream()
                .filter(c -> uniqueName.equals(c.getFoodName()))
                .findFirst()
                .orElseThrow();

        assertEquals("DONATION_RECOMMENDED", candidate.getStatus());
        assertEquals("RECOMMENDED", candidate.getPriority());
        assertTrue(candidate.isEligible());
        assertEquals(15, candidate.getExpiryDays());
    }

    @Test
    @DisplayName("Policy Neutrality: Generic classification applies consistently across diverse food categories without name-based hardcoding")
    public void testNoHardcodedFoodNames() {
        String[] sampleNames = {"chicken", "beef", "tofu", "fresh fish", "milk", "vegetables"};
        for (String name : sampleNames) {
            PrologAssessment assessment = prologService.evaluateRedistributionCandidate(name, "kg", 30.0, 10.0, 15, true);
            assertEquals("DONATION_RECOMMENDED", assessment.getRedistributionStatus(),
                    "Item '" + name + "' with 15 days expiry must be DONATION_RECOMMENDED");
            assertEquals("RECOMMENDED", assessment.getRedistributionPriority(),
                    "Item '" + name + "' with 15 days expiry must have RECOMMENDED priority");
            assertTrue(assessment.isRedistributionEligible());
        }
    }
}
