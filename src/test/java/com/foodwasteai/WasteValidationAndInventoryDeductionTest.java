package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.WasteRecord;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.WasteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rigorous test suite validating strict stock checking, atomic inventory deduction,
 * unit preservation, monetary loss accuracy, and concurrency protection for Food Waste incidents.
 */
public class WasteValidationAndInventoryDeductionTest {

    private FoodItemService foodItemService;
    private WasteService wasteService;

    @BeforeEach
    public void setUp() {
        foodItemService = new FoodItemService();
        wasteService = new WasteService(new com.foodwasteai.dao.WasteRecordDao(), foodItemService);
    }

    @Test
    @DisplayName("1. Fresh Milk: Stock = 15 liter, waste 3 -> success, remaining = 12 liter, unit = liter, loss = 6,000 MMK")
    public void testWasteWithinStock_RemainingStockAndUnitPreserved() throws SQLException {
        FoodItem milk = foodItemService.createFoodItem(
                new FoodItem(null, "Fresh Milk " + System.currentTimeMillis(), "Dairy", new BigDecimal("15.00"), "liter",
                        new BigDecimal("2000.00"), LocalDate.now().plusDays(3), new BigDecimal("5.00")), 1L
        );
        Long milkId = milk.getId();

        WasteRecord waste = new WasteRecord(milkId, new BigDecimal("3.00"), WasteRecord.Reason.EXPIRED, null, LocalDateTime.now(), "Expired in fridge");
        WasteRecord recorded = wasteService.recordWaste(waste, 1L);

        assertNotNull(recorded.getId());
        assertEquals("liter", recorded.getUnit(), "Waste record must preserve real item unit (liter)");
        assertEquals(0, new BigDecimal("3.00").compareTo(recorded.getQuantityWasted()), "Quantity wasted must be 3.00");
        assertEquals(0, new BigDecimal("6000.00").compareTo(recorded.getMonetaryLoss()), "Monetary loss must be 3 * 2000 = 6,000 MMK");

        Optional<FoodItem> after = foodItemService.getFoodItemById(milkId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("12.00").compareTo(after.get().getQuantity()), "Remaining stock must be 12.00 liter");
    }

    @Test
    @DisplayName("2. Over-waste: Stock = 12 liter, waste 20 -> rejected, stock remains 12, no waste row created")
    public void testOverWaste_StockInsufficient_ThrowsExceptionAndPreservesStock() throws SQLException {
        FoodItem milk = foodItemService.createFoodItem(
                new FoodItem(null, "Fresh Milk Over " + System.currentTimeMillis(), "Dairy", new BigDecimal("12.00"), "liter",
                        new BigDecimal("2000.00"), LocalDate.now().plusDays(3), new BigDecimal("5.00")), 1L
        );
        Long milkId = milk.getId();

        WasteRecord waste = new WasteRecord(milkId, new BigDecimal("20.00"), WasteRecord.Reason.SPOILED, null, LocalDateTime.now(), "Attempt over-waste");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            wasteService.recordWaste(waste, 1L);
        });

        assertTrue(ex.getMessage().contains("Insufficient stock") || ex.getMessage().contains("Available: 12"),
                "Exception message should mention insufficient stock: " + ex.getMessage());

        // Verify inventory was NOT changed
        Optional<FoodItem> after = foodItemService.getFoodItemById(milkId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("12.00").compareTo(after.get().getQuantity()), "Inventory must remain 12.00 liter");

        // Verify no waste record was saved for this item
        List<WasteRecord> records = wasteService.getWasteByFoodItemId(milkId);
        assertTrue(records.isEmpty(), "No waste record should be created on failed validation");
    }

    @Test
    @DisplayName("3. Exact Stock: Stock = 15 liter, waste 15 -> success, remaining = 0 liter, loss = 30,000 MMK")
    public void testWasteExactStock_StockBecomesZero() throws SQLException {
        FoodItem milk = foodItemService.createFoodItem(
                new FoodItem(null, "Fresh Milk Exact " + System.currentTimeMillis(), "Dairy", new BigDecimal("15.00"), "liter",
                        new BigDecimal("2000.00"), LocalDate.now().plusDays(3), new BigDecimal("5.00")), 1L
        );
        Long milkId = milk.getId();

        WasteRecord waste = new WasteRecord(milkId, new BigDecimal("15.00"), WasteRecord.Reason.OVERPRODUCTION, null, LocalDateTime.now(), "Exact batch disposal");
        WasteRecord recorded = wasteService.recordWaste(waste, 1L);

        assertNotNull(recorded.getId());
        assertEquals(0, new BigDecimal("15.00").compareTo(recorded.getQuantityWasted()));
        assertEquals(0, new BigDecimal("30000.00").compareTo(recorded.getMonetaryLoss()), "Monetary loss must be 15 * 2000 = 30,000 MMK");

        Optional<FoodItem> after = foodItemService.getFoodItemById(milkId);
        assertTrue(after.isPresent());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.get().getQuantity()), "Remaining stock must be exactly 0.00 liter");
    }

    @Test
    @DisplayName("4. Zero Quantity: Waste 0.00 -> rejected")
    public void testWasteZeroQuantity_Rejected() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Rice " + System.currentTimeMillis(), "Grains", new BigDecimal("50.00"), "kg",
                        new BigDecimal("3000.00"), LocalDate.now().plusDays(30), new BigDecimal("10.00")), 1L
        );
        Long itemId = item.getId();

        WasteRecord waste = new WasteRecord(itemId, BigDecimal.ZERO, WasteRecord.Reason.OTHER, null, LocalDateTime.now(), "Zero qty test");
        assertThrows(IllegalArgumentException.class, () -> {
            wasteService.recordWaste(waste, 1L);
        });

        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("50.00").compareTo(after.get().getQuantity()));
    }

    @Test
    @DisplayName("5. Negative Quantity: Waste -5.00 -> rejected")
    public void testWasteNegativeQuantity_Rejected() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Eggs " + System.currentTimeMillis(), "Poultry", new BigDecimal("100.00"), "pieces",
                        new BigDecimal("400.00"), LocalDate.now().plusDays(10), new BigDecimal("20.00")), 1L
        );
        Long itemId = item.getId();

        WasteRecord waste = new WasteRecord(itemId, new BigDecimal("-5.00"), WasteRecord.Reason.DAMAGED, null, LocalDateTime.now(), "Negative qty test");
        assertThrows(IllegalArgumentException.class, () -> {
            wasteService.recordWaste(waste, 1L);
        });

        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("100.00").compareTo(after.get().getQuantity()));
    }

    @Test
    @DisplayName("6. Non-existent Item: Waste for invalid foodItemId -> rejected")
    public void testWasteNonExistentItem_Rejected() {
        WasteRecord waste = new WasteRecord(99999999L, new BigDecimal("5.00"), WasteRecord.Reason.SPOILED, null, LocalDateTime.now(), "Non-existent item");
        assertThrows(IllegalArgumentException.class, () -> {
            wasteService.recordWaste(waste, 1L);
        });
    }

    @Test
    @DisplayName("7. Multi-unit test: Eggs in 'pieces' preserves unit and computes accurate loss")
    public void testPiecesUnitAndLossComputation() throws SQLException {
        FoodItem eggs = foodItemService.createFoodItem(
                new FoodItem(null, "Organic Eggs " + System.currentTimeMillis(), "Poultry", new BigDecimal("60.00"), "pieces",
                        new BigDecimal("450.00"), LocalDate.now().plusDays(7), new BigDecimal("10.00")), 1L
        );
        Long eggsId = eggs.getId();

        WasteRecord waste = new WasteRecord(eggsId, new BigDecimal("12.00"), WasteRecord.Reason.DAMAGED, null, LocalDateTime.now(), "Cracked during transport");
        WasteRecord recorded = wasteService.recordWaste(waste, 1L);

        assertEquals("pieces", recorded.getUnit(), "Must preserve 'pieces' unit");
        assertEquals(0, new BigDecimal("12.00").compareTo(recorded.getQuantityWasted()));
        assertEquals(0, new BigDecimal("5400.00").compareTo(recorded.getMonetaryLoss()), "12 pieces * 450 MMK = 5,400 MMK");

        Optional<FoodItem> after = foodItemService.getFoodItemById(eggsId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("48.00").compareTo(after.get().getQuantity()));
    }

    @Test
    @DisplayName("8. Concurrency Protection: 10 concurrent threads attempt to waste 3 units each on stock of 15")
    public void testConcurrentWaste_PreventsOversubtraction() throws Exception {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Concurrent Beef " + System.currentTimeMillis(), "Meat", new BigDecimal("15.00"), "kg",
                        new BigDecimal("18000.00"), LocalDate.now().plusDays(4), new BigDecimal("2.00")), 1L
        );
        Long itemId = item.getId();

        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await(); // Start all threads simultaneously
                    WasteRecord w = new WasteRecord(itemId, new BigDecimal("3.00"), WasteRecord.Reason.EXPIRED, null, LocalDateTime.now(), "Concurrent test");
                    wasteService.recordWaste(w, 1L);
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    failureCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }));
        }

        latch.countDown(); // Trigger all threads
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // With 15 kg stock and 3 kg per waste, exactly 5 threads must succeed and 5 threads must fail
        assertEquals(5, successCount.get(), "Exactly 5 waste operations of 3 kg should succeed from 15 kg stock");
        assertEquals(5, failureCount.get(), "Remaining 5 waste operations must be rejected");

        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.get().getQuantity()), "Final stock must be exactly 0.00 kg and never negative");
    }
}
