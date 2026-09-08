package com.foodwasteai.util;

import com.foodwasteai.model.*;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Robust input and database constraint validator.
 * Ensures data integrity before any SQL prepared statements are executed.
 */
public class ValidationUtils {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final Pattern GMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._%+-]*@gmail\\.com$", Pattern.CASE_INSENSITIVE);

    public static boolean isGmailAddress(String email) {
        if (email == null) return false;
        String trimmed = email.trim();
        return GMAIL_PATTERN.matcher(trimmed).matches();
    }

    public static void validateGmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("Email address is required");
        }
        String trimmed = email.trim();
        int atIndex = trimmed.lastIndexOf('@');
        if (atIndex == -1 || !trimmed.substring(atIndex).equalsIgnoreCase("@gmail.com")) {
            throw new IllegalArgumentException("Only @gmail.com email addresses are allowed. Other domains are rejected.");
        }
        if (!isGmailAddress(trimmed)) {
            throw new IllegalArgumentException("Invalid Gmail format");
        }
    }

    public static void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters long");
        }
        if (!Pattern.compile("[A-Z]").matcher(password).find()) {
            throw new IllegalArgumentException("Password must contain at least 1 uppercase letter");
        }
        if (!Pattern.compile("[a-z]").matcher(password).find()) {
            throw new IllegalArgumentException("Password must contain at least 1 lowercase letter");
        }
        if (!Pattern.compile("[0-9]").matcher(password).find()) {
            throw new IllegalArgumentException("Password must contain at least 1 number");
        }
        if (!Pattern.compile("[^A-Za-z0-9]").matcher(password).find()) {
            throw new IllegalArgumentException("Password must contain at least 1 special character");
        }
    }

    public static void validateRegistration(String email, String password, String confirmPassword) {
        if (email == null || email.trim().length() > 100 || !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new IllegalArgumentException("A valid email address is required.");
        }
        if (password != null && password.length() > 1024) {
            throw new IllegalArgumentException("Password is too long.");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password is required");
        }
        if (confirmPassword == null || confirmPassword.isEmpty()) {
            throw new IllegalArgumentException("Password confirmation is required");
        }
        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Passwords do not match");
        }
        validatePasswordStrength(password);
    }

    public static void validateUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User object cannot be null");
        }
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            throw new IllegalArgumentException("Username is required and cannot be empty");
        }
        if (user.getUsername().length() > 50) {
            throw new IllegalArgumentException("Username cannot exceed 50 characters");
        }
        if (user.getEmail() == null || !EMAIL_PATTERN.matcher(user.getEmail().trim()).matches()) {
            throw new IllegalArgumentException("A valid email address is required");
        }
        if (user.getRole() == null) {
            throw new IllegalArgumentException("User role (ADMIN or STAFF) is required");
        }
    }

    public static final java.util.Set<String> CANONICAL_CATEGORIES = java.util.Set.of(
            "POULTRY", "PRODUCE", "SEAFOOD", "DAIRY", "GRAINS", "BAKERY", "BAKING", "OTHER", "MEAT", "PANTRY", "BEVERAGE", "BEVERAGES", "SNACKS", "FROZEN", "CONDIMENTS"
    );

    public static final java.util.Set<String> CANONICAL_UNITS = java.util.Set.of(
            "kg", "g", "gram", "grams", "liter", "liters", "l", "ml", "pcs", "piece", "pieces", "loaves", "loaf", "units", "unit", "portions", "portion", "cans", "can", "packs", "pack", "packet", "packets", "boxes", "box", "heads", "head", "bottles", "bottle", "bags", "bag"
    );

    public static void validateFoodItem(FoodItem item) {
        if (item == null) {
            throw new IllegalArgumentException("Food item object cannot be null");
        }
        if (item.getName() == null || item.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Food item name is required");
        }
        if (item.getCategory() == null || item.getCategory().trim().isEmpty()) {
            throw new IllegalArgumentException("Food category is required");
        }
        String upperCat = item.getCategory().trim().toUpperCase();
        if (!CANONICAL_CATEGORIES.contains(upperCat)) {
            throw new IllegalArgumentException("Invalid food category: '" + item.getCategory() + "'. Allowed categories: Poultry, Produce, Seafood, Dairy, Grains, Bakery, Other");
        }
        if (item.getQuantity() == null || item.getQuantity().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Quantity must be greater than or equal to 0");
        }
        if (item.getUnit() == null || item.getUnit().trim().isEmpty()) {
            throw new IllegalArgumentException("Stock measurement unit is required");
        }
        String lowerUnit = item.getUnit().trim().toLowerCase();
        if (!CANONICAL_UNITS.contains(lowerUnit)) {
            throw new IllegalArgumentException("Invalid measurement unit: '" + item.getUnit() + "'. Allowed units: kg, g, liter, ml, pcs, loaves, portions, units, cans, packs");
        }
        if (item.getPricePerUnit() == null || item.getPricePerUnit().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Price per unit must be non-negative");
        }
        if (item.getExpiryDate() == null) {
            throw new IllegalArgumentException("Expiry date is required");
        }
    }

    public static void validateSale(Sale sale) {
        if (sale == null) {
            throw new IllegalArgumentException("Sale record cannot be null");
        }
        if (sale.getFoodItemId() == null || sale.getFoodItemId() <= 0) {
            throw new IllegalArgumentException("Valid food item ID is required for sale record");
        }
        if (sale.getQuantitySold() == null || sale.getQuantitySold().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity sold must be greater than 0");
        }
        if (sale.getUnitPrice() != null && sale.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Unit price must be non-negative");
        }
    }

    public static void validateWasteRecord(WasteRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("Waste record cannot be null");
        }
        if (record.getFoodItemId() == null || record.getFoodItemId() <= 0) {
            throw new IllegalArgumentException("Valid food item ID is required for waste log");
        }
        if (record.getQuantityWasted() == null || record.getQuantityWasted().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity wasted must be greater than 0");
        }
        if (record.getReason() == null) {
            throw new IllegalArgumentException("A valid waste reason is required (e.g. EXPIRED, OVERPRODUCTION, etc.)");
        }
    }

    public static void validateInventoryTransaction(InventoryTransaction tx) {
        if (tx == null) {
            throw new IllegalArgumentException("Inventory transaction cannot be null");
        }
        if (tx.getFoodItemId() == null || tx.getFoodItemId() <= 0) {
            throw new IllegalArgumentException("Valid food item ID is required");
        }
        if (tx.getTransactionType() == null) {
            throw new IllegalArgumentException("Transaction type is required");
        }
        if (tx.getQuantity() == null || tx.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Transaction quantity cannot be zero");
        }
    }
}
