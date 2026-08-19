package com.foodwasteai;

import com.foodwasteai.dao.FoodItemDao;
import com.foodwasteai.dao.RedistributionDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.Redistribution;
import com.foodwasteai.model.RedistributionRecipient;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.RedistributionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests ensuring redistribution recipient source-of-truth:
 * 1. Recipients are loaded strictly from the MySQL redistribution_recipients table.
 * 2. Creating a redistribution requires a valid, existing recipient_id from the database.
 * 3. Non-existent, null, or inactive recipient IDs are rejected before DB insertion.
 */
public class RedistributionValidationTest {

    private RedistributionService redistributionService;
    private RedistributionDao redistributionDao;
    private FoodItemService foodItemService;

    @BeforeEach
    public void setUp() {
        redistributionDao = new RedistributionDao();
        foodItemService = new FoodItemService();
        redistributionService = new RedistributionService(redistributionDao, foodItemService);
    }

    @Test
    @DisplayName("Should reject redistribution creation with non-existent recipient ID")
    public void testRejectNonExistentRecipientId() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Test Validation Milk " + System.currentTimeMillis(), "Dairy",
                        new BigDecimal("25.00"), "kg", new BigDecimal("4500.00"),
                        LocalDate.now().plusDays(2), new BigDecimal("5.00")), 1L
        );
        assertNotNull(item.getId());

        Redistribution dispatch = new Redistribution();
        dispatch.setFoodItemId(item.getId());
        dispatch.setRecipientId(99999999L); // Non-existent recipient ID
        dispatch.setQuantity(new BigDecimal("5.00"));
        dispatch.setUnit("kg");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            redistributionService.scheduleDispatch(dispatch, 1L);
        });

        assertTrue(ex.getMessage().contains("Recipient not found") || ex.getMessage().contains("99999999"),
                "Exception message should mention missing recipient ID");
    }

    @Test
    @DisplayName("Should reject redistribution creation with null recipient ID")
    public void testRejectNullRecipientId() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Test Validation Bread " + System.currentTimeMillis(), "Bakery",
                        new BigDecimal("15.00"), "units", new BigDecimal("2000.00"),
                        LocalDate.now().plusDays(2), new BigDecimal("5.00")), 1L
        );
        assertNotNull(item.getId());

        Redistribution dispatch = new Redistribution();
        dispatch.setFoodItemId(item.getId());
        dispatch.setRecipientId(null);
        dispatch.setQuantity(new BigDecimal("5.00"));
        dispatch.setUnit("units");

        assertThrows(IllegalArgumentException.class, () -> {
            redistributionService.scheduleDispatch(dispatch, 1L);
        });
    }

    @Test
    @DisplayName("Should succeed when creating redistribution with an existing, verified database recipient ID")
    public void testSucceedWithValidDatabaseRecipient() throws SQLException {
        // Ensure a valid recipient exists in MySQL redistribution_recipients table
        List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();
        RedistributionRecipient validRecipient;
        if (recipients.isEmpty()) {
            RedistributionRecipient newRecipient = new RedistributionRecipient(
                    null, "Test Hope Center " + System.currentTimeMillis(), "Food Bank",
                    "U Hla", "+95 9 123456789", "hla@hope.org", "Yangon", true
            );
            validRecipient = redistributionService.createRecipient(newRecipient);
        } else {
            validRecipient = recipients.get(0);
        }
        assertNotNull(validRecipient.getId());

        // Create food item
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Test Surplus Produce " + System.currentTimeMillis(), "Produce",
                        new BigDecimal("20.00"), "kg", new BigDecimal("3000.00"),
                        LocalDate.now().plusDays(1), new BigDecimal("4.00")), 1L
        );

        Redistribution dispatch = new Redistribution();
        dispatch.setFoodItemId(item.getId());
        dispatch.setRecipientId(validRecipient.getId());
        dispatch.setQuantity(new BigDecimal("8.00"));
        dispatch.setUnit("kg");
        dispatch.setPickupTime(LocalDateTime.now().plusDays(1));
        dispatch.setNotes("Donation to verified charity");

        Redistribution saved = redistributionService.scheduleDispatch(dispatch, 1L);
        assertNotNull(saved.getId());
        assertEquals(validRecipient.getId(), saved.getRecipientId());
        assertEquals(validRecipient.getName(), saved.getRecipientName());

        // Verify stock deducted
        Optional<FoodItem> afterItem = foodItemService.getFoodItemById(item.getId());
        assertTrue(afterItem.isPresent());
        assertEquals(0, new BigDecimal("12.00").compareTo(afterItem.get().getQuantity()));
    }

    @Test
    @DisplayName("Should reject redistribution creation with non-existent food item ID")
    public void testRejectNonExistentFoodItemId() throws SQLException {
        List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();
        RedistributionRecipient validRecipient;
        if (recipients.isEmpty()) {
            validRecipient = redistributionService.createRecipient(
                    new RedistributionRecipient(null, "Test Charity " + System.currentTimeMillis(), "Soup Kitchen", "U Ba", "+95 9 11112222", "ba@soup.org", "Yangon", true)
            );
        } else {
            validRecipient = recipients.get(0);
        }

        Redistribution dispatch = new Redistribution();
        dispatch.setFoodItemId(88888888L); // Non-existent food ID
        dispatch.setRecipientId(validRecipient.getId());
        dispatch.setQuantity(new BigDecimal("5.00"));
        dispatch.setUnit("kg");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            redistributionService.scheduleDispatch(dispatch, 1L);
        });
        assertTrue(ex.getMessage().contains("Food item not found") || ex.getMessage().contains("88888888"));
    }

    @Test
    @DisplayName("Should reject redistribution creation with zero or negative quantity")
    public void testRejectZeroOrNegativeQuantity() throws SQLException {
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Test Zero Qty Food " + System.currentTimeMillis(), "Bakery",
                        new BigDecimal("20.00"), "units", new BigDecimal("1500.00"),
                        LocalDate.now().plusDays(2), new BigDecimal("5.00")), 1L
        );
        List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();
        Long recipientId = recipients.isEmpty() ? 1L : recipients.get(0).getId();

        Redistribution dispatchZero = new Redistribution();
        dispatchZero.setFoodItemId(item.getId());
        dispatchZero.setRecipientId(recipientId);
        dispatchZero.setQuantity(BigDecimal.ZERO);

        assertThrows(IllegalArgumentException.class, () -> {
            redistributionService.scheduleDispatch(dispatchZero, 1L);
        });

        Redistribution dispatchNeg = new Redistribution();
        dispatchNeg.setFoodItemId(item.getId());
        dispatchNeg.setRecipientId(recipientId);
        dispatchNeg.setQuantity(new BigDecimal("-5.00"));

        assertThrows(IllegalArgumentException.class, () -> {
            redistributionService.scheduleDispatch(dispatchNeg, 1L);
        });
    }

    @Test
    @DisplayName("Should reject redistribution when recipient is inactive")
    public void testRejectInactiveRecipient() throws SQLException {
        RedistributionRecipient inactiveRecipient = new RedistributionRecipient(
                null, "Inactive Partner " + System.currentTimeMillis(), "Animal Shelter",
                "Ko Ko", "+95 9 33334444", "koko@rescue.org", "Yangon", false
        );
        RedistributionRecipient saved = redistributionDao.saveRecipient(inactiveRecipient);
        assertNotNull(saved.getId());

        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Test Inactive Item " + System.currentTimeMillis(), "Meat",
                        new BigDecimal("10.00"), "kg", new BigDecimal("8000.00"),
                        LocalDate.now().plusDays(1), new BigDecimal("2.00")), 1L
        );

        Redistribution dispatch = new Redistribution();
        dispatch.setFoodItemId(item.getId());
        dispatch.setRecipientId(saved.getId());
        dispatch.setQuantity(new BigDecimal("3.00"));
        dispatch.setUnit("kg");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            redistributionService.scheduleDispatch(dispatch, 1L);
        });
        assertTrue(ex.getMessage().contains("Recipient not found or inactive"));

        // Cleanup
        redistributionDao.deleteRecipient(saved.getId());
    }

    @Test
    @DisplayName("Should find recipient by ID via RedistributionDao only when active")
    public void testFindRecipientById() throws SQLException {
        RedistributionRecipient newRecipient = new RedistributionRecipient(
                null, "Test Orphanage " + System.currentTimeMillis(), "Community Shelter",
                "Daw Myint", "+95 9 987654321", "myint@shelter.org", "Mandalay", true
        );
        RedistributionRecipient saved = redistributionDao.saveRecipient(newRecipient);
        assertNotNull(saved.getId());

        Optional<RedistributionRecipient> foundOpt = redistributionDao.findRecipientById(saved.getId());
        assertTrue(foundOpt.isPresent());
        assertEquals(saved.getName(), foundOpt.get().getName());
        assertEquals(saved.getOrganizationType(), foundOpt.get().getOrganizationType());

        // Cleanup
        redistributionDao.deleteRecipient(saved.getId());
        Optional<RedistributionRecipient> deletedOpt = redistributionDao.findRecipientById(saved.getId());
        assertTrue(deletedOpt.isEmpty());
    }
}
