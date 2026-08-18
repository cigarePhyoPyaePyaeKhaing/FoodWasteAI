-- =====================================================================
-- FoodWaste AI - Initial Seed Data
-- =====================================================================

USE foodwaste_ai;

-- 1. SEED USERS (Password: 'admin123' / 'staff123' hashed with BCrypt)
-- $2a$10$wE99qF2y6/wS8hK2jO...
INSERT INTO users (username, email, password_hash, full_name, role, active) VALUES
('admin', 'manager@foodwaste.ai', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'Restaurant Manager (Admin)', 'ADMIN', TRUE),
('staff_sarah', 'sarah@foodwaste.ai', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'Sarah Jenkins (Kitchen Staff)', 'STAFF', TRUE)
ON DUPLICATE KEY UPDATE full_name=VALUES(full_name);

-- 2. SEED FOOD ITEMS
INSERT INTO food_items (id, name, category, quantity, unit, price_per_unit, expiry_date, min_stock_threshold, status) VALUES
(1, 'Fresh Chicken Breast', 'Poultry', 50.00, 'kg', 6500.00, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 15.00, 'NEAR_EXPIRY'),
(2, 'Organic Garden Salad Mix', 'Produce', 18.50, 'kg', 4200.00, DATE_ADD(CURDATE(), INTERVAL 2 DAY), 5.00, 'NEAR_EXPIRY'),
(3, 'Atlantic Salmon Fillet', 'Seafood', 12.00, 'kg', 18000.00, DATE_ADD(CURDATE(), INTERVAL 3 DAY), 4.00, 'OK'),
(4, 'Premium Jasmine Rice', 'Grains', 120.00, 'kg', 2800.00, DATE_ADD(CURDATE(), INTERVAL 60 DAY), 25.00, 'OK'),
(5, 'Pasteurized Whole Milk', 'Dairy', 30.00, 'liters', 3500.00, DATE_ADD(CURDATE(), INTERVAL 4 DAY), 10.00, 'OK'),
(6, 'Artisan Sliced Bread', 'Bakery', 25.00, 'units', 2200.00, DATE_ADD(CURDATE(), INTERVAL 2 DAY), 8.00, 'NEAR_EXPIRY'),
(7, 'Roma Tomatoes', 'Produce', 35.00, 'kg', 2500.00, DATE_ADD(CURDATE(), INTERVAL 5 DAY), 10.00, 'OK'),
(8, 'Fresh Hass Avocados', 'Produce', 15.00, 'kg', 8500.00, DATE_ADD(CURDATE(), INTERVAL 3 DAY), 5.00, 'OK'),
(9, 'Prime Beef Burger Patties', 'Meat', 40.00, 'units', 4500.00, DATE_ADD(CURDATE(), INTERVAL 6 DAY), 12.00, 'OK')
ON DUPLICATE KEY UPDATE quantity=VALUES(quantity);

-- 3. SEED REDISTRIBUTION RECIPIENTS
INSERT INTO redistribution_recipients (id, name, organization_type, contact_person, phone, email, address, active) VALUES
(1, 'Hope Community Food Bank', 'Food Bank', 'Daw Khin Win', '+95 9 450012345', 'contact@hopefoodbank.org', '124 Inya Road, Kamayut, Yangon', TRUE),
(2, 'City Youth Shelter & Kitchen', 'Soup Kitchen', 'U Min Naing', '+95 9 790098765', 'kitchen@cityshelter.org', '45 Merchant Street, Kyauktada, Yangon', TRUE),
(3, 'GreenEarth Animal Sanctuary', 'Animal Rescue', 'Ma Thin Thin', '+95 9 260055443', 'info@greenearthrescue.org', '88 Htauk Kyant Road, Mingaladon', TRUE),
(4, 'Circular BioCompost Hub', 'Composting', 'Ko Thet Aung', '+95 9 310022110', 'ops@biocomposthub.com', 'Plot 12, Industrial Zone, South Dagon', TRUE)
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- 4. SEED SAMPLE RECENT SALES
INSERT INTO sales (food_item_id, quantity_sold, unit_price, total_amount, customer_count, sale_date) VALUES
(1, 28.00, 6500.00, 182000.00, 45, DATE_SUB(NOW(), INTERVAL 1 DAY)),
(2, 14.00, 4200.00, 58800.00, 32, DATE_SUB(NOW(), INTERVAL 1 DAY)),
(3, 8.00, 18000.00, 144000.00, 20, DATE_SUB(NOW(), INTERVAL 1 DAY)),
(4, 25.00, 2800.00, 70000.00, 80, DATE_SUB(NOW(), INTERVAL 1 DAY)),
(1, 30.00, 6500.00, 195000.00, 50, DATE_SUB(NOW(), INTERVAL 2 DAY)),
(2, 12.00, 4200.00, 50400.00, 28, DATE_SUB(NOW(), INTERVAL 2 DAY)),
(1, 32.00, 6500.00, 208000.00, 55, DATE_SUB(NOW(), INTERVAL 3 DAY));

-- 5. SEED SAMPLE RECENT WASTE RECORDS
INSERT INTO waste_records (food_item_id, quantity_wasted, reason, monetary_loss, waste_date, notes) VALUES
(1, 4.50, 'OVERPRODUCTION', 29250.00, DATE_SUB(NOW(), INTERVAL 1 DAY), 'Prepared chicken surplus from lunch shift'),
(2, 3.20, 'EXPIRED', 13440.00, DATE_SUB(NOW(), INTERVAL 1 DAY), 'Salad leaves wilted and expired'),
(6, 6.00, 'UNSOLD', 13200.00, DATE_SUB(NOW(), INTERVAL 2 DAY), 'Unsold evening bread batch'),
(3, 1.50, 'SPOILED', 27000.00, DATE_SUB(NOW(), INTERVAL 3 DAY), 'Temperature fluctuation in cold storage drawer'),
(7, 2.00, 'PREPARATION_WASTE', 5000.00, DATE_SUB(NOW(), INTERVAL 4 DAY), 'Trimmings and bruised batch');

-- 6. SEED INITIAL PREDICTIONS & RECOMMENDATIONS
INSERT INTO predictions (id, prediction_date, overall_risk_score, expected_total_waste_kg, estimated_money_lost, potential_savings, status) VALUES
(1, CURDATE(), 68.50, 18.20, 98500.00, 35000.00, 'GENERATED');

INSERT INTO prediction_items (prediction_id, food_item_id, current_stock, expected_demand, expiry_days, historical_waste_rate, risk_level, risk_percentage, predicted_waste_qty, recommended_production, priority_usage, reasoning_text) VALUES
(1, 1, 50.00, 30.00, 1, 0.2200, 'HIGH', 82.00, 8.50, 25.00, 'IMMEDIATE_USE', 'Current stock substantially exceeds expected demand (50kg vs 30kg); Expiry is imminent (within 1 day); Historical waste rate is high.'),
(1, 2, 18.50, 12.00, 2, 0.2500, 'HIGH', 71.00, 4.20, 10.00, 'HIGH_PRIORITY', 'Organic salad mix expires in 2 days; Stock buffer is 154% above expected consumption.'),
(1, 3, 12.00, 9.00, 3, 0.1200, 'MEDIUM', 55.00, 1.80, 8.00, 'HIGH_PRIORITY', 'Salmon fillet approaching expiry threshold in 3 days; requires careful portion management.'),
(1, 4, 120.00, 80.00, 60, 0.0200, 'LOW', 35.00, 0.50, 80.00, 'STANDARD', 'Safe shelf life remaining; demand trajectory is highly stable.');

INSERT INTO recommendations (id, food_item_id, category, risk_level, title, description, reasoning_details, estimated_savings, status) VALUES
(1, 1, 'URGENT', 'HIGH', 'Reduce Tomorrow Chicken Production by 15%', 'Current chicken stock is 50 kg but expected demand is only 30 kg with 1-day expiry.', 'Prolog Expert System: Stock exceeds demand by 66%; imminent expiry requires immediate inventory exhaustion.', 25000.00, 'PENDING'),
(2, 2, 'IMPORTANT', 'HIGH', 'Prioritize Salad in Tomorrow Lunch Menu Special', 'Promote Caesar and Garden Salad combo specials to clear remaining 18.5 kg stock before expiry.', 'Prolog Expert System: Expiry within 48h with 25% historical spoilage rate.', 10000.00, 'PENDING'),
(3, 1, 'REDISTRIBUTION', 'HIGH', 'Dispatch Surplus Chicken to Hope Food Bank', 'If 10+ kg remains by 16:00, dispatch donation batch to verified community food bank.', 'Prolog Expert System: Eligible for food donation window (1 day prior to expiry).', 18000.00, 'PENDING');
