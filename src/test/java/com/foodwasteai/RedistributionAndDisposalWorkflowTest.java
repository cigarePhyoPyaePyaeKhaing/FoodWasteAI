package com.foodwasteai;

import com.foodwasteai.dao.WasteRecordDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.Redistribution;
import com.foodwasteai.model.WasteRecord;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.prolog.PrologService;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.RedistributionService;
import com.foodwasteai.service.WasteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for:
 * 1. Authoritative SWI-Prolog Expiry-Based Surplus Food Redistribution Policy
 * 2. 0-7 days Priority Donation, 8-30 days Recommended Donation, >30 days Not Needed Yet
 * 3. Expired food blocked from human donation
 * 4. Disposal workflow with explicit user confirmation
 */
public class RedistributionAndDisposalWorkflowTest {

    private PrologService prologService;
    private RedistributionService redistributionService;
    private FoodItemService foodItemService;
    private WasteService wasteService;

    @BeforeEach
    public void setUp() {
        prologService = new PrologService();
        foodItemService = new FoodItemService();
        wasteService = new WasteService(new WasteRecordDao(), foodItemService);
        redistributionService = new RedistributionService();
    }

    @Test
    @DisplayName("Policy 1: Expiry 0-7 days (e.g. 0, 1, 7 days) + True Surplus -> PRIORITY_DONATION, HIGH priority, Eligible")
    public void testPolicy_0to7Days_PriorityDonation() {
        // Day 0 (Same day expiry with confirmed safety)
        PrologAssessment day0 = prologService.evaluateRedistributionCandidate("Cooked Rice", "kg", 20.0, 5.0, 0, true);
        assertNotNull(day0);
        assertEquals("PRIORITY_DONATION", day0.getRedistributionStatus());
        assertEquals("HIGH", day0.getRedistributionPriority());
        assertTrue(day0.isRedistributionEligible());
        assertEquals(15.0, day0.getProjectedSurplus(), 0.01);
        assertNotNull(day0.getRedistributionReasonEn());
        assertNotNull(day0.getRedistributionReasonMy());
        assertTrue(day0.getRedistributionReasonEn().toLowerCase().contains("priority donation") || day0.getRedistributionReasonEn().toLowerCase().contains("redistribute"));
        assertTrue(day0.getRedistributionReasonMy().contains("ဦးစားပေး") || day0.getRedistributionReasonMy().contains("လှူဒါန်း"));

        // Day 1 (Tomorrow expiry)
        PrologAssessment day1 = prologService.evaluateRedistributionCandidate("Fresh Bread", "loaves", 18.0, 5.0, 1, true);
        assertNotNull(day1);
        assertEquals("PRIORITY_DONATION", day1.getRedistributionStatus());
        assertEquals("HIGH", day1.getRedistributionPriority());
        assertTrue(day1.isRedistributionEligible());
        assertEquals(13.0, day1.getProjectedSurplus(), 0.01);

        // Day 7 (Upper boundary of 7-day priority window)
        PrologAssessment day7 = prologService.evaluateRedistributionCandidate("Strawberries", "kg", 30.0, 10.0, 7, true);
        assertNotNull(day7);
        assertEquals("PRIORITY_DONATION", day7.getRedistributionStatus());
        assertEquals("HIGH", day7.getRedistributionPriority());
        assertTrue(day7.isRedistributionEligible());
        assertEquals(20.0, day7.getProjectedSurplus(), 0.01);
    }

    @Test
    @DisplayName("Policy 2: Expiry 8-30 days (e.g. 8, 15, 30 days) + True Surplus -> DONATION_RECOMMENDED, RECOMMENDED priority, Eligible")
    public void testPolicy_8to30Days_DonationRecommended() {
        // Day 8 (Lower boundary of 8-30 day window)
        PrologAssessment day8 = prologService.evaluateRedistributionCandidate("Cabbage", "heads", 40.0, 15.0, 8, true);
        assertNotNull(day8);
        assertEquals("DONATION_RECOMMENDED", day8.getRedistributionStatus());
        assertEquals("RECOMMENDED", day8.getRedistributionPriority());
        assertTrue(day8.isRedistributionEligible());
        assertEquals(25.0, day8.getProjectedSurplus(), 0.01);
        assertTrue(day8.getRedistributionReasonEn().toLowerCase().contains("donation recommended"));
        assertTrue(day8.getRedistributionReasonMy().contains("လှူဒါန်း"));

        // Day 15 (Mid window)
        PrologAssessment day15 = prologService.evaluateRedistributionCandidate("Apples", "kg", 50.0, 20.0, 15, true);
        assertNotNull(day15);
        assertEquals("DONATION_RECOMMENDED", day15.getRedistributionStatus());
        assertEquals("RECOMMENDED", day15.getRedistributionPriority());
        assertTrue(day15.isRedistributionEligible());
        assertEquals(30.0, day15.getProjectedSurplus(), 0.01);

        // Day 30 (Upper boundary of 1-month window)
        PrologAssessment day30 = prologService.evaluateRedistributionCandidate("Cheese Blocks", "kg", 25.0, 10.0, 30, true);
        assertNotNull(day30);
        assertEquals("DONATION_RECOMMENDED", day30.getRedistributionStatus());
        assertEquals("RECOMMENDED", day30.getRedistributionPriority());
        assertTrue(day30.isRedistributionEligible());
        assertEquals(15.0, day30.getProjectedSurplus(), 0.01);
    }

    @Test
    @DisplayName("Policy 3: Expiry > 30 days (e.g. 31, 60 days) -> NOT_NEEDED_YET, LOW priority, Not Eligible")
    public void testPolicy_Beyond30Days_NotNeededYet() {
        // Day 31
        PrologAssessment day31 = prologService.evaluateRedistributionCandidate("Canned Beans", "cans", 100.0, 20.0, 31, true);
        assertNotNull(day31);
        assertEquals("NOT_NEEDED_YET", day31.getRedistributionStatus());
        assertEquals("LOW", day31.getRedistributionPriority());
        assertFalse(day31.isRedistributionEligible(), "Items > 30 days shelf life are not actionable for redistribution yet");
        assertTrue(day31.getRedistributionReasonEn().toLowerCase().contains("not necessary yet"));
        assertTrue(day31.getRedistributionReasonMy().contains("မလိုသေး"));

        // Day 60
        PrologAssessment day60 = prologService.evaluateRedistributionCandidate("Rice Sacks", "kg", 200.0, 50.0, 60, true);
        assertNotNull(day60);
        assertEquals("NOT_NEEDED_YET", day60.getRedistributionStatus());
        assertEquals("LOW", day60.getRedistributionPriority());
        assertFalse(day60.isRedistributionEligible());
    }

    @Test
    @DisplayName("Policy 4: Expired food (expiryDays < 0) -> EXPIRED_NOT_FOR_HUMAN_DONATION, BLOCKED, Not Eligible")
    public void testPolicy_ExpiredFoodBlocked() {
        PrologAssessment expired = prologService.evaluateRedistributionCandidate("Expired Milk", "liter", 15.0, 0.0, -1, true);
        assertNotNull(expired);
        assertEquals("EXPIRED_NOT_FOR_HUMAN_DONATION", expired.getRedistributionStatus());
        assertEquals("BLOCKED", expired.getRedistributionPriority());
        assertFalse(expired.isRedistributionEligible(), "Expired food must NEVER be eligible for human donation");
        assertTrue(expired.getRedistributionReasonEn().toLowerCase().contains("expired"));
        assertTrue(expired.getRedistributionReasonMy().contains("သက်တမ်းကုန်"));
    }

    @Test
    @DisplayName("Policy 5: Unsafe item (isSafe = false) -> UNSAFE, BLOCKED, Not Eligible")
    public void testPolicy_UnsafeFoodBlocked() {
        PrologAssessment unsafe = prologService.evaluateRedistributionCandidate("Compromised Fish", "kg", 10.0, 2.0, 2, false);
        assertNotNull(unsafe);
        assertEquals("UNSAFE", unsafe.getRedistributionStatus());
        assertEquals("BLOCKED", unsafe.getRedistributionPriority());
        assertFalse(unsafe.isRedistributionEligible());
    }

    @Test
    @DisplayName("Policy 6: Zero stock -> OUT_OF_STOCK, NONE priority, Not Eligible")
    public void testPolicy_ZeroStock() {
        PrologAssessment zeroStock = prologService.evaluateRedistributionCandidate("Zero Flour", "kg", 0.0, 10.0, 5, true);
        assertNotNull(zeroStock);
        assertEquals("OUT_OF_STOCK", zeroStock.getRedistributionStatus());
        assertEquals("NONE", zeroStock.getRedistributionPriority());
        assertFalse(zeroStock.isRedistributionEligible());
    }

    @Test
    @DisplayName("Policy 7: No true surplus (Stock <= Expected Demand) -> NO_SURPLUS, NONE priority, Not Eligible")
    public void testPolicy_NoSurplus() {
        // Stock 10, Demand 15
        PrologAssessment noSurplus = prologService.evaluateRedistributionCandidate("High Demand Tomatoes", "kg", 10.0, 15.0, 5, true);
        assertNotNull(noSurplus);
        assertEquals("NO_SURPLUS", noSurplus.getRedistributionStatus());
        assertEquals("NONE", noSurplus.getRedistributionPriority());
        assertFalse(noSurplus.isRedistributionEligible());
        assertEquals(0.0, noSurplus.getProjectedSurplus(), 0.01);
    }

    @Test
    @DisplayName("Policy 8: Redistribution Candidate Sorting -> Priority first, then fewest days to expiry, then largest safe surplus")
    public void testCandidateSorting() throws SQLException {
        Map<String, Object> candidates = redistributionService.evaluateRedistributionCandidates();
        assertNotNull(candidates);

        @SuppressWarnings("unchecked")
        List<RedistributionService.CandidateItem> priority = (List<RedistributionService.CandidateItem>) candidates.get("priorityCandidates");
        @SuppressWarnings("unchecked")
        List<RedistributionService.CandidateItem> recommended = (List<RedistributionService.CandidateItem>) candidates.get("redistributionCandidates");

        if (priority != null && priority.size() > 1) {
            for (int i = 0; i < priority.size() - 1; i++) {
                RedistributionService.CandidateItem first = priority.get(i);
                RedistributionService.CandidateItem second = priority.get(i + 1);
                assertTrue(first.getExpiryDays() <= second.getExpiryDays(),
                        "Priority candidates must be sorted by fewest days to expiry first");
            }
        }

        if (recommended != null && recommended.size() > 1) {
            for (int i = 0; i < recommended.size() - 1; i++) {
                RedistributionService.CandidateItem first = recommended.get(i);
                RedistributionService.CandidateItem second = recommended.get(i + 1);
                assertTrue(first.getExpiryDays() <= second.getExpiryDays(),
                        "Recommended candidates must be sorted by fewest days to expiry first");
            }
        }
    }

    @Test
    @DisplayName("Policy 9: Schedule Dispatch Safeguards -> Rejects expired food and excess quantity")
    public void testScheduleDispatchSafeguards() throws SQLException {
        // 1. Create an expired item
        FoodItem expiredItem = new FoodItem(null, "Safeguard Expired Butter " + System.currentTimeMillis(), "Dairy",
                new BigDecimal("5.00"), "kg", new BigDecimal("3000.00"),
                LocalDate.now().minusDays(2), new BigDecimal("1.00"));
        FoodItem savedExpired = foodItemService.createFoodItem(expiredItem, 1L);

        Redistribution expiredDispatch = new Redistribution();
        expiredDispatch.setFoodItemId(savedExpired.getId());
        expiredDispatch.setRecipientId(1L);
        expiredDispatch.setQuantity(new BigDecimal("3.00"));

        assertThrows(IllegalArgumentException.class, () -> {
            redistributionService.scheduleDispatch(expiredDispatch, 1L);
        }, "Must reject dispatch for expired food");

        // 2. Create an active item and attempt to donate more than remaining stock
        FoodItem activeItem = new FoodItem(null, "Safeguard Active Bread " + System.currentTimeMillis(), "Bakery",
                new BigDecimal("10.00"), "loaves", new BigDecimal("1500.00"),
                LocalDate.now().plusDays(4), new BigDecimal("2.00"));
        FoodItem savedActive = foodItemService.createFoodItem(activeItem, 1L);

        Redistribution excessDispatch = new Redistribution();
        excessDispatch.setFoodItemId(savedActive.getId());
        excessDispatch.setRecipientId(1L);
        excessDispatch.setQuantity(new BigDecimal("15.00")); // exceeds 10.00 stock

        assertThrows(IllegalArgumentException.class, () -> {
            redistributionService.scheduleDispatch(excessDispatch, 1L);
        }, "Must reject dispatch when quantity exceeds available stock");
    }

    @Test
    @DisplayName("Disposal Workflow: Auto-detection does NOT silently create waste records")
    public void testNoSilentWasteRecordCreation() throws SQLException {
        FoodItem expiredItem = new FoodItem(null, "Test Expired Yogurt " + System.currentTimeMillis(), "Dairy",
                new BigDecimal("5.00"), "units", new BigDecimal("1200.00"),
                LocalDate.now().minusDays(3), new BigDecimal("2.00"));
        FoodItem saved = foodItemService.createFoodItem(expiredItem, 1L);
        assertNotNull(saved.getId());

        int initialWasteCount = wasteService.getAllWasteRecords().size();

        List<FoodItem> itemsForDisposal = foodItemService.getExpiredItemsRequiringDisposal();
        assertNotNull(itemsForDisposal);
        assertTrue(itemsForDisposal.stream().anyMatch(i -> i.getId().equals(saved.getId())));

        int afterCheckWasteCount = wasteService.getAllWasteRecords().size();
        assertEquals(initialWasteCount, afterCheckWasteCount, "Auto-detection must not create waste records automatically");
    }

    @Test
    @DisplayName("Disposal Workflow: Explicit Record as Waste creates WasteRecord atomically and deducts stock")
    public void testExplicitRecordAsWaste() throws SQLException {
        FoodItem item = new FoodItem(null, "Test Expired Cream " + System.currentTimeMillis(), "Dairy",
                new BigDecimal("8.00"), "liter", new BigDecimal("4500.00"),
                LocalDate.now().minusDays(1), new BigDecimal("1.00"));
        FoodItem saved = foodItemService.createFoodItem(item, 1L);

        WasteRecord waste = new WasteRecord(
                saved.getId(),
                new BigDecimal("8.00"),
                WasteRecord.Reason.EXPIRED,
                null,
                LocalDateTime.now(),
                "Recorded via Expired Item Disposal Review"
        );

        WasteRecord created = wasteService.recordWaste(waste, 1L);
        assertNotNull(created.getId());

        FoodItem updated = foodItemService.getFoodItemById(saved.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("0.00").compareTo(updated.getQuantity()), "Remaining stock should be deducted to 0");
    }

    @Test
    @DisplayName("Production Authority: Prolog failure in APP_ENV=production returns safe REASONING_UNAVAILABLE and does NOT generate donation recommendations")
    public void testProductionPrologFailureDoesNotSilentlyInvokeJavaFallback() throws Exception {
        System.setProperty("APP_ENV", "production");
        PrologService.setPrologAvailableForTesting(false);
        try {
            // Food item that would normally qualify for PRIORITY_DONATION in dev fallback
            PrologAssessment assessment = prologService.evaluateRedistributionCandidate("Fresh Bread", "pieces", 50.0, 10.0, 2, true);
            assertNotNull(assessment);
            assertEquals("REASONING_UNAVAILABLE", assessment.getRedistributionStatus());
            assertEquals("NONE", assessment.getRedistributionPriority());
            assertFalse(assessment.isRedistributionEligible());
            assertFalse(assessment.isRecommendRedistribution());
            assertNotNull(assessment.getRedistributionReasonEn());
            assertTrue(assessment.getRedistributionReasonEn().contains("temporarily unavailable"));

            // Verify candidates list categorization
            FoodItem prodItem = new FoodItem(null, "Production Bread " + System.currentTimeMillis(), "Bakery",
                    new BigDecimal("50.00"), "pieces", new BigDecimal("2000.00"),
                    LocalDate.now().plusDays(2), new BigDecimal("10.00"));
            FoodItem saved = foodItemService.createFoodItem(prodItem, 1L);

            Map<String, Object> candidateMap = redistributionService.evaluateRedistributionCandidates();
            @SuppressWarnings("unchecked")
            List<RedistributionService.CandidateItem> priority = (List<RedistributionService.CandidateItem>) candidateMap.get("priorityCandidates");
            @SuppressWarnings("unchecked")
            List<RedistributionService.CandidateItem> recommended = (List<RedistributionService.CandidateItem>) candidateMap.get("redistributionCandidates");
            @SuppressWarnings("unchecked")
            List<RedistributionService.CandidateItem> notEligible = (List<RedistributionService.CandidateItem>) candidateMap.get("notEligible");

            assertFalse(priority.stream().anyMatch(c -> c.getFoodName() != null && c.getFoodName().contains("Production Bread")));
            assertFalse(recommended.stream().anyMatch(c -> c.getFoodName() != null && c.getFoodName().contains("Production Bread")));
            assertTrue(notEligible.stream().anyMatch(c -> c.getFoodName() != null && c.getFoodName().contains("Production Bread")));
        } finally {
            System.clearProperty("APP_ENV");
            PrologService.resetPrologAvailableForTesting();
        }
    }

    @Test
    @DisplayName("Dev Fallback Isolation: Non-production environments can still use the isolated Java mirror when SWI-Prolog is absent")
    public void testDevelopmentFallbackWorksWhenIntentionallyEnabled() {
        System.setProperty("APP_ENV", "development");
        PrologService.setPrologAvailableForTesting(false);
        try {
            PrologAssessment assessment = prologService.evaluateRedistributionCandidate("Dev Milk", "liter", 30.0, 5.0, 3, true);
            assertNotNull(assessment);
            assertEquals("PRIORITY_DONATION", assessment.getRedistributionStatus());
            assertEquals("HIGH", assessment.getRedistributionPriority());
            assertTrue(assessment.isRedistributionEligible());
            assertTrue(assessment.isRecommendRedistribution());
        } finally {
            System.clearProperty("APP_ENV");
            PrologService.resetPrologAvailableForTesting();
        }
    }

    @Test
    @DisplayName("UI Verification: Normal user pages do not expose raw Prolog/SWI-Prolog implementation engine details")
    public void testNoNormalUserUIExposesPrologTerminology() throws Exception {
        java.io.File protectedDir = new java.io.File("src/main/webapp/WEB-INF/protected");
        assertTrue(protectedDir.exists() && protectedDir.isDirectory());

        java.io.File[] htmlFiles = protectedDir.listFiles((dir, name) -> name.endsWith(".html"));
        assertNotNull(htmlFiles);

        for (java.io.File file : htmlFiles) {
            String content = java.nio.file.Files.readString(file.toPath());
            assertFalse(content.contains("SWI-Prolog"), "File " + file.getName() + " should not expose 'SWI-Prolog' to normal users");
            assertFalse(content.contains("foodwaste_rules.pl"), "File " + file.getName() + " should not expose 'foodwaste_rules.pl' to normal users");
            assertFalse(content.contains("rule trace"), "File " + file.getName() + " should not expose 'rule trace' to normal users");
        }
    }

    @Test
    @DisplayName("UI Verification: Redistribution partner terminology does not label every recipient as a charity")
    public void testRedistributionPartnerTerminologyDoesNotLabelEveryRecipientAsCharity() throws Exception {
        java.io.File redistHtml = new java.io.File("src/main/webapp/WEB-INF/protected/redistribution.html");
        String htmlContent = java.nio.file.Files.readString(redistHtml.toPath());

        assertTrue(htmlContent.contains("Active Redistribution Partners"), "Should use 'Active Redistribution Partners'");
        assertTrue(htmlContent.contains("Redistribution Partner Dispatches") || htmlContent.contains("Redistribution Dispatches"), "Should use 'Redistribution Partner Dispatches'");
        assertTrue(htmlContent.contains("Redistribution Partner Directory") || htmlContent.contains("Redistribution Recipients Directory"), "Should use 'Redistribution Partner Directory'");
        assertTrue(htmlContent.contains("Surplus items requiring priority redistribution coordination"), "Should use 'Priority Redistribution Coordination'");
        assertFalse(htmlContent.contains("Surplus items requiring priority charity coordination"), "Should not use 'priority charity coordination'");
    }
}
