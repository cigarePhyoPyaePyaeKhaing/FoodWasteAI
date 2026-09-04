package com.foodwasteai;

import com.foodwasteai.model.*;
import com.foodwasteai.util.ValidationUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

public class ValidationUtilsTest {

    @Test
    @DisplayName("Should pass valid user and reject invalid email/role")
    public void testUserValidation() {
        User validUser = new User(1L, "admin", "admin@foodwaste.ai", "Administrator", User.Role.ADMIN, true);
        assertDoesNotThrow(() -> ValidationUtils.validateUser(validUser));

        User invalidEmailUser = new User(2L, "user2", "invalid-email-string", "User Two", User.Role.STAFF, true);
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateUser(invalidEmailUser));

        User nullRoleUser = new User(3L, "user3", "user3@test.com", "User Three", null, true);
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateUser(nullRoleUser));
    }

    @Test
    @DisplayName("Should validate food item constraints")
    public void testFoodItemValidation() {
        FoodItem item = new FoodItem(1L, "Chicken", "Poultry", new BigDecimal("50.0"), "kg", new BigDecimal("6500"), LocalDate.now().plusDays(2));
        assertDoesNotThrow(() -> ValidationUtils.validateFoodItem(item));

        FoodItem negativeQtyItem = new FoodItem(2L, "Chicken", "Poultry", new BigDecimal("-5.0"), "kg", new BigDecimal("6500"), LocalDate.now());
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateFoodItem(negativeQtyItem));

        FoodItem missingExpiryItem = new FoodItem(3L, "Chicken", "Poultry", new BigDecimal("5.0"), "kg", new BigDecimal("6500"), null);
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateFoodItem(missingExpiryItem));
    }

    @Test
    @DisplayName("Should validate sales and waste constraints")
    public void testSalesAndWasteValidation() {
        Sale validSale = new Sale(1L, new BigDecimal("10.0"), new BigDecimal("6500"), new BigDecimal("65000"), 5, java.time.LocalDateTime.now());
        assertDoesNotThrow(() -> ValidationUtils.validateSale(validSale));

        Sale zeroQtySale = new Sale(1L, BigDecimal.ZERO, new BigDecimal("6500"), BigDecimal.ZERO, 1, java.time.LocalDateTime.now());
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateSale(zeroQtySale));

        WasteRecord validWaste = new WasteRecord(1L, new BigDecimal("4.5"), WasteRecord.Reason.OVERPRODUCTION, new BigDecimal("29250"), java.time.LocalDateTime.now(), "Dinner surplus");
        assertDoesNotThrow(() -> ValidationUtils.validateWasteRecord(validWaste));

        WasteRecord nullReasonWaste = new WasteRecord(1L, new BigDecimal("4.5"), null, new BigDecimal("29250"), java.time.LocalDateTime.now(), "Test");
        assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateWasteRecord(nullReasonWaste));
    }
}
