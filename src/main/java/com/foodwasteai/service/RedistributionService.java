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
        return Collections.emptyList();
    }

    public List<RedistributionRecipient> getAllRecipients() throws SQLException {
        if (DatabaseConfig.isAvailable()) {
            return redistributionDao.findAllRecipients();
        }
        return Collections.emptyList();
    }

    public Optional<RedistributionRecipient> getRecipientById(Long id) throws SQLException {
        if (id == null) return Optional.empty();
        if (DatabaseConfig.isAvailable()) {
            return redistributionDao.findRecipientById(id);
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
        throw new SQLException("Database connection is unavailable to register recipient");
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

        if (!DatabaseConfig.isAvailable()) {
            throw new SQLException("Database connection is unavailable to save redistribution record");
        }

        Redistribution saved = redistributionDao.saveDispatch(dispatch);

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
