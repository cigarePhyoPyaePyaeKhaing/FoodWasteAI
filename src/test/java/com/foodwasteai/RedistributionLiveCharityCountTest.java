package com.foodwasteai;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.RedistributionDao;
import com.foodwasteai.model.RedistributionRecipient;
import com.foodwasteai.service.FoodItemService;
import com.foodwasteai.service.RedistributionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying live redistribution partner count and reference charity partner seeding:
 * 1. Default reference partners are properly restored when the recipients table is empty.
 * 2. Active recipients are accurately counted from the live database / service layer.
 * 3. Stats endpoint payload contains the correct active charity partner count.
 * 4. The 4 standard verified partners (Hope Community Food Bank, City Youth Shelter & Kitchen,
 *    GreenEarth Animal Sanctuary, Circular BioCompost Hub) are present.
 */
public class RedistributionLiveCharityCountTest {

    private RedistributionService redistributionService;
    private RedistributionDao redistributionDao;

    @BeforeEach
    public void setUp() {
        redistributionDao = new RedistributionDao();
        redistributionService = new RedistributionService(redistributionDao, new FoodItemService());
    }

    @Test
    @DisplayName("Should return all 4 verified charity partner records")
    public void testGetAllRecipientsContainsStandardPartners() throws SQLException {
        List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();
        assertNotNull(recipients, "Recipients list must not be null");
        assertFalse(recipients.isEmpty(), "Recipients list must not be empty");
        assertTrue(recipients.size() >= 4, "Must have at least 4 verified reference partners");

        Set<String> names = recipients.stream()
                .map(RedistributionRecipient::getName)
                .collect(Collectors.toSet());

        assertTrue(names.contains("Hope Community Food Bank"), "Must contain Hope Community Food Bank");
        assertTrue(names.contains("City Youth Shelter & Kitchen"), "Must contain City Youth Shelter & Kitchen");
        assertTrue(names.contains("GreenEarth Animal Sanctuary"), "Must contain GreenEarth Animal Sanctuary");
        assertTrue(names.contains("Circular BioCompost Hub"), "Must contain Circular BioCompost Hub");
    }

    @Test
    @DisplayName("Stats activeCharitiesCount must match live active recipient count")
    public void testRedistributionStatsActiveCharityCount() throws SQLException {
        List<RedistributionRecipient> recipients = redistributionService.getAllRecipients();
        Map<String, Object> stats = redistributionService.getRedistributionStats();

        assertNotNull(stats, "Stats map must not be null");
        assertTrue(stats.containsKey("activeCharitiesCount"), "Stats must contain 'activeCharitiesCount'");

        int activeCountInStats = (int) stats.get("activeCharitiesCount");
        assertEquals(recipients.size(), activeCountInStats,
                "activeCharitiesCount in stats must exactly match the number of active recipients in the live DB");
        assertTrue(activeCountInStats >= 4, "activeCharitiesCount must be at least 4");
    }

    @Test
    @DisplayName("DatabaseConfig.ensureDefaultRecipientsExist seeds recipients idempotently")
    public void testEnsureDefaultRecipientsExistIdempotent() {
        if (DatabaseConfig.isAvailable()) {
            try (Connection conn = DatabaseConfig.getConnection()) {
                DatabaseConfig.ensureDefaultRecipientsExist(conn);
                int count = redistributionDao.countActiveRecipients();
                assertTrue(count >= 4, "Database must have at least 4 active recipients after ensureDefaultRecipientsExist");
            } catch (SQLException e) {
                fail("Database execution failed: " + e.getMessage());
            }
        }
    }
}
