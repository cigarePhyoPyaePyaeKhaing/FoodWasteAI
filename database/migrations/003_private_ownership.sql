-- Apply only after a verified export and removal of legacy operational data.
-- No user is assigned historical records. Keep redistribution_recipients intact.
-- MySQL 8; users.id and food_items.id must be signed BIGINT as in schema.sql.
-- Parent ownership: sales, waste_records, inventory_transactions,
-- recommendations and redistributions inherit food_items.user_id.
-- Prediction items must reference a prediction and food item of the same owner;
-- the insertion DAO must validate both parents.
DELIMITER $$
CREATE PROCEDURE require_empty_private_tables()
BEGIN
  IF (SELECT COUNT(*) FROM food_items) <> 0
    OR (SELECT COUNT(*) FROM predictions) <> 0
    OR (SELECT COUNT(*) FROM prediction_items) <> 0
    OR (SELECT COUNT(*) FROM recommendations) <> 0
    OR (SELECT COUNT(*) FROM sales) <> 0
    OR (SELECT COUNT(*) FROM waste_records) <> 0
    OR (SELECT COUNT(*) FROM inventory_transactions) <> 0
    OR (SELECT COUNT(*) FROM redistributions) <> 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Export and archive legacy operational rows before migration';
  END IF;
END$$
DELIMITER ;
CALL require_empty_private_tables();
DROP PROCEDURE require_empty_private_tables;

ALTER TABLE food_items
  ADD COLUMN user_id BIGINT NOT NULL,
  ADD INDEX idx_food_owner_expiry (user_id, expiry_date),
  ADD CONSTRAINT fk_food_owner FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE predictions
  ADD COLUMN user_id BIGINT NOT NULL,
  ADD INDEX idx_prediction_owner_date (user_id, prediction_date),
  ADD CONSTRAINT fk_prediction_owner FOREIGN KEY (user_id) REFERENCES users(id);
