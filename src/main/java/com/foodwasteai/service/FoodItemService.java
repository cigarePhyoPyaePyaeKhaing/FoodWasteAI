package com.foodwasteai.service;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.FoodItemDao;
import com.foodwasteai.dao.InventoryTransactionDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.InventoryTransaction;
import com.foodwasteai.util.ValidationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service orchestrating Food Inventory CRUD, status evaluations, and stock transactions.
 * Includes in-memory fallback store when database is offline during local test/dev.
 */
public class FoodItemService {
    private static final Logger logger = LoggerFactory.getLogger(FoodItemService.class);
    private final FoodItemDao foodItemDao;
    private final InventoryTransactionDao transactionDao;

    // In-memory store when DB is offline
    private static final Map<Long, FoodItem> memoryStore = new ConcurrentHashMap<>();
    private static final AtomicLong idGenerator = new AtomicLong(0);

    public FoodItemService() {
        this.foodItemDao = new FoodItemDao();
        this.transactionDao = new InventoryTransactionDao();
    }

    public FoodItemService(FoodItemDao foodItemDao, InventoryTransactionDao transactionDao) {
        this.foodItemDao = foodItemDao;
        this.transactionDao = transactionDao;
    }

    public List<FoodItem> getAllFoodItems() throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return foodItemDao.findAll();
        }
        logger.debug("Serving food items from in-memory fallback");
        List<FoodItem> list = new ArrayList<>(memoryStore.values());
        list.sort(Comparator.comparing(FoodItem::getExpiryDate).thenComparing(FoodItem::getName));
        return list;
    }

    public Optional<FoodItem> getFoodItemById(Long id) throws SQLException {
        if (id == null) return Optional.empty();
        if (DatabaseConfig.isAvailable()) {
            return foodItemDao.findById(id);
        }
        return Optional.ofNullable(memoryStore.get(id));
    }

    public List<FoodItem> getFoodItemsByCategory(String category) throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return foodItemDao.findByCategory(category);
        }
        List<FoodItem> list = new ArrayList<>();
        for (FoodItem item : memoryStore.values()) {
            if (category == null || category.trim().isEmpty() || item.getCategory().equalsIgnoreCase(category.trim())) {
                list.add(item);
            }
        }
        list.sort(Comparator.comparing(FoodItem::getExpiryDate));
        return list;
    }

    public List<FoodItem> getNearExpiryItems(int daysThreshold) throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return foodItemDao.findNearExpiry(daysThreshold);
        }
        LocalDate cutoff = LocalDate.now().plusDays(daysThreshold);
        List<FoodItem> list = new ArrayList<>();
        for (FoodItem item : memoryStore.values()) {
            if (!item.getExpiryDate().isAfter(cutoff) && item.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                list.add(item);
            }
        }
        list.sort(Comparator.comparing(FoodItem::getExpiryDate));
        return list;
    }

    public List<FoodItem> getLowStockItems() throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return foodItemDao.findLowStock();
        }
        List<FoodItem> list = new ArrayList<>();
        for (FoodItem item : memoryStore.values()) {
            if (item.getQuantity().compareTo(item.getMinStockThreshold()) <= 0) {
                list.add(item);
            }
        }
        return list;
    }

    public FoodItem createFoodItem(FoodItem item, Long userId) throws SQLException {
        ValidationUtils.validateFoodItem(item);
        computeStatus(item);

        if (DatabaseConfig.isAvailable()) {
            FoodItem saved = foodItemDao.save(item);
            try {
                InventoryTransaction tx = new InventoryTransaction(
                        saved.getId(),
                        InventoryTransaction.Type.PURCHASE,
                        saved.getQuantity(),
                        saved.getUnit(),
                        "Initial stock addition",
                        userId
                );
                transactionDao.save(tx);
            } catch (Exception e) {
                logger.warn("Failed to log inventory transaction for item #{}: {}", saved.getId(), e.getMessage());
            }
            return saved;
        }

        // Memory Store Fallback
        long newId = idGenerator.incrementAndGet();
        item.setId(newId);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        memoryStore.put(newId, item);
        logger.info("Created food item #{} in memory fallback", newId);
        return item;
    }

    public boolean updateFoodItem(FoodItem item, Long userId) throws SQLException {
        ValidationUtils.validateFoodItem(item);
        if (item.getId() == null) {
            throw new IllegalArgumentException("Food item ID is required for update");
        }
        computeStatus(item);

        if (DatabaseConfig.isAvailable()) {
            boolean updated = foodItemDao.update(item);
            if (updated) {
                try {
                    InventoryTransaction tx = new InventoryTransaction(
                            item.getId(),
                            InventoryTransaction.Type.MANUAL_COUNT,
                            item.getQuantity(),
                            item.getUnit(),
                            "Manual inventory count / edit",
                            userId
                    );
                    transactionDao.save(tx);
                } catch (Exception e) {
                    logger.warn("Failed to log inventory transaction for update #{}: {}", item.getId(), e.getMessage());
                }
            }
            return updated;
        }

        // Memory Store Fallback
        if (memoryStore.containsKey(item.getId())) {
            item.setUpdatedAt(LocalDateTime.now());
            memoryStore.put(item.getId(), item);
            return true;
        }
        return false;
    }

    public boolean deleteFoodItem(Long id) throws SQLException {
        if (id == null) return false;
        if (DatabaseConfig.isAvailable()) {
            return foodItemDao.delete(id);
        }
        return memoryStore.remove(id) != null;
    }

    public void adjustStock(Long foodItemId, BigDecimal deltaQuantity, InventoryTransaction.Type txType, String notes, Long userId) throws SQLException {
        Optional<FoodItem> opt = getFoodItemById(foodItemId);
        if (opt.isEmpty()) {
            throw new IllegalArgumentException("Food item #" + foodItemId + " not found");
        }
        FoodItem item = opt.get();
        BigDecimal newQty = item.getQuantity().add(deltaQuantity);
        if (newQty.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(String.format(
                    "Stock adjustment failed: insufficient stock for item '%s'. Available: %s %s, requested deduction: %s %s.",
                    item.getName(),
                    item.getQuantity().stripTrailingZeros().toPlainString(),
                    item.getUnit(),
                    deltaQuantity.abs().stripTrailingZeros().toPlainString(),
                    item.getUnit()
            ));
        }
        item.setQuantity(newQty);
        computeStatus(item);

        if (DatabaseConfig.isAvailable()) {
            foodItemDao.updateQuantity(foodItemId, newQty);
            try {
                InventoryTransaction tx = new InventoryTransaction(
                        foodItemId,
                        txType,
                        deltaQuantity.abs(),
                        item.getUnit(),
                        notes,
                        userId
                );
                transactionDao.save(tx);
            } catch (Exception e) {
                logger.warn("Could not log stock adjustment transaction: {}", e.getMessage());
            }
        } else {
            item.setUpdatedAt(LocalDateTime.now());
            memoryStore.put(foodItemId, item);
        }
    }

    private void computeStatus(FoodItem item) {
        LocalDate today = LocalDate.now();
        if (item.getExpiryDate().isBefore(today)) {
            item.setStatus("EXPIRED");
        } else if (!item.getExpiryDate().isAfter(today.plusDays(2))) {
            item.setStatus("NEAR_EXPIRY");
        } else if (item.getQuantity().compareTo(item.getMinStockThreshold()) <= 0) {
            item.setStatus("LOW_STOCK");
        } else {
            item.setStatus("OK");
        }
    }
}
