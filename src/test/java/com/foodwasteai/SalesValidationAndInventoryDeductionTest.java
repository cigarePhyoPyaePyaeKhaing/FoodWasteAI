package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.Sale;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.SalesService;
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
 * unit preservation, and concurrency protection.
 */
public class SalesValidationAndInventoryDeductionTest {

    private FoodItemService foodItemService;
    private SalesService salesService;

    @BeforeEach
    public void setUp() {
        foodItemService = new FoodItemService();
        salesService = new SalesService(new com.foodwasteai.dao.SalesDao(), foodItemService);
    }

    @Test
    @DisplayName("1. Stock = 20 liter, sell 5 -> success, remaining = 15 liter")
    public void testSellWithinStock_RemainingStockUpdated() throws SQLException {
        FoodItem milk = foodItemService.createFoodItem(
                new FoodItem(null, "Fresh Milk " + System.currentTimeMillis(), "Dairy", new BigDecimal("20.00"), "liter",
                        new BigDecimal("2500.00"), LocalDate.now().plusDays(5), new BigDecimal("5.00")), 1L
        );
        Long milkId = milk.getId();

        Sale sale = new Sale(milkId, new BigDecimal("5.00"), new BigDecimal("2500.00"), null, 2, LocalDateTime.now());
        Sale recorded = salesService.recordSale(sale, 1L);

        assertNotNull(recorded.getId());
        assertEquals("liter", recorded.getUnit(), "Sale record must preserve item unit");
        assertEquals(0, new BigDecimal("5.00").compareTo(recorded.getQuantitySold()));
        assertEquals(0, new BigDecimal("12500.00").compareTo(recorded.getTotalAmount()));

        Optional<FoodItem> after = foodItemService.getFoodItemById(milkId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("15.00").compareTo(after.get().getQuantity()), "Remaining stock must be 15.00 liter");
    }

    @Test
    @DisplayName("2. Stock = 20 liter, sell 20 -> success, remaining = 0 liter")
    public void testSellExactStock_StockBecomesZero() throws SQLException {
        FoodItem milk = foodItemService.createFoodItem(
                new FoodItem(null, "Fresh Milk Exact " + System.currentTimeMillis(), "Dairy", new BigDecimal("20.00"), "liter",
                        new BigDecimal("2500.00"), LocalDate.now().plusDays(5), new BigDecimal("5.00")), 1L
        );
        Long milkId = milk.getId();

        Sale sale = new Sale(milkId, new BigDecimal("20.00"), new BigDecimal("2500.00"), null, 5, LocalDateTime.now());
        Sale recorded = salesService.recordSale(sale, 1L);

        assertNotNull(recorded.getId());
        assertEquals(0, new BigDecimal("20.00").compareTo(recorded.getQuantitySold()));

        Optional<FoodItem> after = foodItemService.getFoodItemById(milkId);
        assertTrue(after.isPresent());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.get().getQuantity()), "Remaining stock must be exactly 0.00 liter");
    }

    @Test
    @DisplayName("3, 4, 5. Stock = 20 liter, sell 21 -> rejected, 0 sales row inserted, inventory unchanged")
    public void testSellExceedingStock_RejectedAndNoStateChange() throws SQLException {
        FoodItem milk = foodItemService.createFoodItem(
                new FoodItem(null, "Fresh Milk Over " + System.currentTimeMillis(), "Dairy", new BigDecimal("20.00"), "liter",
                        new BigDecimal("2500.00"), LocalDate.now().plusDays(5), new BigDecimal("5.00")), 1L
        );
        Long milkId = milk.getId();

        Sale sale = new Sale(milkId, new BigDecimal("21.00"), new BigDecimal("2500.00"), null, 4, LocalDateTime.now());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> salesService.recordSale(sale, 1L));
        assertTrue(ex.getMessage().contains("Insufficient stock"), "Exception message must state insufficient stock");
        assertTrue(ex.getMessage().contains("20") && ex.getMessage().contains("21"), "Message should mention available and requested quantities");

        // Verify inventory remained unchanged at 20.00 liter
        Optional<FoodItem> after = foodItemService.getFoodItemById(milkId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("20.00").compareTo(after.get().getQuantity()), "Inventory must remain 20.00 liter");

        // Verify no sale record was inserted
        List<Sale> sales = salesService.getSalesByFoodItemId(milkId);
        assertTrue(sales.isEmpty(), "No sales records must exist for rejected transaction");
    }

    @Test
    @DisplayName("6. Quantity = 0 -> rejected")
    public void testSellZeroQuantity_Rejected() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Item Zero " + System.currentTimeMillis(), "Produce", new BigDecimal("10.00"), "kg",
                        new BigDecimal("1000.00"), LocalDate.now().plusDays(5), new BigDecimal("2.00")), 1L
        );

        Sale sale = new Sale(item.getId(), BigDecimal.ZERO, new BigDecimal("1000.00"), null, 1, LocalDateTime.now());
        assertThrows(IllegalArgumentException.class, () -> salesService.recordSale(sale, 1L));
    }

    @Test
    @DisplayName("7. Negative quantity -> rejected")
    public void testSellNegativeQuantity_Rejected() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Item Neg " + System.currentTimeMillis(), "Produce", new BigDecimal("10.00"), "kg",
                        new BigDecimal("1000.00"), LocalDate.now().plusDays(5), new BigDecimal("2.00")), 1L
        );

        Sale sale = new Sale(item.getId(), new BigDecimal("-5.00"), new BigDecimal("1000.00"), null, 1, LocalDateTime.now());
        assertThrows(IllegalArgumentException.class, () -> salesService.recordSale(sale, 1L));
    }

    @Test
    @DisplayName("8. Non-existent food item -> rejected")
    public void testSellNonExistentFoodItem_Rejected() {
        Sale sale = new Sale(9999999L, new BigDecimal("5.00"), new BigDecimal("1000.00"), null, 1, LocalDateTime.now());
        assertThrows(IllegalArgumentException.class, () -> salesService.recordSale(sale, 1L));
    }

    @Test
    @DisplayName("9. Correct unit returned and preserved across different item types")
    public void testUnitPreservation() throws SQLException {
        FoodItem rice = foodItemService.createFoodItem(
                new FoodItem(null, "Basmati Rice " + System.currentTimeMillis(), "Grains", new BigDecimal("50.00"), "kg",
                        new BigDecimal("4000.00"), LocalDate.now().plusDays(30), new BigDecimal("10.00")), 1L
        );
        FoodItem eggs = foodItemService.createFoodItem(
                new FoodItem(null, "Organic Eggs " + System.currentTimeMillis(), "Produce", new BigDecimal("60.00"), "pieces",
                        new BigDecimal("500.00"), LocalDate.now().plusDays(10), new BigDecimal("12.00")), 1L
        );

        Sale riceSale = salesService.recordSale(new Sale(rice.getId(), new BigDecimal("10.00"), null, null, 1, LocalDateTime.now()), 1L);
        assertEquals("kg", riceSale.getUnit(), "Rice sale must have 'kg' unit");

        Sale eggSale = salesService.recordSale(new Sale(eggs.getId(), new BigDecimal("12.00"), null, null, 1, LocalDateTime.now()), 1L);
        assertEquals("pieces", eggSale.getUnit(), "Egg sale must have 'pieces' unit");
    }

    @Test
    @DisplayName("10. Concurrent sales cannot oversell stock")
    public void testConcurrentSalesCannotOversellStock() throws Exception {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Concurrent Milk " + System.currentTimeMillis(), "Dairy", new BigDecimal("20.00"), "liter",
                        new BigDecimal("2500.00"), LocalDate.now().plusDays(5), new BigDecimal("5.00")), 1L
        );
        Long itemId = item.getId();

        int threadCount = 5;
        BigDecimal requestPerThread = new BigDecimal("6.00"); // 5 * 6 = 30 (exceeds 20.00)
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Sale sale = new Sale(itemId, requestPerThread, new BigDecimal("2500.00"), null, 1, LocalDateTime.now());
                    salesService.recordSale(sale, 1L);
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    rejectCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // At most 3 sales of 6.00 liter can fit into 20.00 liter (3 * 6 = 18 <= 20)
        assertEquals(3, successCount.get(), "Exactly 3 sales of 6.00 liter must succeed");
        assertEquals(2, rejectCount.get(), "Remaining 2 sales must be rejected for insufficient stock");

        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("2.00").compareTo(after.get().getQuantity()), "Remaining stock must be 2.00 liter (20 - 18)");
    }

    @Test
    @DisplayName("12. Expired item (expiry < today) sale is rejected, same-day is permitted")
    public void testExpiryValidation() throws SQLException {
        // Expired item (yesterday)
        FoodItem expiredItem = foodItemService.createFoodItem(
                new FoodItem(null, "Past Milk " + System.currentTimeMillis(), "Dairy", new BigDecimal("10.00"), "liter",
                        new BigDecimal("2000.00"), LocalDate.now().minusDays(1), new BigDecimal("2.00")), 1L
        );
        Sale expiredSale = new Sale(expiredItem.getId(), new BigDecimal("2.00"), new BigDecimal("2000.00"), null, 1, LocalDateTime.now());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> salesService.recordSale(expiredSale, 1L));
        assertTrue(ex.getMessage().contains("expired"), "Must reject sale of expired food");

        // Same day item (today)
        FoodItem todayItem = foodItemService.createFoodItem(
                new FoodItem(null, "Today Bread " + System.currentTimeMillis(), "Bakery", new BigDecimal("10.00"), "pieces",
                        new BigDecimal("1500.00"), LocalDate.now(), new BigDecimal("2.00")), 1L
        );
        Sale todaySale = new Sale(todayItem.getId(), new BigDecimal("2.00"), new BigDecimal("1500.00"), null, 1, LocalDateTime.now());
        Sale recordedToday = salesService.recordSale(todaySale, 1L);
        assertNotNull(recordedToday.getId(), "Same day item should be sellable");
    }
}
