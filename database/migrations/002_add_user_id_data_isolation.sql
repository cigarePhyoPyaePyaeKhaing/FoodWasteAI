-- =====================================================================
-- FoodWaste AI - Migration 002: Multi-Tenant User Data Isolation
-- Safely adds user_id to business entity tables with backward compatibility.
-- Existing production records are safely attributed to user ID 1 (default admin).
-- =====================================================================

-- 1. Add user_id to food_items (inventory)
ALTER TABLE food_items 
    ADD COLUMN IF NOT EXISTS user_id BIGINT NULL,
    ADD INDEX idx_food_user (user_id);

-- 2. Add user_id to sales
ALTER TABLE sales 
    ADD COLUMN IF NOT EXISTS user_id BIGINT NULL,
    ADD INDEX idx_sales_user (user_id);

-- 3. Add user_id to waste_records
ALTER TABLE waste_records 
    ADD COLUMN IF NOT EXISTS user_id BIGINT NULL,
    ADD INDEX idx_waste_user (user_id);

-- 4. Add user_id to predictions
ALTER TABLE predictions 
    ADD COLUMN IF NOT EXISTS user_id BIGINT NULL,
    ADD INDEX idx_pred_user (user_id);

-- 5. Add user_id to recommendations
ALTER TABLE recommendations 
    ADD COLUMN IF NOT EXISTS user_id BIGINT NULL,
    ADD INDEX idx_rec_user (user_id);

-- 6. Safe backfill: Preserve all existing data by assigning to default admin user (ID: 1)
UPDATE food_items SET user_id = 1 WHERE user_id IS NULL;
UPDATE sales SET user_id = 1 WHERE user_id IS NULL;
UPDATE waste_records SET user_id = 1 WHERE user_id IS NULL;
UPDATE predictions SET user_id = 1 WHERE user_id IS NULL;
UPDATE recommendations SET user_id = 1 WHERE user_id IS NULL;
