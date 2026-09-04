package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.Redistribution;
import com.foodwasteai.model.RedistributionRecipient;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.RedistributionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying that surplus food redistribution creation is protected against
 * duplicate creation and that rapid or concurrent submissions create exactly one record.
 */
public class RedistributionDuplicateSubmissionTest {

    private RedistributionService redistributionService;
    private FoodItemService foodItemService;

    @BeforeEach
    public void setUp() {
        this.redistributionService = new RedistributionService();
        this.foodItemService = new FoodItemService();
    }

    @Test
    @DisplayName("1. Single redistribution submission creates exactly one record and deducts inventory once")
    public void testSingleRedistributionCreatesExactlyOneRecord() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Single Redist Milk " + System.currentTimeMillis(), "Dairy",
                        new BigDecimal("20.00"), "liter", new BigDecimal("3500.00"), LocalDate.now().plusDays(2), new BigDecimal("5.00")), 1L
        );
        Long itemId = item.getId();
        BigDecimal initialStock = item.getQuantity();

        List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();
        assertFalse(recipients.isEmpty());
        Long recipientId = recipients.get(0).getId();

        Redistribution dispatch = new Redistribution();
        dispatch.setFoodItemId(itemId);
        dispatch.setRecipientId(recipientId);
        dispatch.setQuantity(new BigDecimal("5.00"));
        dispatch.setUnit("liter");
        dispatch.setClientRequestId("tok_single_" + System.currentTimeMillis());

        Redistribution created = redistributionService.scheduleDispatch(dispatch, 1L);
        assertNotNull(created.getId());

        // Verify inventory deducted exactly once
        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, initialStock.subtract(new BigDecimal("5.00")).compareTo(after.get().getQuantity()));
    }

    @Test
    @DisplayName("2. Rapid double-submit with same clientRequestId creates exactly 1 record and deducts inventory once")
    public void testRapidDoubleSubmissionWithSameTokenCreatesOnlyOneRecord() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Double Submit Yogurt " + System.currentTimeMillis(), "Dairy",
                        new BigDecimal("30.00"), "kg", new BigDecimal("4000.00"), LocalDate.now().plusDays(2), new BigDecimal("5.00")), 1L
        );
        Long itemId = item.getId();
        BigDecimal initialStock = item.getQuantity();

        List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();
        Long recipientId = recipients.get(0).getId();
        String sharedToken = "client_redist_tok_" + System.currentTimeMillis();

        Redistribution first = new Redistribution();
        first.setFoodItemId(itemId);
        first.setRecipientId(recipientId);
        first.setQuantity(new BigDecimal("8.00"));
        first.setUnit("kg");
        first.setClientRequestId(sharedToken);

        Redistribution second = new Redistribution();
        second.setFoodItemId(itemId);
        second.setRecipientId(recipientId);
        second.setQuantity(new BigDecimal("8.00"));
        second.setUnit("kg");
        second.setClientRequestId(sharedToken);

        // Submit first
        Redistribution firstResult = redistributionService.scheduleDispatch(first, 1L);
        assertNotNull(firstResult.getId());

        // Rapid second submit with identical client token (simulating double click)
        Redistribution secondResult = redistributionService.scheduleDispatch(second, 1L);
        assertNotNull(secondResult.getId());

        // Must return the exact same record ID
        assertEquals(firstResult.getId(), secondResult.getId(), "Duplicate submit must return the same dispatch record");

        // Inventory must be reduced ONLY ONCE (30.00 - 8.00 = 22.00, NOT 14.00)
        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("22.00").compareTo(after.get().getQuantity()),
                "Inventory must be deducted exactly once (30 - 8 = 22 kg)");
    }

    @Test
    @DisplayName("3. Concurrent triple-click requests with same clientRequestId deduplicate atomically")
    public void testConcurrentTripleSubmitWithSameTokenCreatesOnlyOneRecord() throws Exception {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Concurrent Cake " + System.currentTimeMillis(), "Bakery",
                        new BigDecimal("15.00"), "kg", new BigDecimal("6000.00"), LocalDate.now().plusDays(2), new BigDecimal("3.00")), 1L
        );
        Long itemId = item.getId();
        String sharedToken = "concurrent_cake_tok_" + System.currentTimeMillis();

        List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();
        Long recipientId = recipients.get(0).getId();

        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Redistribution> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Redistribution r = new Redistribution();
                    r.setFoodItemId(itemId);
                    r.setRecipientId(recipientId);
                    r.setQuantity(new BigDecimal("3.00"));
                    r.setUnit("kg");
                    r.setClientRequestId(sharedToken);

                    Redistribution recorded = redistributionService.scheduleDispatch(r, 1L);
                    results.add(recorded);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount, results.size(), "All 3 concurrent calls should complete");
        Long expectedId = results.get(0).getId();
        for (Redistribution res : results) {
            assertEquals(expectedId, res.getId(), "All concurrent calls must resolve to the exact same dispatch ID");
        }

        // Inventory must be reduced ONLY ONCE (15.00 - 3.00 = 12.00, NOT 6.00)
        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("12.00").compareTo(after.get().getQuantity()),
                "Inventory must be deducted exactly once (15 - 3 = 12 kg)");
    }

    @Test
    @DisplayName("4. Legitimate separate submissions create distinct records")
    public void testLegitimateSeparateSubmissionsCreateDistinctRecords() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Legitimate Separate " + System.currentTimeMillis(), "Produce",
                        new BigDecimal("25.00"), "kg", new BigDecimal("1500.00"), LocalDate.now().plusDays(2), new BigDecimal("5.00")), 1L
        );
        Long itemId = item.getId();

        List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();
        Long recipientId = recipients.get(0).getId();

        Redistribution first = new Redistribution();
        first.setFoodItemId(itemId);
        first.setRecipientId(recipientId);
        first.setQuantity(new BigDecimal("5.00"));
        first.setUnit("kg");
        first.setClientRequestId("legit_tok_1_" + System.currentTimeMillis());

        Redistribution second = new Redistribution();
        second.setFoodItemId(itemId);
        second.setRecipientId(recipientId);
        second.setQuantity(new BigDecimal("5.00"));
        second.setUnit("kg");
        second.setClientRequestId("legit_tok_2_" + System.currentTimeMillis());

        Redistribution r1 = redistributionService.scheduleDispatch(first, 1L);
        Redistribution r2 = redistributionService.scheduleDispatch(second, 1L);

        assertNotEquals(r1.getId(), r2.getId(), "Separate legitimate requests must create distinct records");

        // Inventory should be reduced twice: 25 - 5 - 5 = 15
        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("15.00").compareTo(after.get().getQuantity()));
    }
}
