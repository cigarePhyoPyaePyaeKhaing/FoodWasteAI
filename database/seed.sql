-- =====================================================================
-- FoodWaste AI - Production System Seed Data
-- Only initializes system defaults and reference tables.
-- =====================================================================

USE foodwaste_ai;

-- 1. SYSTEM USERS (Passwords hashed with BCrypt)
-- admin: admin123  |  staff: staff123  |  staff_sarah: staff123
INSERT INTO users (id, username, email, password_hash, full_name, role, active) VALUES
(1, 'admin', 'manager@foodwaste.ai', '$2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa', 'Restaurant Manager (Admin)', 'ADMIN', TRUE),
(2, 'staff', 'staff@foodwaste.ai', '$2a$10$yXfV6K4bF0Lz2BqK7kE1Vu5fE2Xz1Q3mG4c8F5pG7qE9iL2hO8u4W', 'Kitchen Staff', 'STAFF', TRUE),
(3, 'staff_sarah', 'sarah@foodwaste.ai', '$2a$10$yXfV6K4bF0Lz2BqK7kE1Vu5fE2Xz1Q3mG4c8F5pG7qE9iL2hO8u4W', 'Sarah Jenkins (Kitchen Staff)', 'STAFF', TRUE)
ON DUPLICATE KEY UPDATE full_name=VALUES(full_name), password_hash=VALUES(password_hash);

-- 2. VERIFIED REDISTRIBUTION CHARITY PARTNERS (Master Directory)
INSERT INTO redistribution_recipients (id, name, organization_type, contact_person, phone, email, address, active) VALUES
(1, 'Hope Community Food Bank', 'Food Bank', 'Daw Khin Win', '+95 9 450012345', 'contact@hopefoodbank.org', '124 Inya Road, Kamayut, Yangon', TRUE),
(2, 'City Youth Shelter & Kitchen', 'Soup Kitchen', 'U Min Naing', '+95 9 790098765', 'kitchen@cityshelter.org', '45 Merchant Street, Kyauktada, Yangon', TRUE),
(3, 'GreenEarth Animal Sanctuary', 'Animal Rescue', 'Ma Thin Thin', '+95 9 260055443', 'info@greenearthrescue.org', '88 Htauk Kyant Road, Mingaladon', TRUE),
(4, 'Circular BioCompost Hub', 'Composting', 'Ko Thet Aung', '+95 9 310022110', 'ops@biocomposthub.com', 'Plot 12, Industrial Zone, South Dagon', TRUE)
ON DUPLICATE KEY UPDATE name=VALUES(name);
