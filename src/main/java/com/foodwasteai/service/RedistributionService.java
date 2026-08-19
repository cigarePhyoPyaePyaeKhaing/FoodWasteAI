package com.foodwasteai.service;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.RedistributionDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.InventoryTransaction;
import com.foodwasteai.model.Redistribution;
import com.foodwasteai.model.RedistributionRecipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service managing surplus food redistribution, charity partner tracking, and donation dispatches.
 */
public class RedistributionService {
    private static final Logger logger = LoggerFactory.getLogger(RedistributionService.class);
    private final RedistributionDao redistributionDao;
    private final FoodItemService foodItemService;

    // Memory Store Fallback
    private static final Map<Long, Redistribution> memoryDispatches = new ConcurrentHashMap<>();
    private static final List<RedistributionRecipient> memoryRecipients = new ArrayList<>();
    private static final AtomicLong dispatchIdGen = new AtomicLong(10);

    static {
        initFallbackRedistribution();
    }

    private static void initFallbackRedistribution() {
        // Recipients
        memoryRecipients.add(new RedistributionRecipient(1L, "Hope Community Food Bank", "Food Bank", "Daw Khin Win", "+95 9 450012345", "hope@foodbank.org", "No. 45 Bogyoke Rd, Yangon", true));
        memoryRecipients.add(new RedistributionRecipient(2L, "City Youth Shelter & Kitchen", "Soup Kitchen", "U Min Naing", "+95 9 790098765", "cityyouth@charity.org", "No. 12 Insein Rd, Yangon", true));
        memoryRecipients.add(new RedistributionRecipient(3L, "GreenEarth Animal Sanctuary", "Animal Feed", "Dr. Hla Myint", "+95 9 250067890", "info@greenearth.org", "Hlegu Township, Yangon", true));
        memoryRecipients.add(new RedistributionRecipient(4L, "Circular BioCompost Hub", "Compost Partner", "Ko Thura", "+95 9 960011223", "biocompost@green.org", "South Dagon Industrial, Yangon", true));

        // Dispatches
        Redistribution d1 = new Redistribution();
        d1.setId(1L);
        d1.setFoodItemId(1L);
        d1.setFoodItemName("Fresh Chicken Breast");
        d1.setRecipientId(1L);
        d1.setRecipientName("Hope Community Food Bank");
        d1.setQuantity(new BigDecimal("15.00"));
        d1.setUnit("kg");
        d1.setPickupTime(LocalDateTime.now().plusDays(1).withHour(16).withMinute(30));
        d1.setStatus(Redistribution.Status.CONFIRMED);
        d1.setNotes("Scheduled courier pickup before 17:00");
        d1.setCreatedAt(LocalDateTime.now().minusHours(4));

        Redistribution d2 = new Redistribution();
        d2.setId(2L);
        d2.setFoodItemId(6L);
        d2.setFoodItemName("Artisan Sliced Bread");
        d2.setRecipientId(2L);
        d2.setRecipientName("City Youth Shelter & Kitchen");
        d2.setQuantity(new BigDecimal("12.00"));
        d2.setUnit("units");
        d2.setPickupTime(LocalDateTime.now().withHour(21).withMinute(0));
        d2.setStatus(Redistribution.Status.PENDING);
        d2.setNotes("Evening closing bakery pickup");
        d2.setCreatedAt(LocalDateTime.now().minusHours(2));

        memoryDispatches.put(1L, d1);
        memoryDispatches.put(2L, d2);
    }

    public RedistributionService() {
        this.redistributionDao = new RedistributionDao();
        this.foodItemService = new FoodItemService();
    }

    public RedistributionService(RedistributionDao redistributionDao, FoodItemService foodItemService) {
        this.redistributionDao = redistributionDao;
        this.foodItemService = foodItemService;
    }

    public List<Redistribution> getAllDispatches() throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return redistributionDao.findAllDispatches();
        }
        List<Redistribution> list = new ArrayList<>(memoryDispatches.values());
        list.sort(Comparator.comparing(Redistribution::getPickupTime, Comparator.nullsLast(Comparator.naturalOrder())));
        return list;
    }

    public List<RedistributionRecipient> getAllRecipients() throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return redistributionDao.findAllRecipients();
        }
        return new ArrayList<>(memoryRecipients);
    }

    public Redistribution scheduleDispatch(Redistribution dispatch, Long userId) throws SQLException {
        if (dispatch == null || dispatch.getFoodItemId() == null || dispatch.getRecipientId() == null) {
            throw new IllegalArgumentException("Food item ID and Recipient ID are required");
        }
        if (dispatch.getQuantity() == null || dispatch.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Donation quantity must be greater than zero");
        }

        // Fill joined names
        Optional<FoodItem> foodOpt = foodItemService.getFoodItemById(dispatch.getFoodItemId());
        if (foodOpt.isPresent()) {
            FoodItem food = foodOpt.get();
            dispatch.setFoodItemName(food.getName());
            if (dispatch.getUnit() == null) dispatch.setUnit(food.getUnit());
        }

        List<RedistributionRecipient> recipients = getAllRecipients();
        for (RedistributionRecipient r : recipients) {
            if (r.getId().equals(dispatch.getRecipientId())) {
                dispatch.setRecipientName(r.getName());
                break;
            }
        }

        if (dispatch.getPickupTime() == null) {
            dispatch.setPickupTime(LocalDateTime.now().plusDays(1).withHour(14).withMinute(0));
        }
        if (dispatch.getStatus() == null) {
            dispatch.setStatus(Redistribution.Status.CONFIRMED);
        }

        Redistribution saved;
        if (DatabaseConfig.isAvailable()) {
            saved = redistributionDao.saveDispatch(dispatch);
        } else {
            long newId = dispatchIdGen.incrementAndGet();
            dispatch.setId(newId);
            dispatch.setCreatedAt(LocalDateTime.now());
            memoryDispatches.put(newId, dispatch);
            saved = dispatch;
        }

        // Deduct quantity from inventory
        try {
            foodItemService.adjustStock(
                    dispatch.getFoodItemId(),
                    dispatch.getQuantity().negate(),
                    InventoryTransaction.Type.REDISTRIBUTION,
                    "Surplus food donation to " + (dispatch.getRecipientName() != null ? dispatch.getRecipientName() : "Charity Partner"),
                    userId
            );
        } catch (Exception e) {
            logger.error("Error adjusting stock after scheduling redistribution: {}", e.getMessage());
        }

        logger.info("Scheduled surplus food dispatch #{} of {} {} to {}",
                saved.getId(), saved.getQuantity(), saved.getUnit(), saved.getRecipientName());
        return saved;
    }

    public boolean updateDispatchStatus(Long id, Redistribution.Status status) throws SQLException {
        if (id == null || status == null) return false;
        if (DatabaseConfig.isAvailable()) {
            return redistributionDao.updateStatus(id, status);
        }
        Redistribution d = memoryDispatches.get(id);
        if (d != null) {
            d.setStatus(status);
            d.setUpdatedAt(LocalDateTime.now());
            return true;
        }
        return false;
    }

    /**
     * Aggregates live redistribution metrics: quantity redistributed, money saved, and waste reduction impact.
     */
    public Map<String, Object> getRedistributionStats() throws SQLException {
        List<Redistribution> dispatches = getAllDispatches();
        List<RedistributionRecipient> recipients = getAllRecipients();
        List<FoodItem> items = foodItemService.getAllFoodItems();
        Map<Long, FoodItem> foodMap = new HashMap<>();
        for (FoodItem f : items) {
            foodMap.put(f.getId(), f);
        }

        double totalRedistributedKg = 0.0;
        double totalMoneySaved = 0.0;
        double wasteReductionKg = 0.0;
        int pending = 0;
        int completed = 0;
        int cancelled = 0;

        for (Redistribution d : dispatches) {
            double qty = d.getQuantity() != null ? d.getQuantity().doubleValue() : 0.0;
            FoodItem food = foodMap.get(d.getFoodItemId());
            double unitPrice = (food != null && food.getPricePerUnit() != null) ? food.getPricePerUnit().doubleValue() : 3500.0;

            if (d.getStatus() == Redistribution.Status.COLLECTED || d.getStatus() == Redistribution.Status.COMPLETED) {
                totalRedistributedKg += qty;
                wasteReductionKg += qty;
                totalMoneySaved += qty * unitPrice;
                completed++;
            } else if (d.getStatus() == Redistribution.Status.PENDING || d.getStatus() == Redistribution.Status.CONFIRMED) {
                totalRedistributedKg += qty;
                wasteReductionKg += qty;
                totalMoneySaved += qty * unitPrice;
                pending++;
            } else if (d.getStatus() == Redistribution.Status.CANCELLED) {
                cancelled++;
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("quantityRedistributedKg", BigDecimal.valueOf(totalRedistributedKg).setScale(2, java.math.RoundingMode.HALF_UP));
        stats.put("estimatedMoneySaved", BigDecimal.valueOf(totalMoneySaved).setScale(2, java.math.RoundingMode.HALF_UP));
        stats.put("wasteReductionImpactKg", BigDecimal.valueOf(wasteReductionKg).setScale(2, java.math.RoundingMode.HALF_UP));
        stats.put("activeCharitiesCount", recipients.size());
        stats.put("pendingDispatchesCount", pending);
        stats.put("completedDispatchesCount", completed);
        stats.put("cancelledDispatchesCount", cancelled);
        stats.put("totalDispatchesCount", dispatches.size());

        return stats;
    }
}
