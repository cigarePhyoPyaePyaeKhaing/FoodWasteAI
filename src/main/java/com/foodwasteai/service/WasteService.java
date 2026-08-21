package com.foodwasteai.service;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.WasteRecordDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.InventoryTransaction;
import com.foodwasteai.model.WasteRecord;
import com.foodwasteai.util.ValidationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service managing food waste incidents, financial loss calculation, and atomic stock adjustments.
 */
public class WasteService {
    private static final Logger logger = LoggerFactory.getLogger(WasteService.class);
    private final WasteRecordDao wasteDao;
    private final FoodItemService foodItemService;

    // Memory Store Fallback
    private static final Map<Long, WasteRecord> memoryWaste = new ConcurrentHashMap<>();
    private static final AtomicLong wasteIdGen = new AtomicLong(0);
    private static final Object memoryLock = new Object();

    public WasteService() {
        this.wasteDao = new WasteRecordDao();
        this.foodItemService = new FoodItemService();
    }

    public WasteService(WasteRecordDao wasteDao, FoodItemService foodItemService) {
        this.wasteDao = wasteDao;
        this.foodItemService = foodItemService;
    }

    public List<WasteRecord> getAllWasteRecords() throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return wasteDao.findAll();
        }
        List<WasteRecord> list = new ArrayList<>(memoryWaste.values());
        list.sort(Comparator.comparing(WasteRecord::getWasteDate, Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    public Optional<WasteRecord> getWasteRecordById(Long id) throws SQLException {
        if (id == null) return Optional.empty();
        if (DatabaseConfig.isAvailable()) {
            return wasteDao.findById(id);
        }
        return Optional.ofNullable(memoryWaste.get(id));
    }

    public List<WasteRecord> getWasteByFoodItemId(Long foodItemId) throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return wasteDao.findByFoodItemId(foodItemId);
        }
        List<WasteRecord> list = new ArrayList<>();
        for (WasteRecord w : memoryWaste.values()) {
            if (w.getFoodItemId() != null && w.getFoodItemId().equals(foodItemId)) {
                list.add(w);
            }
        }
        return list;
    }

    public List<WasteRecord> getWasteByDateRange(LocalDate start, LocalDate end) throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return wasteDao.findByDateRange(start, end);
        }
        List<WasteRecord> list = new ArrayList<>();
        for (WasteRecord w : memoryWaste.values()) {
            if (w.getWasteDate() != null) {
                LocalDate d = w.getWasteDate().toLocalDate();
                if (!d.isBefore(start) && !d.isAfter(end)) {
                    list.add(w);
                }
            }
        }
        return list;
    }

    public WasteRecord recordWaste(WasteRecord record, Long userId) throws SQLException {
        ValidationUtils.validateWasteRecord(record);

        if (DatabaseConfig.isAvailable()) {
            return wasteDao.recordWasteWithStockDeduction(record, userId);
        }

        // Memory Store Fallback with strict thread-safe synchronization
        synchronized (memoryLock) {
            Optional<FoodItem> foodOpt = foodItemService.getFoodItemById(record.getFoodItemId());
            if (foodOpt.isEmpty()) {
                throw new IllegalArgumentException("Food item #" + record.getFoodItemId() + " does not exist");
            }
            FoodItem foodItem = foodOpt.get();

            BigDecimal availableStock = foodItem.getQuantity() != null ? foodItem.getQuantity() : BigDecimal.ZERO;
            BigDecimal requestedQty = record.getQuantityWasted();

            if (requestedQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Waste quantity must be greater than 0");
            }

            if (requestedQty.compareTo(availableStock) > 0) {
                throw new IllegalArgumentException(String.format(
                        "Insufficient stock. Available: %s %s, requested: %s %s.",
                        availableStock.stripTrailingZeros().toPlainString(),
                        foodItem.getUnit(),
                        requestedQty.stripTrailingZeros().toPlainString(),
                        foodItem.getUnit()
                ));
            }

            // Exact deduction
            BigDecimal newStock = availableStock.subtract(requestedQty);
            if (newStock.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Deduction would result in negative stock");
            }

            record.setFoodItemName(foodItem.getName());
            record.setUnit(foodItem.getUnit());

            // Calculate monetary loss: quantity_wasted * price_per_unit
            BigDecimal pricePerUnit = foodItem.getPricePerUnit() != null ? foodItem.getPricePerUnit() : BigDecimal.ZERO;
            BigDecimal monetaryLoss = pricePerUnit.multiply(requestedQty).setScale(2, RoundingMode.HALF_UP);
            record.setMonetaryLoss(monetaryLoss);

            if (record.getWasteDate() == null) {
                record.setWasteDate(LocalDateTime.now());
            }

            long newId = wasteIdGen.incrementAndGet();
            record.setId(newId);
            record.setCreatedAt(LocalDateTime.now());
            memoryWaste.put(newId, record);

            // Deduct stock in memory store
            foodItemService.adjustStock(
                    foodItem.getId(),
                    requestedQty.negate(),
                    InventoryTransaction.Type.WASTE_ADJUSTMENT,
                    "Waste incident: " + record.getReason() + " (" + requestedQty.stripTrailingZeros().toPlainString() + " " + foodItem.getUnit() + ")",
                    userId
            );

            logger.info("Recorded waste #{} for food item '{}': {} {} (Monetary Loss: {} MMK)",
                    newId, foodItem.getName(), requestedQty, foodItem.getUnit(), monetaryLoss);
            return record;
        }
    }

    public boolean deleteWasteRecord(Long id) throws SQLException {
        if (id == null) return false;
        if (DatabaseConfig.isAvailable()) {
            return wasteDao.delete(id);
        }
        return memoryWaste.remove(id) != null;
    }
}
