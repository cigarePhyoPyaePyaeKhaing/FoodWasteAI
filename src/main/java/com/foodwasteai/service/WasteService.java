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

    // Thread-safe idempotency cache for client request tokens (prevents duplicate waste submissions)
    private static final Map<String, WasteRecord> processedClientRequests = new ConcurrentHashMap<>();
    private static final Map<String, Long> requestTimestamps = new ConcurrentHashMap<>();
    private static final long IDEMPOTENCY_TTL_MS = 5 * 60 * 1000L; // 5 minutes
    private static final ConcurrentHashMap<String, Object> inFlightTokens = new ConcurrentHashMap<>();

    public WasteService() {
        this.wasteDao = new WasteRecordDao();
        this.foodItemService = new FoodItemService();
    }

    public WasteService(WasteRecordDao wasteDao, FoodItemService foodItemService) {
        this.wasteDao = wasteDao;
        this.foodItemService = foodItemService;
    }

    public List<WasteRecord> getAllWasteRecords() throws SQLException {
        List<WasteRecord> persisted;
        if (DatabaseConfig.isAvailable()) {
            persisted = wasteDao.findAll();
        } else {
            persisted = new ArrayList<>(memoryWaste.values());
        }

        // Single Date Rule: Products that reach the end of their usable life TODAY (expiry_date == today, quantity > 0)
        // or past expired are handled as TODAY'S ACTUAL / CONFIRMED WASTE.
        // Remaining unsold quantity -> today's actual waste.
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        Set<Long> recordedFoodItemIdsToday = new HashSet<>();
        for (WasteRecord w : persisted) {
            if (w.getFoodItemId() != null && w.getWasteDate() != null) {
                if (w.getWasteDate().toLocalDate().isEqual(today)) {
                    recordedFoodItemIdsToday.add(w.getFoodItemId());
                }
            }
        }

        List<WasteRecord> result = new ArrayList<>(persisted);
        try {
            List<FoodItem> inventory = foodItemService.getAllFoodItems();
            for (FoodItem item : inventory) {
                if (item.getQuantity() != null && item.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    if (item.getExpiryDate() != null && !item.getExpiryDate().isAfter(today)) {
                        if (!recordedFoodItemIdsToday.contains(item.getId())) {
                            // Synthesize actual waste record in memory without mutating database
                            WasteRecord autoWaste = new WasteRecord();
                            autoWaste.setId(-item.getId()); // Virtual non-conflicting negative ID
                            autoWaste.setFoodItemId(item.getId());
                            autoWaste.setFoodItemName(item.getName());
                            autoWaste.setQuantityWasted(item.getQuantity());
                            autoWaste.setUnit(item.getUnit());
                            BigDecimal price = item.getPricePerUnit() != null ? item.getPricePerUnit() : BigDecimal.ZERO;
                            autoWaste.setMonetaryLoss(price.multiply(item.getQuantity()).setScale(2, RoundingMode.HALF_UP));
                            autoWaste.setWasteDate(today.atStartOfDay());
                            autoWaste.setReason(WasteRecord.Reason.EXPIRED);
                            autoWaste.setNotes("Usable life ended on " + today + " (unsold inventory stock)");
                            autoWaste.setCreatedAt(today.atStartOfDay());
                            result.add(autoWaste);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not append today's actual waste to waste records: {}", e.getMessage());
        }

        result.sort(Comparator.comparing(WasteRecord::getWasteDate, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
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

        // Guarantee Requirement 9: Waste Record date/time must be when the waste was actually recorded, never expiry date
        if (record.getWasteDate() == null) {
            record.setWasteDate(LocalDateTime.now());
        }

        // Idempotency check with in-flight lock: if a clientRequestId is provided, ensure strictly one execution
        if (record.getClientRequestId() != null && !record.getClientRequestId().trim().isEmpty()) {
            String token = record.getClientRequestId().trim();
            WasteRecord existing = processedClientRequests.get(token);
            if (existing != null) {
                logger.warn("Idempotent duplicate waste request blocked (token: '{}'). Returning existing waste record #{}.", token, existing.getId());
                return existing;
            }

            Object lock = inFlightTokens.computeIfAbsent(token, k -> new Object());
            synchronized (lock) {
                try {
                    existing = processedClientRequests.get(token);
                    if (existing != null) {
                        logger.warn("Idempotent duplicate waste request blocked in critical section (token: '{}'). Returning existing waste record #{}.", token, existing.getId());
                        return existing;
                    }

                    WasteRecord saved;
                    if (DatabaseConfig.isAvailable()) {
                        saved = wasteDao.recordWasteWithStockDeduction(record, userId);
                    } else {
                        saved = recordWasteInMemory(record, userId);
                    }

                    if (saved != null) {
                        processedClientRequests.put(token, saved);
                        requestTimestamps.put(token, System.currentTimeMillis());
                        cleanOldIdempotencyTokens();
                    }
                    return saved;
                } finally {
                    inFlightTokens.remove(token);
                }
            }
        }

        // Standard execution when no clientRequestId is provided
        if (DatabaseConfig.isAvailable()) {
            return wasteDao.recordWasteWithStockDeduction(record, userId);
        } else {
            return recordWasteInMemory(record, userId);
        }
    }

    private WasteRecord recordWasteInMemory(WasteRecord record, Long userId) throws SQLException {
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
            if (newStock.compareTo(BigDecimal.ZERO) == 0) {
                newStock = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
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

    /**
     * Converts all inventory items that have reached or passed their expiration date
     * (expiry_date <= today and quantity > 0) into confirmed waste records atomically,
     * deducting the entire remaining unsold inventory so that its quantity becomes exactly 0.
     *
     * Prevents double deduction:
     * - Only items with quantity > 0 are converted.
     * - Once converted, inventory is 0, so subsequent calls safely skip them.
     * - Uses idempotent clientRequestId tokens and atomic stock deduction.
     *
     * @param userId authenticated user ID or system default (1L)
     * @return list of newly created confirmed waste records
     */
    public synchronized List<WasteRecord> convertExpiredInventoryToWaste(Long userId) throws SQLException {
        LocalDate today = com.foodwasteai.util.ExpiryStatusResolver.getToday();
        List<FoodItem> inventory = foodItemService.getAllFoodItems();
        List<WasteRecord> converted = new ArrayList<>();

        for (FoodItem item : inventory) {
            if (item.getQuantity() != null && item.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                if (item.getExpiryDate() != null && !item.getExpiryDate().isAfter(today)) {
                    WasteRecord record = new WasteRecord();
                    record.setFoodItemId(item.getId());
                    record.setQuantityWasted(item.getQuantity());
                    record.setUnit(item.getUnit());
                    record.setReason(WasteRecord.Reason.EXPIRED);
                    record.setNotes("Confirmed waste: usable life ended on " + today + " (unsold inventory stock)");
                    record.setClientRequestId("auto_expiry_waste_" + item.getId() + "_" + today);

                    try {
                        WasteRecord saved = recordWaste(record, userId != null ? userId : 1L);
                        if (saved != null) {
                            converted.add(saved);
                            logger.info("Automatically converted expired inventory item #{} ('{}') to confirmed waste: {} {} (Stock -> 0)",
                                    item.getId(), item.getName(), item.getQuantity(), item.getUnit());
                        }
                    } catch (Exception e) {
                        logger.error("Failed to convert expired item #{} ('{}') to waste: {}",
                                item.getId(), item.getName(), e.getMessage(), e);
                    }
                }
            }
        }
        return converted;
    }

    public boolean deleteWasteRecord(Long id) throws SQLException {
        if (id == null) return false;
        if (DatabaseConfig.isAvailable()) {
            return wasteDao.delete(id);
        }
        return memoryWaste.remove(id) != null;
    }

    private static void cleanOldIdempotencyTokens() {
        long now = System.currentTimeMillis();
        if (requestTimestamps.size() > 500) {
            requestTimestamps.entrySet().removeIf(entry -> {
                if (now - entry.getValue() > IDEMPOTENCY_TTL_MS) {
                    processedClientRequests.remove(entry.getKey());
                    return true;
                }
                return false;
            });
        }
    }
}
