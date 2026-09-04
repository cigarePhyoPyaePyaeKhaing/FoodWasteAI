package com.foodwasteai.service;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.RedistributionDao;
import com.foodwasteai.dao.SalesDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.InventoryTransaction;
import com.foodwasteai.model.Redistribution;
import com.foodwasteai.model.RedistributionRecipient;
import com.foodwasteai.prolog.PrologAssessment;
import com.foodwasteai.prolog.PrologService;
import com.foodwasteai.util.ExpiryStatusResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service managing surplus food redistribution, charity partner tracking, and donation dispatches.
 * Strictly uses SWI-Prolog as the authoritative decision engine for redistribution eligibility and priority.
 */
public class RedistributionService {
    private static final Logger logger = LoggerFactory.getLogger(RedistributionService.class);
    private final RedistributionDao redistributionDao;
    private final FoodItemService foodItemService;
    private final SalesDao salesDao;
    private final PrologService prologService;
    private final PredictionService predictionService;

    private static final Map<Long, RedistributionRecipient> memoryRecipients = new ConcurrentHashMap<>();
    private static final Map<Long, Redistribution> memoryDispatches = new ConcurrentHashMap<>();
    private static final AtomicLong recipientIdGen = new AtomicLong(0);
    private static final AtomicLong dispatchIdGen = new AtomicLong(0);

    // Thread-safe idempotency cache for client request tokens (prevents duplicate submissions)
    private static final Map<String, Redistribution> processedClientRequests = new ConcurrentHashMap<>();
    private static final Map<String, Long> requestTimestamps = new ConcurrentHashMap<>();
    private static final Map<String, Object> inFlightTokens = new ConcurrentHashMap<>();
    private static final long IDEMPOTENCY_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    static {
        seedInitialRecipients();
    }

    private static void seedInitialRecipients() {
        if (!memoryRecipients.isEmpty()) return;
        RedistributionRecipient r1 = new RedistributionRecipient(1L, "Hope Community Food Bank", "Food Bank", "Daw Khin Win", "+95 9 450012345", "contact@hopefoodbank.org", "124 Inya Road, Kamayut, Yangon", true);
        RedistributionRecipient r2 = new RedistributionRecipient(2L, "City Youth Shelter & Kitchen", "Soup Kitchen", "U Min Naing", "+95 9 790098765", "kitchen@cityshelter.org", "45 Merchant Street, Kyauktada, Yangon", true);
        RedistributionRecipient r3 = new RedistributionRecipient(3L, "GreenEarth Animal Sanctuary", "Animal Rescue", "Ma Thin Thin", "+95 9 260055443", "info@greenearthrescue.org", "88 Htauk Kyant Road, Mingaladon", true);
        RedistributionRecipient r4 = new RedistributionRecipient(4L, "Circular BioCompost Hub", "Composting", "Ko Thet Aung", "+95 9 310022110", "ops@biocomposthub.com", "Plot 12, Industrial Zone, South Dagon", true);

        memoryRecipients.put(1L, r1);
        memoryRecipients.put(2L, r2);
        memoryRecipients.put(3L, r3);
        memoryRecipients.put(4L, r4);
        recipientIdGen.set(4);
    }

    public RedistributionService() {
        this.redistributionDao = new RedistributionDao();
        this.foodItemService = new FoodItemService();
        this.salesDao = new SalesDao();
        this.prologService = new PrologService();
        this.predictionService = new PredictionService();
    }

    public RedistributionService(RedistributionDao redistributionDao, FoodItemService foodItemService) {
        this.redistributionDao = redistributionDao;
        this.foodItemService = foodItemService;
        this.salesDao = new SalesDao();
        this.prologService = new PrologService();
        this.predictionService = new PredictionService();
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

        // Idempotency check with in-flight lock: if a clientRequestId is provided, ensure strictly one execution
        if (dispatch.getClientRequestId() != null && !dispatch.getClientRequestId().trim().isEmpty()) {
            String token = dispatch.getClientRequestId().trim();
            Redistribution existing = processedClientRequests.get(token);
            if (existing != null) {
                logger.warn("Idempotent duplicate redistribution request blocked (token: '{}'). Returning existing dispatch record #{}.", token, existing.getId());
                return existing;
            }

            Object lock = inFlightTokens.computeIfAbsent(token, k -> new Object());
            synchronized (lock) {
                try {
                    existing = processedClientRequests.get(token);
                    if (existing != null) {
                        logger.warn("Idempotent duplicate redistribution request blocked in critical section (token: '{}'). Returning existing dispatch record #{}.", token, existing.getId());
                        return existing;
                    }

                    Redistribution saved = scheduleDispatchInternal(dispatch, userId);
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
        return scheduleDispatchInternal(dispatch, userId);
    }

    private Redistribution scheduleDispatchInternal(Redistribution dispatch, Long userId) throws SQLException {
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

        // Safety Safeguards: Expired food must NEVER be donated for human consumption
        if (food.getExpiryDate() != null) {
            int daysLeft = ExpiryStatusResolver.calculateDaysRemaining(food.getExpiryDate());
            if (daysLeft < 0) {
                throw new IllegalArgumentException("Expired food is unsafe for human consumption. Never eligible for donation.");
            }
        }

        // Available stock safeguard: Donation quantity cannot exceed current available stock
        BigDecimal availableStock = food.getRemainingQuantity() != null ? food.getRemainingQuantity() :
                (food.getQuantity() != null ? food.getQuantity() : BigDecimal.ZERO);
        if (dispatch.getQuantity().compareTo(availableStock) > 0) {
            throw new IllegalArgumentException(String.format("Donation quantity (%.2f) exceeds available stock (%.2f %s)",
                    dispatch.getQuantity().doubleValue(), availableStock.doubleValue(), food.getUnit()));
        }

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

        // Synchronously deduct inventory balance
        foodItemService.adjustStock(
                dispatch.getFoodItemId(),
                dispatch.getQuantity().negate(),
                InventoryTransaction.Type.REDISTRIBUTION,
                "Surplus food redistribution dispatch #" + saved.getId() + " to " + recipient.getName(),
                userId
        );

        logger.info("Scheduled surplus food dispatch #{} of {} {} to {}", saved.getId(), saved.getQuantity(), saved.getUnit(), recipient.getName());
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

    /**
     * DTO representing a food item evaluated for redistribution suitability by SWI-Prolog.
     */
    public static class CandidateItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long foodItemId;
        private String foodName;
        private String category;
        private double stock;
        private String unit;
        private double expectedDemand;
        private double projectedSurplus;
        private int expiryDays;
        private String expiryDate;
        private String status; // PRIORITY_DONATION, DONATION_RECOMMENDED, NOT_NEEDED_YET, NO_SURPLUS, OUT_OF_STOCK, UNSAFE, EXPIRED_NOT_FOR_HUMAN_DONATION
        private String priority; // HIGH, RECOMMENDED, LOW, NONE, BLOCKED
        private String statusLabelEn;
        private String statusLabelMy;
        private String reasonEn;
        private String reasonMy;
        private String suggestedActionEn;
        private String suggestedActionMy;
        private boolean eligible;

        public CandidateItem() {}

        public Long getFoodItemId() { return foodItemId; }
        public void setFoodItemId(Long foodItemId) { this.foodItemId = foodItemId; }

        public String getFoodName() { return foodName; }
        public void setFoodName(String foodName) { this.foodName = foodName; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public double getStock() { return stock; }
        public void setStock(double stock) { this.stock = stock; }

        public String getUnit() { return unit != null ? unit : "kg"; }
        public void setUnit(String unit) { this.unit = unit; }

        public double getExpectedDemand() { return expectedDemand; }
        public void setExpectedDemand(double expectedDemand) { this.expectedDemand = expectedDemand; }

        public double getProjectedSurplus() { return projectedSurplus; }
        public void setProjectedSurplus(double projectedSurplus) { this.projectedSurplus = projectedSurplus; }

        public int getExpiryDays() { return expiryDays; }
        public void setExpiryDays(int expiryDays) { this.expiryDays = expiryDays; }

        public String getExpiryDate() { return expiryDate; }
        public void setExpiryDate(String expiryDate) { this.expiryDate = expiryDate; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }

        public String getStatusLabelEn() { return statusLabelEn; }
        public void setStatusLabelEn(String statusLabelEn) { this.statusLabelEn = statusLabelEn; }

        public String getStatusLabelMy() { return statusLabelMy; }
        public void setStatusLabelMy(String statusLabelMy) { this.statusLabelMy = statusLabelMy; }

        public String getReasonEn() { return reasonEn; }
        public void setReasonEn(String reasonEn) { this.reasonEn = reasonEn; }

        public String getReasonMy() { return reasonMy; }
        public void setReasonMy(String reasonMy) { this.reasonMy = reasonMy; }

        public String getSuggestedActionEn() { return suggestedActionEn; }
        public void setSuggestedActionEn(String suggestedActionEn) { this.suggestedActionEn = suggestedActionEn; }

        public String getSuggestedActionMy() { return suggestedActionMy; }
        public void setSuggestedActionMy(String suggestedActionMy) { this.suggestedActionMy = suggestedActionMy; }

        public boolean isEligible() { return eligible; }
        public void setEligible(boolean eligible) { this.eligible = eligible; }

        public double getSuggestedDonationQuantity() { return projectedSurplus; }
        public void setSuggestedDonationQuantity(double qty) { this.projectedSurplus = qty; }
        public double getSuggested_donation_quantity() { return getSuggestedDonationQuantity(); }
        public double getProjected_surplus() { return getProjectedSurplus(); }
        public double getExpected_demand() { return getExpectedDemand(); }

        public int getCurrentDaysRemaining() { return expiryDays; }
        public int getExpiryDaysRemaining() { return expiryDays; }
        public String getName() { return foodName; }
        public double getQuantity() { return stock; }
    }

    /**
     * Authoritative Redistribution Candidate Evaluator using SWI-Prolog rules.
     * Evaluates live inventory strictly through PrologService without Java decision tree overrides.
     */
    public Map<String, Object> evaluateRedistributionCandidates() throws SQLException {
        List<FoodItem> items = foodItemService.getAllFoodItems();
        List<CandidateItem> priorityCandidates = new ArrayList<>();
        List<CandidateItem> redistributionCandidates = new ArrayList<>();
        List<CandidateItem> notEligible = new ArrayList<>();
        List<CandidateItem> expiredBlocked = new ArrayList<>();

        double totalSurplusVolume = 0.0;

        for (FoodItem item : items) {
            double stock = item.getQuantity() != null ? item.getQuantity().doubleValue() : 0.0;
            if (stock <= 0) continue;

            String unit = item.getUnit() != null ? item.getUnit() : "kg";
            int expiryDays = ExpiryStatusResolver.calculateDaysRemaining(item.getExpiryDate());

            // Estimated demand: Single Source of Truth via PredictionService
            double expectedDemand = predictionService.calculateExpectedDailyDemand(item);

            // Authoritative Prolog evaluation: Java prepares facts, Prolog decides policy
            PrologAssessment assessment = prologService.evaluateRedistributionCandidate(
                    item.getName(), unit, stock, expectedDemand, expiryDays
            );

            double surplus = Math.max(0.0, stock - expectedDemand);

            CandidateItem candidate = new CandidateItem();
            candidate.setFoodItemId(item.getId());
            candidate.setFoodName(item.getName());
            candidate.setCategory(item.getCategory());
            candidate.setStock(stock);
            candidate.setUnit(unit);
            candidate.setExpectedDemand(expectedDemand);
            candidate.setProjectedSurplus(surplus);
            candidate.setSuggestedDonationQuantity(surplus);
            candidate.setExpiryDays(expiryDays);
            candidate.setExpiryDate(item.getExpiryDate() != null ? item.getExpiryDate().toString() : "N/A");

            // Expose structured Prolog results directly
            candidate.setStatus(assessment.getRedistributionStatus());
            candidate.setPriority(assessment.getRedistributionPriority());
            candidate.setEligible(assessment.isRedistributionEligible());
            candidate.setStatusLabelEn(assessment.getRedistributionStatusLabelEn());
            candidate.setStatusLabelMy(assessment.getRedistributionStatusLabelMy());
            candidate.setReasonEn(assessment.getRedistributionReasonEn());
            candidate.setReasonMy(assessment.getRedistributionReasonMy());
            candidate.setSuggestedActionEn(assessment.getRedistributionSuggestedActionEn());
            candidate.setSuggestedActionMy(assessment.getRedistributionSuggestedActionMy());

            // Partition into UI candidate lists based on Prolog authoritative status
            String pStatus = assessment.getRedistributionStatus();
            if ("EXPIRED_NOT_FOR_HUMAN_DONATION".equalsIgnoreCase(pStatus)) {
                expiredBlocked.add(candidate);
            } else if ("PRIORITY_DONATION".equalsIgnoreCase(pStatus)) {
                priorityCandidates.add(candidate);
                totalSurplusVolume += surplus;
            } else if ("DONATION_RECOMMENDED".equalsIgnoreCase(pStatus)) {
                redistributionCandidates.add(candidate);
                totalSurplusVolume += surplus;
            } else {
                // NOT_NEEDED_YET, NO_SURPLUS, OUT_OF_STOCK, UNSAFE
                notEligible.add(candidate);
            }
        }

        // Sort candidates:
        // 1. Fewest days to expiry
        // 2. Largest safe surplus
        Comparator<CandidateItem> candidateComparator = Comparator.comparingInt(CandidateItem::getExpiryDays)
                .thenComparing(Comparator.comparingDouble(CandidateItem::getProjectedSurplus).reversed());

        priorityCandidates.sort(candidateComparator);
        redistributionCandidates.sort(candidateComparator);
        notEligible.sort(Comparator.comparingInt(CandidateItem::getExpiryDays));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("priorityCandidates", priorityCandidates);
        result.put("redistributionCandidates", redistributionCandidates);
        result.put("notEligible", notEligible);
        result.put("expiredBlocked", expiredBlocked);
        result.put("totalCandidatesCount", priorityCandidates.size() + redistributionCandidates.size());
        result.put("totalSurplusVolume", BigDecimal.valueOf(totalSurplusVolume).setScale(2, java.math.RoundingMode.HALF_UP));

        return result;
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
