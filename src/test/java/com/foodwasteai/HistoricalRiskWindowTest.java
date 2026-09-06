package com.foodwasteai;

import com.foodwasteai.config.DatabaseConfig;
import com.foodwasteai.dao.SalesDao;
import com.foodwasteai.dao.WasteRecordDao;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalRiskWindowTest {
    @Test void historyExcludesOtherItemsOldRecordsAndFutureRecords() throws Exception {
        // Connection-local temporary tables shadow real history without modifying it.
        try (Connection real = DatabaseConfig.getConnection()) {
            Connection borrowed = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close")) return null;
                        try { return method.invoke(real,args); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    });
            try (var sql = real.createStatement()) {
                sql.execute("CREATE TEMPORARY TABLE sales (food_item_id BIGINT, quantity_sold DECIMAL(12,2), sale_date DATETIME)");
                sql.execute("CREATE TEMPORARY TABLE waste_records (food_item_id BIGINT, quantity_wasted DECIMAL(12,2), waste_date DATETIME)");
                try {
                    sql.execute("INSERT INTO sales VALUES (1,60,NOW()-INTERVAL 4 DAY),(1,20,NOW()-INTERVAL 4 DAY),"
                            + "(1,20,NOW()-INTERVAL 10 DAY),(1,900,NOW()-INTERVAL 20 DAY),"
                            + "(1,900,NOW()+INTERVAL 1 DAY),(2,900,NOW()-INTERVAL 1 DAY)");
                    sql.execute("INSERT INTO waste_records VALUES (1,900,NOW()-INTERVAL 20 DAY),"
                            + "(1,900,NOW()+INTERVAL 1 DAY),(2,900,NOW()-INTERVAL 1 DAY)");
                    var sales = new SalesDao() { @Override protected Connection getConnection() {return borrowed;} };
                    var waste = new WasteRecordDao() { @Override protected Connection getConnection() {return borrowed;} };
                    assertEquals(80.0/7,sales.getHistoricalAverageDailySales(1L,7).doubleValue(),0.000001);
                    assertEquals(0,waste.calculateHistoricalWasteRate(1L,14).compareTo(BigDecimal.ZERO));
                    sql.execute("INSERT INTO waste_records VALUES (1,25,NOW()-INTERVAL 10 DAY)");
                    assertEquals(new BigDecimal("0.2000"),waste.calculateHistoricalWasteRate(1L,14));
                } finally {
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS sales, waste_records");
                }
            }
        }
    }
}
