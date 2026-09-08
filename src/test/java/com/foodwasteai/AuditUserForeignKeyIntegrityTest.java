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
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.SalesService;
import com.foodwasteai.service.WasteService;
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
 * Test suite ensuring foreign key integrity between optional user records,
 * users table primary keys, and inventory_transactions.created_by audit records.
 */
public class AuditUserForeignKeyIntegrityTest {

    private UserDao userDao;
    private FoodItemService foodItemService;
    private SalesService salesService;
    private WasteService wasteService;
    private InventoryTransactionDao txDao;

    @BeforeEach
    public void setUp() {
        userDao = new UserDao();
        foodItemService = new FoodItemService();
        salesService = new SalesService(new SalesDao(), foodItemService);
        wasteService = new WasteService(new WasteRecordDao(), foodItemService);
        txDao = new InventoryTransactionDao();
    }

    @Test
    @DisplayName("1. Real User: user has real DB users.id, sale audit writes real created_by")
    public void testRealUserSaleAudit() throws SQLException {
        // Create real user
        String username = "manager_" + System.currentTimeMillis();
        User user = new User(null, username, username + "@foodwaste.ai", "manager123", "Branch Manager", User.Role.ADMIN, true);

        User savedUser;
        if (DatabaseConfig.isAvailable()) {
            savedUser = userDao.save(user);
        } else {
            savedUser = user;
            savedUser.setId(999L);
        }

        assertNotNull(savedUser.getId(), "Saved user must have a non-null DB id");
        Long realUserId = savedUser.getId();

        // Create food item
        FoodItem item = foodItemService.createFoodItem(
                new OwnedFoodItemFixture(null, "Organic Apples " + System.currentTimeMillis(), "Produce", new BigDecimal("30.00"), "kg",
                        new BigDecimal("1500.00"), LocalDate.now().plusDays(10), new BigDecimal("5.00")), realUserId
        );
        Long itemId = item.getId();

        // Record sale with real user ID
        Sale sale = new Sale(itemId, new BigDecimal("4.00"), new BigDecimal("1500.00"), null, 1, LocalDateTime.now());
        Sale recorded = salesService.recordSale(sale, realUserId);

        assertNotNull(recorded.getId(), "Sale must be recorded successfully");
        assertEquals(0, new BigDecimal("6000.00").compareTo(recorded.getTotalAmount()));

        // Verify remaining stock is 26.00 kg
        Optional<FoodItem> after = foodItemService.getFoodItemById(itemId, realUserId);
        assertTrue(after.isPresent());
        assertEquals(0, new BigDecimal("26.00").compareTo(after.get().getQuantity()));

        // Verify audit transactions contain the real user ID or valid foreign key
        if (DatabaseConfig.isAvailable()) {
            List<InventoryTransaction> transactions = txDao.findByFoodItemId(itemId, realUserId);
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
    void anonymousInventoryIsRejected() {
        var item = new OwnedFoodItemFixture(null,"Anonymous fixture","Dairy",new BigDecimal("12"),"liter",new BigDecimal("2000"),LocalDate.now().plusDays(4));
        assertThrows(IllegalArgumentException.class, () -> foodItemService.createFoodItem(item, null));
    }
    @Test
    void unknownOwnerIsRejectedByForeignKey() {
        var item = new OwnedFoodItemFixture(null,"Unknown owner fixture","Meat",new BigDecimal("20"),"kg",new BigDecimal("12000"),LocalDate.now().plusDays(5));
        assertThrows(SQLException.class, () -> foodItemService.createFoodItem(item, 88888888L));
    }
}
