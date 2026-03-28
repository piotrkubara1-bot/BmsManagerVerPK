import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BmsApiServer {
	private static final int DEFAULT_PORT = 8090;
	private static final int MAX_HISTORY = 5000;
	private static final int MAX_EVENTS = 2000;

	private static final Object LOCK = new Object();
	private static final Map<Integer, BmsReading> latestByModule = new ConcurrentHashMap<>();
	private static final Deque<BmsReading> history = new ArrayDeque<>();
	private static final Deque<BmsEvent> events = new ArrayDeque<>();
	private static final Set<Integer> allowedModules = new HashSet<>();
	private static final Map<Integer, Map<String, Double>> cellSettingsByModule = new ConcurrentHashMap<>();

	private static final SettingDefinition[] SETTING_DEFINITIONS = new SettingDefinition[] {
		new SettingDefinition("fully_charged_voltage_v", "Fully Charged Voltage", "V", 3.0, 4.5, 4.2, true),
		new SettingDefinition("charge_finished_current_a", "Charge Finished Current", "A", 0.1, 20.0, 2.0, true),
		new SettingDefinition("early_balancing_threshold_v", "Early Balancing Threshold", "V", 3.0, 4.5, 3.7, true),
		new SettingDefinition("allowed_disbalance_mv", "Allowed Disbalance", "mV", 1.0, 500.0, 30.0, true),
		new SettingDefinition("series_cells", "Number of Series Cells", "count", 1.0, 32.0, 16.0, false)
	};

	private static volatile Connection dbConnection;

	public static void main(String[] args) throws IOException {
		int port = parseIntEnv("BMS_API_PORT", DEFAULT_PORT);
		allowedModules.addAll(parseAllowedModules(System.getenv("BMS_ALLOWED_MODULES")));
		initSettingsDefaults();
		initDatabase();
		loadSettingsFromDb();
		persistAllSettingsIfMissing();

		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/api/health", new HealthHandler());
		server.createContext("/api/ingest", new IngestHandler());
		server.createContext("/api/latest", new LatestHandler());
		server.createContext("/api/history", new HistoryHandler());
		server.createContext("/api/events", new EventHandler());
		server.createContext("/api/statistics", new StatisticsHandler());
		server.createContext("/api/cell-settings", new CellSettingsHandler());
		server.setExecutor(null);

		System.out.println("[BmsApiServer] Listening on port " + port);
		if (dbConnection == null) {
			System.out.println("[BmsApiServer] Database disabled or unavailable. Running in memory-only mode.");
		}
		if (!allowedModules.isEmpty()) {
			System.out.println("[BmsApiServer] Allowed modules: " + allowedModules);
		}
		server.start();
	}

	private static void initDatabase() {
		String jdbcUrl = env("BMS_DB_URL", "jdbc:mysql://localhost:3306/bms?useSSL=false&allowPublicKeyRetrieval=true");
		String dbUser = env("BMS_DB_USER", "root");
		String dbPass = env("BMS_DB_PASS", "");

		try {
			dbConnection = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
			try (Statement statement = dbConnection.createStatement()) {
				statement.executeUpdate(
					"CREATE TABLE IF NOT EXISTS bms_readings (" +
						"id BIGINT PRIMARY KEY AUTO_INCREMENT," +
						"created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
						"module_id TINYINT NOT NULL," +
						"voltage_v DECIMAL(10,3) NOT NULL," +
						"current_a DECIMAL(10,3) NOT NULL," +
						"soc_percent DECIMAL(7,3) NOT NULL," +
						"status_code INT NOT NULL," +
						"raw_line TEXT NOT NULL" +
					")"
				);
				statement.executeUpdate(
					"CREATE TABLE IF NOT EXISTS bms_cell_readings (" +
						"id BIGINT PRIMARY KEY AUTO_INCREMENT," +
						"reading_id BIGINT NOT NULL," +
						"module_id TINYINT NOT NULL," +
						"cell_index INT NOT NULL," +
						"cell_mv INT NOT NULL," +
						"FOREIGN KEY (reading_id) REFERENCES bms_readings(id) ON DELETE CASCADE" +
					")"
				);
				statement.executeUpdate(
					"CREATE TABLE IF NOT EXISTS bms_events (" +
						"id BIGINT PRIMARY KEY AUTO_INCREMENT," +
						"created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
						"module_id TINYINT NOT NULL," +
						"event_code INT NOT NULL," +
						"severity VARCHAR(16) NOT NULL," +
						"message TEXT NOT NULL," +
						"raw_line TEXT NOT NULL" +
					")"
				);
				statement.executeUpdate(
					"CREATE TABLE IF NOT EXISTS bms_cell_settings (" +
						"module_id TINYINT NOT NULL," +
						"key_name VARCHAR(128) NOT NULL," +
						"label_name VARCHAR(128) NOT NULL," +
						"unit_name VARCHAR(32) NOT NULL," +
						"min_value DOUBLE NOT NULL," +
						"max_value DOUBLE NOT NULL," +
						"value_num DOUBLE NOT NULL," +
						"writable BOOLEAN NOT NULL," +
						"updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
						"PRIMARY KEY (module_id, key_name)" +
					")"
				);
			}
			System.out.println("[BmsApiServer] Database initialized.");
		} catch (Exception ex) {
			dbConnection = null;
			System.err.println("[BmsApiServer] DB init failed: " + ex.getMessage());
		}
	}

	private static void initSettingsDefaults() {
		for (int moduleId = 1; moduleId <= 4; moduleId++) {
			Map<String, Double> moduleMap = new ConcurrentHashMap<>();
			for (SettingDefinition definition : SETTING_DEFINITIONS) {
				moduleMap.put(definition.key, definition.defaultValue);
			}
			cellSettingsByModule.put(moduleId, moduleMap);
		}
	}

	private static void loadSettingsFromDb() {
		if (dbConnection == null) {
			return;
		}

		String sql = "SELECT module_id, key_name, value_num FROM bms_cell_settings";
		try (Statement stmt = dbConnection.createStatement();
			 java.sql.ResultSet rs = stmt.executeQuery(sql)) {
			while (rs.next()) {
				int moduleId = rs.getInt("module_id");
				String key = rs.getString("key_name");
				double value = rs.getDouble("value_num");
				if (moduleId >= 1 && moduleId <= 4 && getSettingDefinition(key) != null) {
					cellSettingsByModule.computeIfAbsent(moduleId, x -> new ConcurrentHashMap<>()).put(key, value);
				}
			}
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed loading settings from DB: " + ex.getMessage());
		}
	}

	private static void persistAllSettingsIfMissing() {
		if (dbConnection == null) {
			return;
		}
		for (int moduleId = 1; moduleId <= 4; moduleId++) {
			for (SettingDefinition definition : SETTING_DEFINITIONS) {
				double value = cellSettingsByModule.get(moduleId).get(definition.key);
				persistSetting(moduleId, definition, value);
			}
		}
	}

	private static void persistSetting(int moduleId, SettingDefinition definition, double value) {
		if (dbConnection == null) {
			return;
		}

		String sql =
			"INSERT INTO bms_cell_settings (module_id, key_name, label_name, unit_name, min_value, max_value, value_num, writable) " +
			"VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
			"ON DUPLICATE KEY UPDATE value_num = VALUES(value_num), label_name = VALUES(label_name), unit_name = VALUES(unit_name), " +
			"min_value = VALUES(min_value), max_value = VALUES(max_value), writable = VALUES(writable)";

		try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
			stmt.setInt(1, moduleId);
			stmt.setString(2, definition.key);
			stmt.setString(3, definition.label);
			stmt.setString(4, definition.unit);
			stmt.setDouble(5, definition.minValue);
			stmt.setDouble(6, definition.maxValue);
			stmt.setDouble(7, value);
			stmt.setBoolean(8, definition.writable);
			stmt.executeUpdate();
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed persisting setting: " + ex.getMessage());
		}
	}

	private static void ingestLine(String line) {
		String trimmed = line == null ? "" : line.trim();
		if (trimmed.isEmpty()) {
			return;
		}

		if (trimmed.startsWith("BMS")) {
			BmsReading reading = parseBms(trimmed);
			if (reading == null) {
				return;
			}
			if (!allowedModules.isEmpty() && !allowedModules.contains(reading.moduleId)) {
				return;
			}

			synchronized (LOCK) {
				latestByModule.put(reading.moduleId, reading);
				history.addFirst(reading);
				while (history.size() > MAX_HISTORY) {
					history.removeLast();
				}
			}
			persistReading(reading);
			return;
		}

		if (trimmed.startsWith("EVENT")) {
			BmsEvent event = parseEvent(trimmed);
			if (event == null) {
				return;
			}
			if (!allowedModules.isEmpty() && !allowedModules.contains(event.moduleId)) {
				return;
			}

			synchronized (LOCK) {
				events.addFirst(event);
				while (events.size() > MAX_EVENTS) {
					events.removeLast();
				}
			}
			persistEvent(event);
		}
	}

	private static BmsReading parseBms(String line) {
		String[] parts = line.split(",");
		if (parts.length < 5) {
			return null;
		}

		int moduleId = 1;
		int index = 1;
		if (parts.length >= 6 && isInteger(parts[1])) {
			int parsedModule = safeInt(parts[1], 1);
			if (parsedModule >= 1 && parsedModule <= 4) {
				moduleId = parsedModule;
				index = 2;
			}
		}

		if (parts.length < index + 4) {
			return null;
		}

		double voltage = safeDouble(parts[index], 0.0);
		double current = safeDouble(parts[index + 1], 0.0);
		double socRaw = safeDouble(parts[index + 2], 0.0);
		int statusCode = safeInt(parts[index + 3], 0);
		double socPercent = socRaw > 1000.0 ? socRaw / 1_000_000.0 : socRaw;

		List<Integer> cells = new ArrayList<>();
		for (int i = index + 4; i < parts.length; i++) {
			if (isInteger(parts[i])) {
				cells.add(safeInt(parts[i], 0));
			}
		}

		BmsReading reading = new BmsReading();
		reading.timestamp = Instant.now().toString();
		reading.moduleId = moduleId;
		reading.voltageV = voltage;
		reading.currentA = current;
		reading.socPercent = socPercent;
		reading.statusCode = statusCode;
		reading.cellMv = cells;
		reading.rawLine = line;
		return reading;
	}

	private static BmsEvent parseEvent(String line) {
		String[] parts = line.split(",");
		if (parts.length < 2) {
			return null;
		}

		int moduleId = 1;
		int index = 1;
		if (parts.length >= 3 && isInteger(parts[1])) {
			int parsedModule = safeInt(parts[1], 1);
			if (parsedModule >= 1 && parsedModule <= 4) {
				moduleId = parsedModule;
				index = 2;
			}
		}

		int eventCode = 0;
		if (index < parts.length && isInteger(parts[index])) {
			eventCode = safeInt(parts[index], 0);
			index++;
		}

		String severity = "INFO";
		if (index < parts.length) {
			String candidate = parts[index].trim().toUpperCase(Locale.ROOT);
			if ("INFO".equals(candidate) || "WARN".equals(candidate) || "ERROR".equals(candidate)) {
				severity = candidate;
				index++;
			}
		}

		StringBuilder messageBuilder = new StringBuilder();
		for (int i = index; i < parts.length; i++) {
			if (messageBuilder.length() > 0) {
				messageBuilder.append(',');
			}
			messageBuilder.append(parts[i]);
		}
		String message = messageBuilder.length() == 0 ? line : messageBuilder.toString().trim();

		BmsEvent event = new BmsEvent();
		event.timestamp = Instant.now().toString();
		event.moduleId = moduleId;
		event.eventCode = eventCode;
		event.severity = severity;
		event.message = message;
		event.rawLine = line;
		return event;
	}

	private static void persistReading(BmsReading reading) {
		if (dbConnection == null) {
			return;
		}

		String insertReading =
			"INSERT INTO bms_readings (module_id, voltage_v, current_a, soc_percent, status_code, raw_line) " +
			"VALUES (?, ?, ?, ?, ?, ?)";
		String insertCell =
			"INSERT INTO bms_cell_readings (reading_id, module_id, cell_index, cell_mv) VALUES (?, ?, ?, ?)";

		try (PreparedStatement stmtReading = dbConnection.prepareStatement(insertReading, Statement.RETURN_GENERATED_KEYS)) {
			stmtReading.setInt(1, reading.moduleId);
			stmtReading.setDouble(2, reading.voltageV);
			stmtReading.setDouble(3, reading.currentA);
			stmtReading.setDouble(4, reading.socPercent);
			stmtReading.setInt(5, reading.statusCode);
			stmtReading.setString(6, reading.rawLine);
			stmtReading.executeUpdate();

			long readingId = -1;
			try (java.sql.ResultSet keys = stmtReading.getGeneratedKeys()) {
				if (keys.next()) {
					readingId = keys.getLong(1);
				}
			}

			if (readingId > 0 && !reading.cellMv.isEmpty()) {
				try (PreparedStatement stmtCell = dbConnection.prepareStatement(insertCell)) {
					for (int i = 0; i < reading.cellMv.size(); i++) {
						stmtCell.setLong(1, readingId);
						stmtCell.setInt(2, reading.moduleId);
						stmtCell.setInt(3, i + 1);
						stmtCell.setInt(4, reading.cellMv.get(i));
						stmtCell.addBatch();
					}
					stmtCell.executeBatch();
				}
			}
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed to persist reading: " + ex.getMessage());
		}
	}

	private static void persistEvent(BmsEvent event) {
		if (dbConnection == null) {
			return;
		}

		String insert =
			"INSERT INTO bms_events (module_id, event_code, severity, message, raw_line) VALUES (?, ?, ?, ?, ?)";

		try (PreparedStatement stmt = dbConnection.prepareStatement(insert)) {
			stmt.setInt(1, event.moduleId);
			stmt.setInt(2, event.eventCode);
			stmt.setString(3, event.severity);
			stmt.setString(4, event.message);
			stmt.setString(5, event.rawLine);
			stmt.executeUpdate();
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed to persist event: " + ex.getMessage());
		}
	}

	private static class HealthHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			List<Integer> modules = new ArrayList<>(latestByModule.keySet());
			Collections.sort(modules);

			StringBuilder sb = new StringBuilder();
			sb.append("{\"status\":\"ok\",\"dbConnected\":").append(dbConnection != null).append(",\"modulesSeen\":[");
			for (int i = 0; i < modules.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(modules.get(i));
			}
			sb.append("],\"allowedModules\":[");
			List<Integer> allowed = new ArrayList<>(allowedModules);
			Collections.sort(allowed);
			for (int i = 0; i < allowed.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(allowed.get(i));
			}
			sb.append("]}");
			writeJson(exchange, 200, sb.toString());
		}
	}

	private static class IngestHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			String body = readBody(exchange.getRequestBody()).trim();
			if (body.isEmpty()) {
				writeJson(exchange, 400, "{\"error\":\"Empty request body\"}");
				return;
			}

			int accepted = 0;
			int rejected = 0;
			String[] lines = body.split("\\r?\\n");
			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.isEmpty()) {
					continue;
				}
				if (trimmed.startsWith("BMS") || trimmed.startsWith("EVENT")) {
					ingestLine(trimmed);
					accepted++;
				} else {
					rejected++;
				}
			}

			String response = "{\"accepted\":" + accepted + ",\"rejected\":" + rejected + "}";
			writeJson(exchange, 200, response);
		}
	}

	private static class LatestHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
			int moduleIdFilter = safeInt(query.get("moduleId"), 0);

			List<BmsReading> latest = new ArrayList<>(latestByModule.values());
			latest.sort(Comparator.comparingInt(a -> a.moduleId));
			if (moduleIdFilter > 0) {
				latest.removeIf(reading -> reading.moduleId != moduleIdFilter);
			}

			StringBuilder sb = new StringBuilder();
			sb.append('[');
			for (int i = 0; i < latest.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(latest.get(i).toJson());
			}
			sb.append(']');

			writeJson(exchange, 200, sb.toString());
		}
	}

	private static class HistoryHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
			int moduleId = safeInt(query.get("moduleId"), 0);
			int limit = safeInt(query.get("limit"), 200);
			if (limit < 1) {
				limit = 1;
			}
			if (limit > 2000) {
				limit = 2000;
			}

			List<BmsReading> snapshot = new ArrayList<>();
			synchronized (LOCK) {
				for (BmsReading item : history) {
					if (moduleId > 0 && item.moduleId != moduleId) {
						continue;
					}
					snapshot.add(item);
					if (snapshot.size() >= limit) {
						break;
					}
				}
			}

			StringBuilder sb = new StringBuilder();
			sb.append('[');
			for (int i = 0; i < snapshot.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(snapshot.get(i).toJson());
			}
			sb.append(']');
			writeJson(exchange, 200, sb.toString());
		}
	}

	private static class EventHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
			int limit = safeInt(query.get("limit"), 200);
			if (limit < 1) {
				limit = 1;
			}
			if (limit > 2000) {
				limit = 2000;
			}

			List<BmsEvent> snapshot = new ArrayList<>();
			synchronized (LOCK) {
				for (BmsEvent item : events) {
					snapshot.add(item);
					if (snapshot.size() >= limit) {
						break;
					}
				}
			}

			StringBuilder sb = new StringBuilder();
			sb.append('[');
			for (int i = 0; i < snapshot.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(snapshot.get(i).toJson());
			}
			sb.append(']');
			writeJson(exchange, 200, sb.toString());
		}
	}

	private static class StatisticsHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
			int moduleId = safeInt(query.get("moduleId"), 0);

			Map<Integer, ModuleStats> statsByModule = new LinkedHashMap<>();
			synchronized (LOCK) {
				for (BmsReading reading : history) {
					if (moduleId > 0 && reading.moduleId != moduleId) {
						continue;
					}
					ModuleStats stats = statsByModule.get(reading.moduleId);
					if (stats == null) {
						stats = new ModuleStats(reading.moduleId);
						statsByModule.put(reading.moduleId, stats);
					}
					stats.accept(reading);
				}
			}

			StringBuilder sb = new StringBuilder();
			sb.append('[');
			List<Integer> keys = new ArrayList<>(statsByModule.keySet());
			Collections.sort(keys);
			for (int i = 0; i < keys.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(statsByModule.get(keys.get(i)).toJson());
			}
			sb.append(']');
			writeJson(exchange, 200, sb.toString());
		}
	}

	private static class CellSettingsHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}

			if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				handleGet(exchange);
				return;
			}
			if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				handlePost(exchange);
				return;
			}

			writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
		}

		private void handleGet(HttpExchange exchange) throws IOException {
			Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
			int filterModule = safeInt(query.get("moduleId"), 0);

			StringBuilder sb = new StringBuilder();
			sb.append('[');
			boolean firstModule = true;
			for (int moduleId = 1; moduleId <= 4; moduleId++) {
				if (filterModule > 0 && moduleId != filterModule) {
					continue;
				}
				if (!firstModule) {
					sb.append(',');
				}
				firstModule = false;
				sb.append(moduleSettingsToJson(moduleId));
			}
			sb.append(']');
			writeJson(exchange, 200, sb.toString());
		}

		private void handlePost(HttpExchange exchange) throws IOException {
			String body = readBody(exchange.getRequestBody());
			Map<String, String> params = parseQuery(body);

			if (params.isEmpty()) {
				writeJson(exchange, 400, "{\"error\":\"Expected form body: moduleId,key,value\"}");
				return;
			}

			int moduleId = safeInt(params.get("moduleId"), 0);
			String key = params.get("key");
			double value = safeDouble(params.get("value"), Double.NaN);

			if (moduleId < 1 || moduleId > 4) {
				writeJson(exchange, 400, "{\"error\":\"moduleId must be between 1 and 4\"}");
				return;
			}
			if (!allowedModules.isEmpty() && !allowedModules.contains(moduleId)) {
				writeJson(exchange, 403, "{\"error\":\"moduleId blocked by BMS_ALLOWED_MODULES\"}");
				return;
			}
			if (key == null || key.trim().isEmpty()) {
				writeJson(exchange, 400, "{\"error\":\"key is required\"}");
				return;
			}

			SettingDefinition definition = getSettingDefinition(key.trim());
			if (definition == null) {
				writeJson(exchange, 400, "{\"error\":\"unknown setting key\"}");
				return;
			}
			if (!definition.writable) {
				writeJson(exchange, 400, "{\"error\":\"setting is read-only\"}");
				return;
			}
			if (Double.isNaN(value)) {
				writeJson(exchange, 400, "{\"error\":\"value must be numeric\"}");
				return;
			}
			if (value < definition.minValue || value > definition.maxValue) {
				writeJson(
					exchange,
					400,
					"{\"error\":\"value out of range\",\"min\":" + definition.minValue + ",\"max\":" + definition.maxValue + "}"
				);
				return;
			}

			cellSettingsByModule.computeIfAbsent(moduleId, ignored -> new ConcurrentHashMap<>()).put(definition.key, value);
			persistSetting(moduleId, definition, value);

			writeJson(exchange, 200, moduleSettingsToJson(moduleId));
		}
	}

	private static boolean handleCorsAndPreflight(HttpExchange exchange) throws IOException {
		Headers headers = exchange.getResponseHeaders();
		headers.add("Access-Control-Allow-Origin", "*");
		headers.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
		headers.add("Access-Control-Allow-Headers", "Content-Type");
		if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
			return true;
		}
		return false;
	}

	private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		Headers headers = exchange.getResponseHeaders();
		headers.set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(body);
		}
	}

	private static String readBody(InputStream is) throws IOException {
		byte[] bytes = is.readAllBytes();
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static String escapeJson(String value) {
		if (value == null) {
			return "";
		}
		return value
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r");
	}

	private static boolean isInteger(String input) {
		if (input == null || input.trim().isEmpty()) {
			return false;
		}
		try {
			Integer.parseInt(input.trim());
			return true;
		} catch (NumberFormatException ex) {
			return false;
		}
	}

	private static int safeInt(String input, int fallback) {
		if (input == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(input.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static double safeDouble(String input, double fallback) {
		if (input == null) {
			return fallback;
		}
		try {
			return Double.parseDouble(input.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.trim().isEmpty() ? fallback : value.trim();
	}

	private static SettingDefinition getSettingDefinition(String key) {
		if (key == null) {
			return null;
		}
		for (SettingDefinition definition : SETTING_DEFINITIONS) {
			if (definition.key.equals(key)) {
				return definition;
			}
		}
		return null;
	}

	private static String moduleSettingsToJson(int moduleId) {
		Map<String, Double> values = cellSettingsByModule.computeIfAbsent(moduleId, ignored -> new ConcurrentHashMap<>());

		StringBuilder sb = new StringBuilder();
		sb.append("{\"moduleId\":").append(moduleId).append(",\"settings\":[");
		for (int i = 0; i < SETTING_DEFINITIONS.length; i++) {
			SettingDefinition definition = SETTING_DEFINITIONS[i];
			if (i > 0) {
				sb.append(',');
			}
			double currentValue = values.containsKey(definition.key)
				? values.get(definition.key)
				: definition.defaultValue;
			sb.append("{\"key\":\"").append(escapeJson(definition.key)).append("\"");
			sb.append(",\"label\":\"").append(escapeJson(definition.label)).append("\"");
			sb.append(",\"unit\":\"").append(escapeJson(definition.unit)).append("\"");
			sb.append(",\"min\":").append(String.format(Locale.US, "%.4f", definition.minValue));
			sb.append(",\"max\":").append(String.format(Locale.US, "%.4f", definition.maxValue));
			sb.append(",\"value\":").append(String.format(Locale.US, "%.4f", currentValue));
			sb.append(",\"writable\":").append(definition.writable);
			sb.append('}');
		}
		sb.append("]}");
		return sb.toString();
	}

	private static Set<Integer> parseAllowedModules(String value) {
		Set<Integer> result = new HashSet<>();
		if (value == null || value.trim().isEmpty()) {
			return result;
		}
		String[] parts = value.split(",");
		for (String part : parts) {
			int module = safeInt(part, 0);
			if (module >= 1 && module <= 4) {
				result.add(module);
			}
		}
		return result;
	}

	private static int parseIntEnv(String name, int fallback) {
		return safeInt(System.getenv(name), fallback);
	}

	private static Map<String, String> parseQuery(String query) {
		Map<String, String> result = new HashMap<>();
		if (query == null || query.isEmpty()) {
			return result;
		}
		String[] pairs = query.split("&");
		for (String pair : pairs) {
			String[] kv = pair.split("=", 2);
			String key = urlDecode(kv[0]);
			String value = kv.length > 1 ? urlDecode(kv[1]) : "";
			result.put(key, value);
		}
		return result;
	}

	private static String urlDecode(String input) {
		return URLDecoder.decode(input, StandardCharsets.UTF_8);
	}

	private static class BmsReading {
		String timestamp;
		int moduleId;
		double voltageV;
		double currentA;
		double socPercent;
		int statusCode;
		List<Integer> cellMv;
		String rawLine;

		String toJson() {
			StringBuilder sb = new StringBuilder();
			sb.append("{\"timestamp\":\"").append(escapeJson(timestamp)).append("\"");
			sb.append(",\"moduleId\":").append(moduleId);
			sb.append(",\"voltageV\":").append(String.format(Locale.US, "%.3f", voltageV));
			sb.append(",\"currentA\":").append(String.format(Locale.US, "%.3f", currentA));
			sb.append(",\"socPercent\":").append(String.format(Locale.US, "%.3f", socPercent));
			sb.append(",\"statusCode\":").append(statusCode);
			sb.append(",\"cellsMv\":[");
			for (int i = 0; i < cellMv.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(cellMv.get(i));
			}
			sb.append(']');
			sb.append(",\"rawLine\":\"").append(escapeJson(rawLine)).append("\"}");
			return sb.toString();
		}
	}

	private static class SettingDefinition {
		final String key;
		final String label;
		final String unit;
		final double minValue;
		final double maxValue;
		final double defaultValue;
		final boolean writable;

		SettingDefinition(String key, String label, String unit, double minValue, double maxValue, double defaultValue, boolean writable) {
			this.key = key;
			this.label = label;
			this.unit = unit;
			this.minValue = minValue;
			this.maxValue = maxValue;
			this.defaultValue = defaultValue;
			this.writable = writable;
		}
	}

	private static class ModuleStats {
		final int moduleId;
		int sampleCount;
		double socSum;
		double minSoc = Double.MAX_VALUE;
		double maxSoc = -Double.MAX_VALUE;
		double minVoltage = Double.MAX_VALUE;
		double maxVoltage = -Double.MAX_VALUE;
		double minCurrent = Double.MAX_VALUE;
		double maxCurrent = -Double.MAX_VALUE;
		int lastStatusCode;
		String lastTimestamp = "";

		ModuleStats(int moduleId) {
			this.moduleId = moduleId;
		}

		void accept(BmsReading reading) {
			sampleCount++;
			socSum += reading.socPercent;
			minSoc = Math.min(minSoc, reading.socPercent);
			maxSoc = Math.max(maxSoc, reading.socPercent);
			minVoltage = Math.min(minVoltage, reading.voltageV);
			maxVoltage = Math.max(maxVoltage, reading.voltageV);
			minCurrent = Math.min(minCurrent, reading.currentA);
			maxCurrent = Math.max(maxCurrent, reading.currentA);
			lastStatusCode = reading.statusCode;
			lastTimestamp = reading.timestamp;
		}

		String toJson() {
			double avgSoc = sampleCount == 0 ? 0.0 : socSum / sampleCount;
			return "{\"moduleId\":" + moduleId +
				",\"sampleCount\":" + sampleCount +
				",\"avgSoc\":" + String.format(Locale.US, "%.3f", avgSoc) +
				",\"minSoc\":" + String.format(Locale.US, "%.3f", sampleCount == 0 ? 0.0 : minSoc) +
				",\"maxSoc\":" + String.format(Locale.US, "%.3f", sampleCount == 0 ? 0.0 : maxSoc) +
				",\"minVoltage\":" + String.format(Locale.US, "%.3f", sampleCount == 0 ? 0.0 : minVoltage) +
				",\"maxVoltage\":" + String.format(Locale.US, "%.3f", sampleCount == 0 ? 0.0 : maxVoltage) +
				",\"minCurrent\":" + String.format(Locale.US, "%.3f", sampleCount == 0 ? 0.0 : minCurrent) +
				",\"maxCurrent\":" + String.format(Locale.US, "%.3f", sampleCount == 0 ? 0.0 : maxCurrent) +
				",\"lastStatusCode\":" + lastStatusCode +
				",\"lastTimestamp\":\"" + escapeJson(lastTimestamp) + "\"}";
		}
	}

	private static class BmsEvent {
		String timestamp;
		int moduleId;
		int eventCode;
		String severity;
		String message;
		String rawLine;

		String toJson() {
			return "{\"timestamp\":\"" + escapeJson(timestamp) + "\"" +
				",\"moduleId\":" + moduleId +
				",\"eventCode\":" + eventCode +
				",\"severity\":\"" + escapeJson(severity) + "\"" +
				",\"message\":\"" + escapeJson(message) + "\"" +
				",\"rawLine\":\"" + escapeJson(rawLine) + "\"}";
		}
	}
}
