CREATE DATABASE IF NOT EXISTS bms;
USE bms;

CREATE TABLE IF NOT EXISTS bms_readings (
	id BIGINT PRIMARY KEY AUTO_INCREMENT,
	created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
	module_id TINYINT NOT NULL,
	voltage_v DECIMAL(10,3) NOT NULL,
	current_a DECIMAL(10,3) NOT NULL,
	soc_percent DECIMAL(7,3) NOT NULL,
	status_code INT NOT NULL,
	raw_line TEXT NOT NULL,
	INDEX idx_bms_readings_module_time (module_id, created_at)
);

CREATE TABLE IF NOT EXISTS bms_cell_readings (
	id BIGINT PRIMARY KEY AUTO_INCREMENT,
	reading_id BIGINT NOT NULL,
	module_id TINYINT NOT NULL,
	cell_index INT NOT NULL,
	cell_mv INT NOT NULL,
	FOREIGN KEY (reading_id) REFERENCES bms_readings(id) ON DELETE CASCADE,
	INDEX idx_bms_cell_readings_module (module_id)
);

CREATE TABLE IF NOT EXISTS bms_events (
	id BIGINT PRIMARY KEY AUTO_INCREMENT,
	created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
	module_id TINYINT NOT NULL,
	event_code INT NOT NULL,
	severity VARCHAR(16) NOT NULL,
	message TEXT NOT NULL,
	raw_line TEXT NOT NULL,
	INDEX idx_bms_events_module_time (module_id, created_at)
);

CREATE TABLE IF NOT EXISTS bms_cell_settings (
	module_id TINYINT NOT NULL,
	key_name VARCHAR(128) NOT NULL,
	label_name VARCHAR(128) NOT NULL,
	unit_name VARCHAR(32) NOT NULL,
	min_value DOUBLE NOT NULL,
	max_value DOUBLE NOT NULL,
	value_num DOUBLE NOT NULL,
	writable BOOLEAN NOT NULL,
	updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
	PRIMARY KEY (module_id, key_name)
);
