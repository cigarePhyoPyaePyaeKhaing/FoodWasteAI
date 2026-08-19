-- =====================================================================
-- FoodWaste AI - MySQL Database Schema
-- Compatible with Aiven Cloud MySQL & Local MySQL 8.x
-- =====================================================================

CREATE DATABASE IF NOT EXISTS foodwaste_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE foodwaste_ai;

-- 1. USERS TABLE
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    role ENUM('ADMIN', 'STAFF') NOT NULL DEFAULT 'STAFF',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_role (role),
    INDEX idx_user_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. FOOD ITEMS (INVENTORY) TABLE
CREATE TABLE IF NOT EXISTS food_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL, -- e.g. Poultry, Produce, Seafood, Dairy, Grains, Bakery
    quantity DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    unit VARCHAR(20) NOT NULL, -- kg, g, liters, portions, units
    price_per_unit DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    expiry_date DATE NOT NULL,
    min_stock_threshold DECIMAL(10, 2) NOT NULL DEFAULT 5.00,
    status ENUM('OK', 'NEAR_EXPIRY', 'EXPIRED', 'LOW_STOCK') NOT NULL DEFAULT 'OK',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_food_category (category),
    INDEX idx_food_expiry (expiry_date),
    INDEX idx_food_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. INVENTORY TRANSACTIONS
CREATE TABLE IF NOT EXISTS inventory_transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    food_item_id BIGINT NOT NULL,
    transaction_type ENUM('PURCHASE', 'USAGE', 'WASTE_ADJUSTMENT', 'REDISTRIBUTION', 'MANUAL_COUNT') NOT NULL,
    quantity DECIMAL(10, 2) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    notes VARCHAR(255),
    created_by BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (food_item_id) REFERENCES food_items(id) ON DELETE CASCADE,
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_inv_tx_type (transaction_type),
    INDEX idx_inv_tx_date (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. SALES RECORDS TABLE
CREATE TABLE IF NOT EXISTS sales (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    food_item_id BIGINT NOT NULL,
    quantity_sold DECIMAL(10, 2) NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL,
    total_amount DECIMAL(10, 2) NOT NULL,
    customer_count INT DEFAULT 1,
    sale_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (food_item_id) REFERENCES food_items(id) ON DELETE CASCADE,
    INDEX idx_sales_date (sale_date),
    INDEX idx_sales_food (food_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. WASTE RECORDS TABLE
CREATE TABLE IF NOT EXISTS waste_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    food_item_id BIGINT NOT NULL,
    quantity_wasted DECIMAL(10, 2) NOT NULL,
    reason ENUM('EXPIRED', 'OVERPRODUCTION', 'UNSOLD', 'SPOILED', 'DAMAGED', 'PREPARATION_WASTE', 'OTHER') NOT NULL,
    monetary_loss DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    waste_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (food_item_id) REFERENCES food_items(id) ON DELETE CASCADE,
    INDEX idx_waste_reason (reason),
    INDEX idx_waste_date (waste_date),
    INDEX idx_waste_food (food_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. PREDICTIONS (BATCH RUNS) TABLE
CREATE TABLE IF NOT EXISTS predictions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prediction_date DATE NOT NULL,
    overall_risk_score DECIMAL(5, 2) NOT NULL DEFAULT 0.00, -- 0-100%
    expected_total_waste_kg DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    estimated_money_lost DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    potential_savings DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    status ENUM('GENERATED', 'APPLIED', 'ARCHIVED') NOT NULL DEFAULT 'GENERATED',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pred_date (prediction_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. PREDICTION ITEMS (DETAILED BREAKDOWN BY FOOD)
CREATE TABLE IF NOT EXISTS prediction_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prediction_id BIGINT NOT NULL,
    food_item_id BIGINT NOT NULL,
    current_stock DECIMAL(10, 2) NOT NULL,
    expected_demand DECIMAL(10, 2) NOT NULL,
    expiry_days INT NOT NULL,
    historical_waste_rate DECIMAL(5, 4) NOT NULL DEFAULT 0.0000,
    risk_level ENUM('LOW', 'MEDIUM', 'HIGH') NOT NULL,
    risk_percentage DECIMAL(5, 2) NOT NULL,
    predicted_waste_qty DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    recommended_production DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    priority_usage VARCHAR(50) DEFAULT 'STANDARD',
    reasoning_text TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (prediction_id) REFERENCES predictions(id) ON DELETE CASCADE,
    FOREIGN KEY (food_item_id) REFERENCES food_items(id) ON DELETE CASCADE,
    INDEX idx_pred_item_risk (risk_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. AI RECOMMENDATIONS TABLE
CREATE TABLE IF NOT EXISTS recommendations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    food_item_id BIGINT NOT NULL,
    category ENUM('URGENT', 'IMPORTANT', 'OPTIMIZATION', 'REDISTRIBUTION') NOT NULL,
    risk_level ENUM('LOW', 'MEDIUM', 'HIGH') NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    reasoning_details TEXT,
    estimated_savings DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    status ENUM('PENDING', 'ACCEPTED', 'DISMISSED', 'COMPLETED') NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (food_item_id) REFERENCES food_items(id) ON DELETE CASCADE,
    INDEX idx_rec_category (category),
    INDEX idx_rec_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. REDISTRIBUTION RECIPIENTS (NGOs, Shelters, Community Centers)
CREATE TABLE IF NOT EXISTS redistribution_recipients (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    organization_type VARCHAR(100) NOT NULL, -- Food Bank, Soup Kitchen, Animal Shelter, Compost Partner
    contact_person VARCHAR(100) NOT NULL,
    phone VARCHAR(50) NOT NULL,
    email VARCHAR(100),
    address TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. REDISTRIBUTION DISPATCHES
CREATE TABLE IF NOT EXISTS redistributions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    food_item_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    quantity DECIMAL(10, 2) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    pickup_time DATETIME NOT NULL,
    status ENUM('PENDING', 'CONFIRMED', 'COLLECTED', 'COMPLETED', 'CANCELLED') NOT NULL DEFAULT 'PENDING',
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (food_item_id) REFERENCES food_items(id) ON DELETE CASCADE,
    FOREIGN KEY (recipient_id) REFERENCES redistribution_recipients(id) ON DELETE CASCADE,
    INDEX idx_redist_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
