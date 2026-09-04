-- Migration 001: Non-destructive additions for 7-Day Prediction Forecast
-- FoodWaste AI

-- 1. Add forecast start/end date columns to predictions table if they do not exist
ALTER TABLE predictions 
    ADD COLUMN IF NOT EXISTS forecast_start_date DATE NULL AFTER prediction_date,
    ADD COLUMN IF NOT EXISTS forecast_end_date DATE NULL AFTER forecast_start_date;

-- 2. Add forecast_date and day_index to prediction_items table if they do not exist
ALTER TABLE prediction_items 
    ADD COLUMN IF NOT EXISTS forecast_date DATE NULL AFTER prediction_id,
    ADD COLUMN IF NOT EXISTS day_index INT NOT NULL DEFAULT 1 AFTER forecast_date;
