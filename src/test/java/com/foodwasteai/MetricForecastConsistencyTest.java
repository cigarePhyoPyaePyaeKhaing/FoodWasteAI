package com.foodwasteai;

import com.foodwasteai.dao.*;
import com.foodwasteai.model.*;
import com.foodwasteai.prolog.*;
import com.foodwasteai.service.*;
import com.foodwasteai.controller.BaseServlet;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MetricForecastConsistencyTest {
    private FoodItem item(long id, String unit, double stock, int expiry, double price) {
        return new FoodItem(id,"Generic batch " + id,"Other",BigDecimal.valueOf(stock),unit,BigDecimal.valueOf(price),
            LocalDate.of(2026,9,7).plusDays(expiry),BigDecimal.ONE);
    }
    private PredictionService forecast() {
        return new PredictionService(new PrologService(),null,new SalesDao() {
            @Override public BigDecimal getHistoricalAverageDailySales(Long id,int days) {
                return BigDecimal.valueOf(id==1 ? 100.0/7 : id==2 ? 90.0/7 : 80.0/7);
            }
        },new WasteRecordDao() {
            @Override public BigDecimal calculateHistoricalWasteRate(Long id,int days) {return BigDecimal.ZERO;}
        },null,null,Clock.fixed(Instant.parse("2026-09-06T18:00:00Z"),ZoneId.of("Asia/Yangon")));
    }
    private Redistribution dispatch(long id,double qty,String unit,Redistribution.Status status) {
        Redistribution d=new Redistribution(); d.setFoodItemId(id);d.setQuantity(BigDecimal.valueOf(qty));d.setUnit(unit);d.setStatus(status);return d;
    }
    @Test void physicalCountIsSnapshotAndHistorySurvivesWaste() throws Exception {
        FoodItemService service=new FoodItemService();
        FoodItem fresh=item(999,"kg",8,10,23000);fresh.setId(null);fresh.setName("Count consistency " + UUID.randomUUID());
        FoodItem saved=service.createFoodItem(fresh,1L);
        saved.setQuantity(new BigDecimal("7"));assertTrue(service.updateFoodItem(saved,1L));
        var after=service.getFoodItemById(saved.getId()).orElseThrow();
        assertEquals(7,after.getQuantity().doubleValue());assertEquals(8,after.getTotalQuantity().doubleValue());
        var history=service.getItemStockHistory(saved.getId());
        assertTrue(history.stream().anyMatch(t->t.getTransactionType()==InventoryTransaction.Type.MANUAL_COUNT && t.getQuantity().doubleValue()==7));
        new WasteService(new WasteRecordDao(),service).recordWaste(new WasteRecord(saved.getId(),new BigDecimal("7"),WasteRecord.Reason.SPOILED,null,LocalDateTime.now(),"Regression fixture"),1L);
        after=service.getFoodItemById(saved.getId()).orElseThrow();
        assertEquals(0,after.getQuantity().doubleValue());assertEquals(8,after.getTotalQuantity().doubleValue());
        var preserved=service.getItemStockHistory(saved.getId());
        assertTrue(preserved.size()>history.size());
        for(var transaction:history) assertTrue(preserved.stream().anyMatch(t->Objects.equals(t.getId(),transaction.getId())));
    }
    @Test void dispatchTotalsSeparateStatusUnitsAndMoney() {
        var stats=RedistributionService.summarizeDispatches(List.of(
            dispatch(1,25.5,"pcs",Redistribution.Status.COMPLETED),dispatch(1,30,"pcs",Redistribution.Status.COMPLETED),
            dispatch(2,30,"kg",Redistribution.Status.PENDING),dispatch(2,90,"kg",Redistribution.Status.CANCELLED),
            dispatch(2,10,"kg",Redistribution.Status.CONFIRMED)),Map.of(1L,item(1,"pcs",0,1,1000),2L,item(2,"kg",70,1,2000)),4);
        assertEquals(Map.of("pcs",55.5),stats.get("completedQuantityByUnit"));
        assertEquals(Map.of("kg",30.0),stats.get("pendingQuantityByUnit"));
        assertEquals(55500.0,stats.get("confirmedValueSaved"));assertEquals(60000.0,stats.get("pendingRedistributionValue"));
        assertEquals(1,stats.get("pendingDispatchesCount"));assertEquals(2,stats.get("completedDispatchesCount"));
        assertEquals(0,((BigDecimal)stats.get("quantityRedistributedKg")).doubleValue());
    }
    @Test void canonicalForecastConservesStockReconcilesAllViewsAndKeepsCurrentRisk() throws Exception {
        var service=forecast(); var inventory=List.of(item(1,"kg",70,1,2000),item(2,"liter",10,1,2000),item(3,"kg",20,10,2000));
        var report=service.assessInventory(inventory);
        var details=(List<Map<String,Object>>)report.get("forecastItems");
        Map<String,Double> totals=new LinkedHashMap<>();
        for(var detail:details) {
            double previous=((Number)detail.get("currentStock")).doubleValue(),sum=0;
            for(var day:(List<Map<String,Object>>)detail.get("dailyForecast")) {
                double opening=((Number)day.get("projectedOpeningStock")).doubleValue();
                double sales=((Number)day.get("predictedSales")).doubleValue(),waste=((Number)day.get("predictedWaste")).doubleValue();
                double closing=((Number)day.get("projectedClosingStock")).doubleValue();
                assertTrue(previous>0,"No days may resurrect depleted stock"); assertEquals(previous,opening,1e-8);
                assertTrue(closing>=0); assertEquals(opening,sales+waste+closing,1e-8);previous=closing;sum+=waste;
            }
            assertEquals(sum,((Number)detail.get("sevenDayPredictedWaste")).doubleValue(),1e-8);
            if(sum>0)totals.merge((String)detail.get("unit"),sum,Double::sum);
        }
        assertEquals(totals,report.get("predictedWasteByUnit"));
        assertEquals(totals,((Map<?,?>)report.get("weeklySummary")).get("predictedWasteByUnit"));
        assertEquals(0.0,details.get(1).get("sevenDayPredictedWaste"));assertEquals("NO_SURPLUS",details.get(1).get("redistributionStatus"));
        assertEquals(0.0,details.get(1).get("projectedSurplus")); assertEquals("HIGH",details.get(1).get("riskLevel"));
        assertEquals(85.0,details.get(0).get("riskScore")); assertEquals(85.0,details.get(1).get("riskScore"));
        assertEquals("MEDIUM",details.get(2).get("riskLevel")); assertEquals(50.0,details.get(2).get("riskScore"));
        var tomorrow=(List<PrologAssessment>)((Map<?,?>)report.get("tomorrowPrediction")).get("items");
        var batches=service.assessTomorrowBatches(inventory);
        for(int i=0;i<2;i++) {
            assertEquals(details.get(i).get("sevenDayPredictedWaste"),tomorrow.get(i).getPredictedWasteQuantity());
            assertEquals(details.get(i).get("sevenDayPredictedWaste"),batches.get(i).get("predictedWasteQuantity"));
        }
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/consistency-forecast-fixture.json"),JsonAccess.serialize(report));
    }
    @Test void emptyAndZeroForecastAreDistinct() throws Exception {
        assertEquals("NO_INVENTORY",forecast().assessInventory(List.of()).get("forecastStatus"));
        assertEquals("ZERO_FORECAST",forecast().assessInventory(List.of(item(2,"liter",10,1,2000))).get("forecastStatus"));
    }
    @Test void reportDateBoundariesUseYangonDayAndExcludeNextMidnight() throws Exception {
        FoodItemService service=new FoodItemService();
        FoodItem fresh=item(999,"kg",20,10,1000);fresh.setId(null);fresh.setName("Timezone consistency " + UUID.randomUUID());
        var saved=service.createFoodItem(fresh,1L);
        var sales=new SalesService(new SalesDao(),service);var waste=new WasteService(new WasteRecordDao(),service);
        var dates=List.of(LocalDateTime.of(2030,1,1,17,29,59),LocalDateTime.of(2030,1,1,17,30),
            LocalDateTime.of(2030,1,2,17,29,59),LocalDateTime.of(2030,1,2,17,30));
        for(var date:dates) {
            sales.recordSale(new Sale(saved.getId(),BigDecimal.ONE,BigDecimal.valueOf(1000),BigDecimal.valueOf(1000),1,date),1L);
            waste.recordWaste(new WasteRecord(saved.getId(),BigDecimal.ONE,WasteRecord.Reason.SPOILED,null,date,"Timezone fixture"),1L);
        }
        var day=LocalDate.of(2030,1,2);
        var selectedSales=new SalesDao().findByDateRange(day,day).stream().filter(row->saved.getId().equals(row.getFoodItemId())).toList();
        var selectedWaste=new WasteRecordDao().findByDateRange(day,day).stream().filter(row->saved.getId().equals(row.getFoodItemId())).toList();
        assertEquals(2,selectedSales.size());assertEquals(2,selectedWaste.size());
        assertEquals(Set.of(dates.get(1),dates.get(2)),new HashSet<>(selectedSales.stream().map(Sale::getSaleDate).toList()));
        assertEquals(Set.of(dates.get(1),dates.get(2)),new HashSet<>(selectedWaste.stream().map(WasteRecord::getWasteDate).toList()));
    }
    @Test void nonzeroDifferentUnitsRemainSeparate() throws Exception {
        var report=forecast().assessInventory(List.of(item(1,"kg",70,1,1000),item(2,"liter",100,1,1000),item(3,"pcs",100,1,1000)));
        var units=(Map<String,Double>)report.get("predictedWasteByUnit");
        assertEquals(Set.of("kg","liter","pcs"),units.keySet());assertEquals(3,((List<?>)report.get("quantities")).size());
        assertTrue(((Map<?,?>)report.get("weeklySummary")).get("predictedWaste") instanceof Map);
    }
    private static class JsonAccess extends BaseServlet {static String serialize(Object value){return gson.toJson(value);}}
}
