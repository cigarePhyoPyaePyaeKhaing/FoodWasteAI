package com.foodwasteai.service;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.SalesDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.InventoryTransaction;
import com.foodwasteai.model.Sale;
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
 * Service managing customer food sales, demand tracking, and inventory deductions.
 */
public class SalesService {
    private static final Logger logger = LoggerFactory.getLogger(SalesService.class);
    private final SalesDao salesDao;
    private final FoodItemService foodItemService;

    // Memory Store Fallback
    private static final Map<Long, Sale> memorySales = new ConcurrentHashMap<>();
    private static final AtomicLong salesIdGen = new AtomicLong(0);

    public SalesService() {
        this.salesDao = new SalesDao();
        this.foodItemService = new FoodItemService();
    }

    public SalesService(SalesDao salesDao, FoodItemService foodItemService) {
        this.salesDao = salesDao;
        this.foodItemService = foodItemService;
    }

    public List<Sale> getAllSales() throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return salesDao.findAll();
        }
        List<Sale> list = new ArrayList<>(memorySales.values());
        list.sort(Comparator.comparing(Sale::getSaleDate, Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    public Optional<Sale> getSaleById(Long id) throws SQLException {
        if (id == null) return Optional.empty();
        if (DatabaseConfig.isAvailable()) {
            return salesDao.findById(id);
        }
        return Optional.ofNullable(memorySales.get(id));
    }

    public List<Sale> getSalesByFoodItemId(Long foodItemId) throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return salesDao.findByFoodItemId(foodItemId);
        }
        List<Sale> list = new ArrayList<>();
        for (Sale s : memorySales.values()) {
            if (s.getFoodItemId() != null && s.getFoodItemId().equals(foodItemId)) {
                list.add(s);
            }
        }
        return list;
    }

    public List<Sale> getSalesByDateRange(LocalDate start, LocalDate end) throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return salesDao.findByDateRange(start, end);
        }
        List<Sale> list = new ArrayList<>();
        for (Sale s : memorySales.values()) {
            if (s.getSaleDate() != null) {
                LocalDate d = s.getSaleDate().toLocalDate();
                if (!d.isBefore(start) && !d.isAfter(end)) {
                    list.add(s);
                }
            }
        }
        return list;
    }

    public Sale recordSale(Sale sale, Long userId) throws SQLException {
        ValidationUtils.validateSale(sale);

        // Fetch food item details
        Optional<FoodItem> foodOpt = foodItemService.getFoodItemById(sale.getFoodItemId());
        if (foodOpt.isEmpty()) {
            throw new IllegalArgumentException("Food item #" + sale.getFoodItemId() + " does not exist");
        }
        FoodItem foodItem = foodOpt.get();
        sale.setFoodItemName(foodItem.getName());

        if (sale.getUnitPrice() == null || sale.getUnitPrice().compareTo(BigDecimal.ZERO) == 0) {
            sale.setUnitPrice(foodItem.getPricePerUnit());
        }
        sale.setTotalAmount(sale.getUnitPrice().multiply(sale.getQuantitySold()).setScale(2, java.math.RoundingMode.HALF_UP));

        if (sale.getSaleDate() == null) {
            sale.setSaleDate(LocalDateTime.now());
        }

        Sale saved;
        if (DatabaseConfig.isAvailable()) {
            saved = salesDao.save(sale);
        } else {
            long newId = salesIdGen.incrementAndGet();
            sale.setId(newId);
            sale.setCreatedAt(LocalDateTime.now());
            memorySales.put(newId, sale);
            saved = sale;
        }

        // Deduct sold quantity from inventory
        try {
            foodItemService.adjustStock(
                    foodItem.getId(),
                    sale.getQuantitySold().negate(),
                    InventoryTransaction.Type.USAGE,
                    "Customer sale (" + sale.getQuantitySold() + " " + foodItem.getUnit() + ")",
                    userId
            );
        } catch (Exception e) {
            logger.error("Error adjusting stock after sale: {}", e.getMessage());
        }

        logger.info("Recorded sale #{} for food item '{}': {} units (Total: {})",
                saved.getId(), foodItem.getName(), saved.getQuantitySold(), saved.getTotalAmount());
        return saved;
    }

    public boolean deleteSale(Long id) throws SQLException {
        if (id == null) return false;
        if (DatabaseConfig.isAvailable()) {
            return salesDao.delete(id);
        }
        return memorySales.remove(id) != null;
    }
}
