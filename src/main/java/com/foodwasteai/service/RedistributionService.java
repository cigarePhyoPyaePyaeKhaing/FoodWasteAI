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

    private static final Map<Long, RedistributionRecipient> memoryRecipients = new ConcurrentHashMap<>();
    private static final Map<Long, Redistribution> memoryDispatches = new ConcurrentHashMap<>();
    private static final AtomicLong recipientIdGen = new AtomicLong(0);
    private static final AtomicLong dispatchIdGen = new AtomicLong(0);

    static {
        seedInitialRecipients();
    }

    private static void seedInitialRecipients() {
        if (!memoryRecipients.isEmpty()) return;
        RedistributionRecipient r1 = new RedistributionRecipient(1L, "Hope Community Food Bank", "Food Bank", "Daw Khin Myint", "+95 9 798765432", "contact@hopecommunity.org", "No. 45, Bogyoke Road, Bahan, Yangon", true);
        RedistributionRecipient r2 = new RedistributionRecipient(2L, "City Youth Shelter & Kitchen", "Soup Kitchen", "U Than Htut", "+95 9 450123456", "info@cityyouthshelter.org", "No. 128, Lower Kyimyindaing Road, Yangon", true);
        RedistributionRecipient r3 = new RedistributionRecipient(3L, "GreenEarth Animal Sanctuary", "Animal Feed", "Dr. Aye Thida", "+95 9 250987654", "rescue@greenearth.org", "Hlawga Park Road, Mingaladon, Yangon", true);
        RedistributionRecipient r4 = new RedistributionRecipient(4L, "Circular BioCompost Hub", "Compost", "Ko Aung Kyaw", "+95 9 360112233", "hello@circularbio.com", "Industrial Zone 3, South Dagon, Yangon", true);

        memoryRecipients.put(1L, r1);
        memoryRecipients.put(2L, r2);
        memoryRecipients.put(3L, r3);
        memoryRecipients.put(4L, r4);
        recipientIdGen.set(4);
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
        return new ArrayList<>(memoryDispatches.values());
    }

    public List<RedistributionRecipient> getAllRecipients() throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return redistributionDao.findAllRecipients();
        }
        List<RedistributionRecipient> list = new ArrayList<>();
        for (RedistributionRecipient r : memoryRecipients.values()) {
            if (r.isActive()) list.add(r);
        }
        list.sort(Comparator.comparing(RedistributionRecipient::getName));
        return list;
    }

    public Optional<RedistributionRecipient> getRecipientById(Long id) throws SQLException {
        if (id == null) return Optional.empty();
        if (DatabaseConfig.isAvailable()) {
            return redistributionDao.findRecipientById(id);
        }
        RedistributionRecipient r = memoryRecipients.get(id);
        if (r != null && r.isActive()) {
            return Optional.of(r);
        }
        return Optional.empty();
    }

    public RedistributionRecipient createRecipient(RedistributionRecipient recipient) throws SQLException {
        if (recipient == null || recipient.getName() == null || recipient.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Recipient organization name is required");
        }
        if (DatabaseConfig.isAvailable()) {
            return redistributionDao.saveRecipient(recipient);
        }
        long id = recipientIdGen.incrementAndGet();
        recipient.setId(id);
        memoryRecipients.put(id, recipient);
        return recipient;
    }

    public Redistribution scheduleDispatch(Redistribution dispatch, Long userId) throws SQLException {
        if (dispatch == null) {
            throw new IllegalArgumentException("Redistribution dispatch payload cannot be null");
        }
        if (dispatch.getFoodItemId() == null) {
            throw new IllegalArgumentException("Food item ID is required");
        }
        if (dispatch.getRecipientId() == null) {
            throw new IllegalArgumentException("Recipient ID is required");
        }
        if (dispatch.getQuantity() == null || dispatch.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Donation quantity must be greater than zero");
        }

        // Validate food item exists
        Optional<FoodItem> foodOpt = foodItemService.getFoodItemById(dispatch.getFoodItemId());
        if (foodOpt.isEmpty()) {
            throw new IllegalArgumentException("Food item not found with ID: " + dispatch.getFoodItemId());
        }
        FoodItem food = foodOpt.get();
        dispatch.setFoodItemName(food.getName());
        if (dispatch.getUnit() == null || dispatch.getUnit().trim().isEmpty()) {
            dispatch.setUnit(food.getUnit());
        }

        // Validate recipient exists in MySQL redistribution_recipients table
        Optional<RedistributionRecipient> recipientOpt = getRecipientById(dispatch.getRecipientId());
        if (recipientOpt.isEmpty()) {
            throw new IllegalArgumentException("Recipient not found or inactive with ID: " + dispatch.getRecipientId());
        }
        RedistributionRecipient recipient = recipientOpt.get();
        dispatch.setRecipientName(recipient.getName());

        if (dispatch.getPickupTime() == null) {
            dispatch.setPickupTime(LocalDateTime.now().plusDays(1).withHour(14).withMinute(0));
        }
        if (dispatch.getStatus() == null) {
            dispatch.setStatus(Redistribution.Status.CONFIRMED);
        }

        TranslationService translator = TranslationService.getInstance();
        if (dispatch.getNotes() != null && !dispatch.getNotes().trim().isEmpty()) {
            if (dispatch.getNotesEn() == null) {
                dispatch.setNotesEn(dispatch.getNotes());
            }
            if (dispatch.getNotesMy() == null) {
                dispatch.setNotesMy(translator.translateToMyanmar(dispatch.getNotesEn()));
            }
        } else {
            String defaultNotesEn = "Surplus food donation of " + dispatch.getQuantity() + " " + dispatch.getUnit() + " to " + dispatch.getRecipientName();
            dispatch.setNotes(defaultNotesEn);
            dispatch.setNotesEn(defaultNotesEn);
            dispatch.setNotesMy(translator.translateToMyanmar(defaultNotesEn));
        }

        Redistribution saved;
        if (DatabaseConfig.isAvailable()) {
            saved = redistributionDao.saveDispatch(dispatch);
        } else {
            long id = dispatchIdGen.incrementAndGet();
            dispatch.setId(id);
            dispatch.setCreatedAt(LocalDateTime.now());
            memoryDispatches.put(id, dispatch);
            saved = dispatch;
        }

        // Deduct quantity from inventory
        try {
            foodItemService.adjustStock(
                    dispatch.getFoodItemId(),
                    dispatch.getQuantity().negate(),
                    InventoryTransaction.Type.REDISTRIBUTION,
                    "Surplus food donation to " + dispatch.getRecipientName(),
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
