package com.foodwasteai;

import com.foodwasteai.model.FoodItem;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Owner of the legacy regression fixtures, confined to the disposable test database. */
class OwnedFoodItemFixture extends FoodItem {
    OwnedFoodItemFixture() { setUserId(1L); }
    OwnedFoodItemFixture(Long id, String name, String category, BigDecimal quantity, String unit,
                         BigDecimal price, LocalDate expiry) {
        super(id,name,category,quantity,unit,price,expiry); setUserId(1L);
    }
    OwnedFoodItemFixture(Long id, String name, String category, BigDecimal quantity, String unit,
                         BigDecimal price, LocalDate expiry, BigDecimal threshold) {
        super(id,name,category,quantity,unit,price,expiry,threshold); setUserId(1L);
    }
}
