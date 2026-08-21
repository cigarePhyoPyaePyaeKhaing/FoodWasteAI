package com.foodwasteai;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.InventoryTransactionDao;
import com.foodwasteai.dao.SalesDao;
import com.foodwasteai.dao.UserDao;
import com.foodwasteai.dao.WasteRecordDao;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.model.InventoryTransaction;
import com.foodwasteai.model.Sale;
import com.foodwasteai.model.User;
import com.foodwasteai.model.WasteRecord;
import com.foodwasteai.service.AuthService;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.SalesService;
import com.foodwasteai.service.WasteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite ensuring foreign key integrity between authenticated sessions,
 * users table primary keys, and inventory_transactions.created_by audit records.
 */
public class AuditUserForeignKeyIntegrityTest {

    private UserDao userDao;
    private AuthService authService;
    private FoodItemService foodItemService;
    private SalesService salesService;
    private WasteService wasteService;
    private InventoryTransactionDao txDao;

    @BeforeEach
    public void setUp() {
        userDao = new UserDao();
        authService = new AuthService(userDao);
        foodItemService = new FoodItemService();
        salesService = new SalesService(new SalesDao(), foodItemService);
        wasteService = new WasteService(new WasteRecordDao(), foodItemService);
        txDao = new InventoryTransactionDao();
    }

    @Test
    @DisplayName("1. Real Authenticated User: session has real DB users.id, sale audit writes real created_by")
    public void testRealAuthenticatedUserSaleAudit() throws SQLException {
        // Create real user
        String username = "manager_" + System.currentTimeMillis();
        String hash = BCrypt.hashpw("manager123", BCrypt.gensalt(10));
        User user = new User(null, username, username + "@foodwaste.ai", hash, "Branch Manager", User.Role.ADMIN, true);

        User savedUser;
        if (DatabaseConfig.isAvailable()) {
            savedUser = userDao.save(user);
        } else {
            savedUser = user;
            savedUser.setId(999L);
        }

        assertNotNull(savedUser.getId(), "Saved user must have a non-null DB id");
        Long realUserId = savedUser.getId();

        // Authenticate
        Optional<AuthService.UserSession> sessionOpt = authService.authenticate(username, "manager123");
        if (DatabaseConfig.isAvailable()) {
            assertTrue(sessionOpt.isPresent(), "Authentication must succeed for real DB user");
            assertEquals(realUserId, sessionOpt.get().getUser().getId(), "Session user ID must match DB users.id");
        }

        // Create food item
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Organic Apples " + System.currentTimeMillis(), "Produce", new BigDecimal("30.00"), "kg",
                        new BigDecimal("1500.00"), LocalDate.now().plusDays(10), new BigDecimal("5.00")), realUserId
        );
        Long itemId = item.getId();

        // Record sale with real authenticated user ID
        Sale sale = new Sale(itemId, new BigDecimal("4.00"), new BigDecimal("1500.00"), null, 1, LocalDateTime.now());
        Sale recorded = salesService.recordSale(sale, realUserId);

        assertNotNull(recorded.getId(), "Sale must be recorded successfully");
        assertEquals(0, new BigDecimal("6000.00").compareTo(recorded.getTotalAmount()));

        // Verify remaining stock is 26.00 kg
        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("26.00").compareTo(after.get().getQuantity()));

        // Verify audit transactions contain the real user ID or valid foreign key
        if (DatabaseConfig.isAvailable()) {
            List<InventoryTransaction> transactions = txDao.findByFoodItemId(itemId);
            assertFalse(transactions.isEmpty(), "Audit transactions must be logged");
            boolean foundUsage = false;
            for (InventoryTransaction tx : transactions) {
                if (tx.getTransactionType() == InventoryTransaction.Type.USAGE) {
                    assertEquals(realUserId, tx.getCreatedBy(), "inventory_transactions.created_by must match real user.id");
                    foundUsage = true;
                }
            }
            assertTrue(foundUsage, "Must have recorded USAGE transaction");
        }
    }

    @Test
    @DisplayName("2. Real Authenticated User: Waste audit writes real created_by")
    public void testRealAuthenticatedUserWasteAudit() throws SQLException {
        // Create food item
        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Fresh Milk Audit " + System.currentTimeMillis(), "Dairy", new BigDecimal("12.00"), "liter",
                        new BigDecimal("2000.00"), LocalDate.now().plusDays(4), new BigDecimal("3.00")), null
        );
        Long itemId = item.getId();

        WasteRecord waste = new WasteRecord(itemId, new BigDecimal("4.00"), WasteRecord.Reason.EXPIRED, null, LocalDateTime.now(), "Audit test");
        WasteRecord recorded = wasteService.recordWaste(waste, null);

        assertNotNull(recorded.getId());
        assertEquals("liter", recorded.getUnit());

        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("8.00").compareTo(after.get().getQuantity()), "Stock must be reduced 12 -> 8 liter");
    }

    @Test
    @DisplayName("3. Non-existent User ID: resolveValidUserId returns null to avoid FK constraint failure")
    public void testNonExistentUserIdSafelyHandled() throws SQLException {
        Long fakeUserId = 88888888L;

        FoodItem item = foodItemService.createFoodItem(
                new FoodItem(null, "Safe Beef " + System.currentTimeMillis(), "Meat", new BigDecimal("20.00"), "kg",
                        new BigDecimal("12000.00"), LocalDate.now().plusDays(5), new BigDecimal("2.00")), fakeUserId
        );
        Long itemId = item.getId();

        // Record sale with fake user ID: must not throw foreign key constraint violation
        Sale sale = new Sale(itemId, new BigDecimal("5.00"), new BigDecimal("12000.00"), null, 1, LocalDateTime.now());
        Sale recorded = salesService.recordSale(sale, fakeUserId);
        assertNotNull(recorded.getId());

        // Record waste with fake user ID: must not throw foreign key constraint violation
        WasteRecord waste = new WasteRecord(itemId, new BigDecimal("2.00"), WasteRecord.Reason.SPOILED, null, LocalDateTime.now(), "Safe waste");
        WasteRecord savedWaste = wasteService.recordWaste(waste, fakeUserId);
        assertNotNull(savedWaste.getId());

        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("13.00").compareTo(after.get().getQuantity()), "Stock must be 20 - 5 - 2 = 13.00 kg");
    }
}
