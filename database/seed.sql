-- =====================================================================
-- FoodWaste AI - Production System Seed Data
-- Only initializes system defaults and reference tables.
-- =====================================================================

USE foodwaste_ai;

-- Accounts are created only through registration.

-- 2. VERIFIED REDISTRIBUTION CHARITY PARTNERS (Master Directory)
INSERT INTO redistribution_recipients (id, name, organization_type, contact_person, phone, email, address, active) VALUES
(1, 'Hope Community Food Bank', 'Food Bank', 'Daw Khin Win', '+95 9 450012345', 'contact@hopefoodbank.org', '124 Inya Road, Kamayut, Yangon', TRUE),
(2, 'City Youth Shelter & Kitchen', 'Soup Kitchen', 'U Min Naing', '+95 9 790098765', 'kitchen@cityshelter.org', '45 Merchant Street, Kyauktada, Yangon', TRUE),
(3, 'GreenEarth Animal Sanctuary', 'Animal Rescue', 'Ma Thin Thin', '+95 9 260055443', 'info@greenearthrescue.org', '88 Htauk Kyant Road, Mingaladon', TRUE),
(4, 'Circular BioCompost Hub', 'Composting', 'Ko Thet Aung', '+95 9 310022110', 'ops@biocomposthub.com', 'Plot 12, Industrial Zone, South Dagon', TRUE)
ON DUPLICATE KEY UPDATE name=VALUES(name);
