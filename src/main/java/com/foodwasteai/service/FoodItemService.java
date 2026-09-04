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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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
    private static final List<InventoryTransaction> memoryTransactions = new CopyOnWriteArrayList<>();
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
        List<FoodItem> list = new ArrayList<>();
        for (FoodItem item : memoryStore.values()) {
            item.updateComputedExpiryFields();
            computeMemoryTotalQuantity(item);
            list.add(item);
        }
        list.sort(Comparator.comparing(FoodItem::getExpiryDate).thenComparing(FoodItem::getName));
        return list;
    }

    public Optional<FoodItem> getFoodItemById(Long id) throws SQLException {
        if (id == null) return Optional.empty();
        if (DatabaseConfig.isAvailable()) {
            return foodItemDao.findById(id);
        }
        FoodItem item = memoryStore.get(id);
        if (item != null) {
            item.updateComputedExpiryFields();
            computeMemoryTotalQuantity(item);
        }
        return Optional.ofNullable(item);
    }

    public List<FoodItem> getFoodItemsByCategory(String category) throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return foodItemDao.findByCategory(category);
        }
        List<FoodItem> list = new ArrayList<>();
        for (FoodItem item : memoryStore.values()) {
            item.updateComputedExpiryFields();
            computeMemoryTotalQuantity(item);
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
        LocalDate cutoff = com.foodwasteai.util.ExpiryStatusResolver.getToday().plusDays(daysThreshold);
        List<FoodItem> list = new ArrayList<>();
        for (FoodItem item : memoryStore.values()) {
            item.updateComputedExpiryFields();
            computeMemoryTotalQuantity(item);
            if (!item.getExpiryDate().isAfter(cutoff) && item.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                list.add(item);
            }
        }
        list.sort(Comparator.comparing(FoodItem::getExpiryDate));
        return list;
    }

    public void computeMemoryTotalQuantity(FoodItem item) {
        if (item == null || item.getId() == null) return;
        BigDecimal remainingQty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
        item.setRemainingQuantity(remainingQty);

        BigDecimal stockInSum = memoryTransactions.stream()
                .filter(t -> item.getId().equals(t.getFoodItemId()))
                .filter(t -> t.getTransactionType() == InventoryTransaction.Type.PURCHASE
                        || "STOCK_IN".equalsIgnoreCase(String.valueOf(t.getTransactionType()))
                        || t.getTransactionType() == InventoryTransaction.Type.MANUAL_COUNT)
                .map(InventoryTransaction::getQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (stockInSum.compareTo(BigDecimal.ZERO) > 0) {
            item.setTotalQuantity(stockInSum.max(remainingQty));
        } else {
            item.setTotalQuantity(remainingQty);
        }
    }

    public List<FoodItem> getLowStockItems() throws SQLException {
        return new ArrayList<>();
    }

    /**
     * Retrieves all expired inventory items that still have remaining stock (> 0)
     * requiring explicit user disposal confirmation.
     */
    public List<FoodItem> getExpiredItemsRequiringDisposal() throws SQLException {
        List<FoodItem> allItems = getAllFoodItems();
        List<FoodItem> expiredWithStock = new ArrayList<>();
        for (FoodItem item : allItems) {
            item.updateComputedExpiryFields();
            if (com.foodwasteai.util.ExpiryStatusResolver.isExpired(item.getExpiryDate()) &&
                item.getQuantity() != null && item.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                expiredWithStock.add(item);
            }
        }
        expiredWithStock.sort(Comparator.comparing(FoodItem::getExpiryDate));
        return expiredWithStock;
    }

    /**
     * Handles inventory stock additions according to the batch merge rule:
     * Merges quantity into an existing row if normalized name, unit, price_per_unit, and expiry_date match.
     * Otherwise creates a new food item row with a new ID.
     * Transactionally records the stock-in addition audit history.
     */
    public FoodItem addFoodItem(FoodItem item, Long userId) throws SQLException {
        ValidationUtils.validateFoodItem(item);
        computeStatus(item);

        if (DatabaseConfig.isAvailable()) {
            return foodItemDao.saveOrMergeStockWithTransaction(item, userId);
        }

        // Memory Store Fallback for Stock Addition & Batch Merging
        String normName = item.getName() != null ? item.getName().trim() : "";
        String normUnit = item.getUnit() != null ? item.getUnit().trim() : "kg";
        BigDecimal price = item.getPricePerUnit() != null ? item.getPricePerUnit().setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
        LocalDate expiry = item.getExpiryDate();
        BigDecimal addedQty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;

        for (FoodItem existing : memoryStore.values()) {
            String exNorm = existing.getName() != null ? existing.getName().trim() : "";
            String exUnit = existing.getUnit() != null ? existing.getUnit().trim() : "kg";
            BigDecimal exPrice = existing.getPricePerUnit() != null ? existing.getPricePerUnit().setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
            LocalDate exExpiry = existing.getExpiryDate();

            if (exNorm.equalsIgnoreCase(normName)
                    && exUnit.equalsIgnoreCase(normUnit)
                    && exPrice.compareTo(price) == 0
                    && Objects.equals(exExpiry, expiry)) {

                BigDecimal currentQty = existing.getQuantity() != null ? existing.getQuantity() : BigDecimal.ZERO;
                BigDecimal newQty = currentQty.add(addedQty);
                existing.setQuantity(newQty);
                existing.setUpdatedAt(LocalDateTime.now());
                computeStatus(existing);

                InventoryTransaction tx = new InventoryTransaction(
                        existing.getId(),
                        InventoryTransaction.Type.PURCHASE,
                        addedQty,
                        normUnit,
                        "Stock addition: +" + addedQty.stripTrailingZeros().toPlainString() + " " + normUnit,
                        userId
                );
                tx.setId((long) (memoryTransactions.size() + 1));
                tx.setCreatedAt(LocalDateTime.now());
                memoryTransactions.add(tx);

                computeMemoryTotalQuantity(existing);
                logger.info("Merged stock addition in-memory for item #{}: added {}, new total {}",
                        existing.getId(), addedQty, newQty);
                return existing;
            }
        }

        // New Item in Memory
        long newId = idGenerator.incrementAndGet();
        item.setId(newId);
        item.setQuantity(addedQty);
        item.setRemainingQuantity(addedQty);
        item.setTotalQuantity(addedQty);
        item.setUnit(normUnit);
        item.setPricePerUnit(price);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        computeStatus(item);
        memoryStore.put(newId, item);

        InventoryTransaction tx = new InventoryTransaction(
                newId,
                InventoryTransaction.Type.PURCHASE,
                addedQty,
                normUnit,
                "Initial stock addition: +" + addedQty.stripTrailingZeros().toPlainString() + " " + normUnit,
                userId
        );
        tx.setId((long) (memoryTransactions.size() + 1));
        tx.setCreatedAt(LocalDateTime.now());
        memoryTransactions.add(tx);

        logger.info("Created new food item #{} in-memory with initial stock {}", newId, addedQty);
        return item;
    }

    public FoodItem createFoodItem(FoodItem item, Long userId) throws SQLException {
        ValidationUtils.validateFoodItem(item);
        computeStatus(item);

        if (DatabaseConfig.isAvailable()) {
            FoodItem saved = foodItemDao.save(item);
            saved.updateComputedExpiryFields();
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
        item.setRemainingQuantity(item.getQuantity());
        item.setTotalQuantity(item.getQuantity());
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        memoryStore.put(newId, item);
        return item;
    }

    /**
     * Retrieves stock addition transactions for a specific food item ID sorted newest first.
     */
    public List<InventoryTransaction> getItemStockHistory(Long foodItemId) throws SQLException {
        if (foodItemId == null) return Collections.emptyList();
        if (DatabaseConfig.isAvailable()) {
            return transactionDao.findByFoodItemId(foodItemId);
        }
        return memoryTransactions.stream()
                .filter(t -> foodItemId.equals(t.getFoodItemId()))
                .sorted(Comparator.comparing(InventoryTransaction::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(InventoryTransaction::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    public static void clearMemoryStore() {
        memoryStore.clear();
        memoryTransactions.clear();
        idGenerator.set(0);
    }

    public boolean updateFoodItem(FoodItem item, Long userId) throws SQLException {
        if (item == null) {
            throw new IllegalArgumentException("Food item object cannot be null");
        }
        if (item.getId() == null) {
            throw new IllegalArgumentException("Food item ID is required for update");
        }

        // If category or unit is omitted in update payload, preserve existing values
        if (item.getCategory() == null || item.getCategory().trim().isEmpty() ||
            item.getUnit() == null || item.getUnit().trim().isEmpty()) {
            Optional<FoodItem> existingOpt = getFoodItemById(item.getId());
            if (existingOpt.isPresent()) {
                FoodItem existing = existingOpt.get();
                if (item.getCategory() == null || item.getCategory().trim().isEmpty()) {
                    item.setCategory(existing.getCategory());
                }
                if (item.getUnit() == null || item.getUnit().trim().isEmpty()) {
                    item.setUnit(existing.getUnit());
                }
            }
        }

        ValidationUtils.validateFoodItem(item);
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
        if (newQty.compareTo(BigDecimal.ZERO) == 0) {
            newQty = BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        item.setQuantity(newQty);
        computeStatus(item);

        if (DatabaseConfig.isAvailable()) {
            foodItemDao.updateQuantity(foodItemId, newQty);
            try {
                InventoryTransaction tx = new InventoryTransaction(
                        foodItemId,
                        txType != null ? txType : (deltaQuantity.compareTo(BigDecimal.ZERO) > 0 ? InventoryTransaction.Type.PURCHASE : InventoryTransaction.Type.WASTE_ADJUSTMENT),
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
            item.setQuantity(newQty);
            item.setRemainingQuantity(newQty);
            item.setUpdatedAt(LocalDateTime.now());
            computeMemoryTotalQuantity(item);
            memoryStore.put(foodItemId, item);
        }
    }

    public void adjustStock(Long foodItemId, BigDecimal deltaQuantity, String notes, Long userId) throws SQLException {
        adjustStock(foodItemId, deltaQuantity, deltaQuantity.compareTo(BigDecimal.ZERO) > 0 ? InventoryTransaction.Type.PURCHASE : InventoryTransaction.Type.WASTE_ADJUSTMENT, notes, userId);
    }

    private void computeStatus(FoodItem item) {
        if (item == null) return;
        item.updateComputedExpiryFields();
    }
}
