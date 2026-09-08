package com.foodwasteai;

import com.foodwasteai.util.*;
import com.foodwasteai.controller.BaseServlet;
import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.*;
import com.foodwasteai.model.*;
import com.foodwasteai.service.*;
import com.foodwasteai.prolog.PrologService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class MyanmarTimeTest extends BaseServlet {
    @ParameterizedTest @ValueSource(strings={"UTC","America/Los_Angeles","Asia/Tokyo"})
    void calendarAndWireContractIgnoreDefaultZone(String zone) throws Exception {
        TimeZone previous=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            Clock clock=Clock.fixed(Instant.parse("2026-09-08T18:00:00Z"), ZoneId.of(zone));
            LocalDate today=AppTime.today(clock);
            assertEquals(ZoneOffset.ofHoursMinutes(6,30),AppTime.APP_ZONE.getRules().getOffset(clock.instant()));
            assertEquals(LocalDate.of(2026,9,9),today);
            assertEquals(0,ExpiryStatusResolver.calculateDaysRemaining(today,today));
            assertEquals(1,ExpiryStatusResolver.calculateDaysRemaining(today.plusDays(1),today));
            assertEquals(-1,ExpiryStatusResolver.calculateDaysRemaining(today.minusDays(1),today));
            assertEquals(LocalDateTime.parse("2026-09-08T17:30:00"),AppTime.startOfDayUtc(today));
            LocalDateTime utc=AppTime.utcNow(clock);
            assertEquals(today,AppTime.businessDate(utc));
            assertEquals("\"2026-09-08T18:00Z\"",gson.toJson(utc,LocalDateTime.class));
            assertEquals(utc,gson.fromJson("\"2026-09-09T00:30:00+06:30\"",LocalDateTime.class));
            Redistribution pickup=new Redistribution();pickup.setPickupTime(LocalDateTime.parse("2026-09-09T00:30:00"));pickup.setCreatedAt(utc);
            String json=gson.toJson(pickup);
            assertTrue(json.contains("2026-09-09T00:30+06:30"));
            assertEquals(pickup.getPickupTime(),gson.fromJson(json,Redistribution.class).getPickupTime());
            PredictionService predictions=new PredictionService(new PrologService(),new FoodItemService(),new SalesDao(),new WasteRecordDao(),new PredictionDao(),null,clock);
            Map<String,Object> report=predictions.assessInventory(Collections.emptyList());
            assertEquals("2026-09-10",report.get("forecastStartDate"));
            assertEquals("2026-09-16",report.get("forecastEndDate"));
            assertEquals(7,((List<?>)report.get("days")).size());
            assertEquals(clock.instant().toString(),report.get("generatedAt"));
        } finally { TimeZone.setDefault(previous); }
    }

    @ParameterizedTest @ValueSource(strings={"UTC","America/Los_Angeles"})
    void jdbcEventsAndTodayRangesUseUtcStorageAndYangonBoundaries(String zone) throws Exception {
        TimeZone previous=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            FoodItem food=new FoodItem(null,"Timezone boundary "+UUID.randomUUID(),"Produce",BigDecimal.TEN,"kg",BigDecimal.ONE,AppTime.today().plusDays(10));
            food.setUserId(1L);new FoodItemDao().save(food);
            LocalDate day=LocalDate.of(2026,9,9);LocalDateTime start=AppTime.startOfDayUtc(day),end=AppTime.startOfDayUtc(day.plusDays(1));
            try(var conn=DatabaseConfig.getConnection()) {
                try(var stmt=conn.createStatement();var rs=stmt.executeQuery("SELECT @@session.time_zone")){assertTrue(rs.next());assertEquals("+00:00",rs.getString(1));}
                for(LocalDateTime event:List.of(start.minusSeconds(1),start,end.minusSeconds(1),end)) {
                    try(var stmt=conn.prepareStatement("INSERT INTO sales(food_item_id,quantity_sold,unit_price,total_amount,sale_date) VALUES (?,1,1,1,?)")){stmt.setLong(1,food.getId());stmt.setObject(2,event);stmt.executeUpdate();}
                    try(var stmt=conn.prepareStatement("INSERT INTO waste_records(food_item_id,quantity_wasted,reason,monetary_loss,waste_date) VALUES (?,1,'SPOILED',1,?)")){stmt.setLong(1,food.getId());stmt.setObject(2,event);stmt.executeUpdate();}
                }
            }
            var sales=new SalesDao().findByDateRange(day,day,1L).stream().filter(s->food.getId().equals(s.getFoodItemId())).toList();
            var waste=new WasteRecordDao().findByDateRange(day,day,1L).stream().filter(w->food.getId().equals(w.getFoodItemId())).toList();
            assertEquals(2,sales.size());assertEquals(2,waste.size());
            for(var sale:sales)assertEquals(day,AppTime.businessDate(sale.getSaleDate()));
            for(var record:waste)assertEquals(day,AppTime.businessDate(record.getWasteDate()));
        } finally { TimeZone.setDefault(previous); }
    }
}
