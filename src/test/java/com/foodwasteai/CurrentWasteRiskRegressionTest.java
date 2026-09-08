package com.foodwasteai;

import com.foodwasteai.dao.*;
import com.foodwasteai.model.FoodItem;
import com.foodwasteai.prolog.*;
import com.foodwasteai.service.*;
import com.foodwasteai.controller.BaseServlet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CurrentWasteRiskRegressionTest {
    private final PrologService prolog = new PrologService();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-06T18:00:00Z"), ZoneId.of("Asia/Yangon"));
    private BigDecimal sales = new BigDecimal("11");
    private BigDecimal wasteRate = BigDecimal.ZERO;
    private boolean historyUnavailable;
    private String originalSwiplPath;

    @BeforeEach void useRealEngineForTheseRegressions() {
        originalSwiplPath = System.getProperty("SWIPL_PATH");
        String testPath = System.getenv("RISK_TEST_SWIPL_PATH");
        if (testPath != null && !testPath.isBlank()) {
            System.setProperty("SWIPL_PATH", testPath);
            PrologService.setPrologAvailableForTesting(true);
        }
    }

    @AfterEach void restoreEngineConfiguration() {
        PrologService.resetPrologAvailableForTesting();
        if (originalSwiplPath == null) System.clearProperty("SWIPL_PATH");
        else System.setProperty("SWIPL_PATH",originalSwiplPath);
    }

    private PredictionService service() {
        return new PredictionService(prolog, null, new SalesDao() {
            @Override public BigDecimal getHistoricalAverageDailySales(Long id, int days, Long userId) {
                assertEquals(7, days); return sales;
            }
        }, new WasteRecordDao() {
            @Override public BigDecimal calculateHistoricalWasteRate(Long id, int days, Long userId) throws SQLException {
                assertEquals(14, days);
                if (historyUnavailable) throw new SQLException("History unavailable");
                return wasteRate;
            }
        }, null, null, CLOCK);
    }

    private FoodItem item(double stock, String unit, int days) {
        FoodItem i = new OwnedFoodItemFixture();
        i.setId(987L); i.setName("Generic inventory fixture"); i.setCategory("poultry");
        i.setQuantity(BigDecimal.valueOf(stock)); i.setUnit(unit);
        i.setExpiryDate(LocalDate.of(2026, 9, 7).plusDays(days));
        return i;
    }

    @ParameterizedTest
    @CsvSource({"20,11,10,0,MEDIUM,50", "20,11,10,0.19,MEDIUM,50",
        "20,11,10,0.20,HIGH,82", "20,11,2,0,HIGH,82", "10,8,10,0,MEDIUM,50",
        "10,9,10,0,LOW,18", "70,11,1,0,HIGH,85", "10,8,1,0,HIGH,85", "0,11,10,0,LOW,0"})
    void approvedMatrixUsesRealProlog(double stock, double demand, int days, double history, String risk, double score) {
        PrologAssessment a = prolog.assessFoodItem("Generic fixture", "kg", stock, demand, days, history, demand * 1.1);
        assertEquals("SWI-Prolog Expert Engine", a.getEngineUsed(), "Must run real SWI-Prolog, not the Java mirror");
        assertEquals(risk, a.getRiskLevel()); assertEquals(score, a.getRiskScore());
    }

    @ParameterizedTest
    @CsvSource({"0,true,PRIORITY_DONATION,true", "7,true,PRIORITY_DONATION,true",
            "8,true,DONATION_RECOMMENDED,true", "30,true,DONATION_RECOMMENDED,true",
            "31,true,NOT_NEEDED_YET,false", "-1,true,EXPIRED_NOT_FOR_HUMAN_DONATION,false",
            "10,false,UNSAFE,false"})
    void redistributionPolicyRemainsAuthoritative(int expiry, boolean safe, String status, boolean eligible) {
        var a=prolog.evaluateRedistributionCandidate("Generic fixture","kg",20,11,expiry,safe);
        assertEquals("SWI-Prolog Expert Engine",a.getEngineUsed());
        assertEquals(status,a.getRedistributionStatus()); assertEquals(eligible,a.isRedistributionEligible());
    }

    @Test void confirmedZeroMustNotBecomeCategoryWaste() {
        var a = service().assessFoodItem(item(20,"kg",10)).orElseThrow();
        assertEquals(0, a.getHistoricalWasteRate());
        assertEquals("MEDIUM", a.getRiskLevel()); assertEquals(50, a.getRiskScore());
        assertEquals(10, a.getCurrentDaysRemaining());
        assertEquals("DONATION_RECOMMENDED", a.getRedistributionStatus());
        var before = prolog.assessFoodItem("Generic fixture", "kg",20,11,9,0.22,12.1);
        assertEquals("HIGH", before.getRiskLevel()); assertEquals(82,before.getRiskScore());
        assertTrue(before.getReasons().toString().contains("High historical waste rate recorded"));
    }

    @Test void dashboardUsesCurrentAssessmentAndSerializedScore() throws SQLException {
        // E=4 today is MEDIUM; E=3 tomorrow satisfies HIGH rule B.
        FoodItem i = item(20,"kg",4);
        var report = service().assessInventory(List.of(i));
        var current = ((List<PrologAssessment>)report.get("items")).get(0);
        var days = (List<Map<String,Object>>)report.get("days");
        var tomorrow = ((List<PrologAssessment>)days.get(0).get("items")).get(0);
        assertEquals("MEDIUM", current.getRiskLevel()); assertEquals(4,current.getExpiryDays());
        assertEquals("HIGH", tomorrow.getRiskLevel()); assertEquals(3,tomorrow.getExpiryDays());
        var json = JsonAccess.serialize(current);
        assertEquals("MEDIUM",json.get("riskLevel").getAsString());
        assertEquals(50,json.get("riskScore").getAsDouble());
        assertEquals(50,json.get("riskPercentage").getAsDouble());
        i.setQuantity(BigDecimal.ZERO);
        assertTrue(((List<?>)service().assessInventory(List.of(i)).get("items")).isEmpty());
    }

    @Test void productionEquivalentHistoryUsesConfirmedZero() throws SQLException {
        // Production API snapshot: two sales on Sep 3, 60 + 20 kg; no waste records.
        var saleQuantities = List.of(new BigDecimal("60"), new BigDecimal("20"));
        var total = saleQuantities.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        sales = total.divide(new BigDecimal("7"), 6, java.math.RoundingMode.HALF_UP);
        wasteRate = BigDecimal.ZERO.divide(total, 4, java.math.RoundingMode.HALF_UP);
        var report = service().assessInventory(List.of(item(20,"kg",10)));
        var a = ((List<PrologAssessment>)report.get("items")).get(0);
        assertEquals(LocalDate.of(2026,9,17),a.getExpiryDate());
        assertEquals(10,a.getCurrentDaysRemaining()); assertEquals(10,a.getExpiryDays());
        assertEquals(11.428571,a.getExpectedDemand()); assertEquals(0,a.getHistoricalWasteRate());
        assertEquals("MEDIUM",a.getRiskLevel()); assertEquals(50,a.getRiskScore());
        assertEquals("MEDIUM",JsonAccess.serialize(a).get("riskLevel").getAsString());
        assertEquals("DONATION_RECOMMENDED",a.getRedistributionStatus());
    }

    @Test void refreshUsesChangedFactsAndHistory() {
        var s=service(); var i=item(20,"kg",10);
        assertEquals("MEDIUM",s.assessFoodItem(i).orElseThrow().getRiskLevel());
        wasteRate=new BigDecimal("0.20");
        assertEquals("HIGH",s.assessFoodItem(i).orElseThrow().getRiskLevel());
        wasteRate=BigDecimal.ZERO; sales=new BigDecimal("20");
        assertEquals("LOW",s.assessFoodItem(i).orElseThrow().getRiskLevel());
        i.setExpiryDate(LocalDate.of(2026,9,8));
        assertEquals("HIGH",s.assessFoodItem(i).orElseThrow().getRiskLevel());
    }

    @Test void unavailableHistoryDoesNotInventAConfirmedRate() {
        historyUnavailable=true;
        assertThrows(IllegalStateException.class,()->service().assessFoodItem(item(20,"kg",10)));
    }

    @Test void demandUsesPositiveHistoryAndCanonicalFallback() {
        assertEquals(11,service().calculateExpectedDailyDemand(item(20,"kg",10)));
        sales=BigDecimal.ZERO;
        assertEquals(17,service().calculateExpectedDailyDemand(item(20,"kg",10)));
    }

    private static class JsonAccess extends BaseServlet {
        static com.google.gson.JsonObject serialize(Object value) { return gson.toJsonTree(value).getAsJsonObject(); }
    }
}
