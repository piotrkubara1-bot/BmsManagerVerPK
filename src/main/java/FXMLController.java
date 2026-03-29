import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FXMLController {
	private static final String INPUT_MODE = env("GUI_INPUT_MODE", "api").toLowerCase();
	private static final String API_BASE = env("GUI_API_BASE", "http://127.0.0.1:8090");
	private static final int DEFAULT_MODULE_ID = parseDisplayModuleId();
	private static final int HISTORY_LIMIT = 400;

	@FXML
	private Label lblConnection;

	@FXML
	private Label lblVoltage;

	@FXML
	private Label lblCurrent;

	@FXML
	private Label lblSoc;

	@FXML
	private Label lblStatus;

	@FXML
	private Label lblLiveStatusLine;

	@FXML
	private ComboBox<String> cmbLiveModule;

	@FXML
	private Button btnRefreshEvents;

	@FXML
	private TableView<EventRow> tblEvents;

	@FXML
	private TableColumn<EventRow, String> colEventTime;

	@FXML
	private TableColumn<EventRow, String> colEventModule;

	@FXML
	private TableColumn<EventRow, String> colEventSeverity;

	@FXML
	private TableColumn<EventRow, String> colEventCode;

	@FXML
	private TableColumn<EventRow, String> colEventMessage;

	@FXML
	private ComboBox<String> cmbStatsModule;

	@FXML
	private ComboBox<String> cmbStatsRange;

	@FXML
	private Button btnRefreshStats;

	@FXML
	private Label lblStatAvgSoc;

	@FXML
	private Label lblStatSocRange;

	@FXML
	private Label lblStatVoltageRange;

	@FXML
	private Label lblStatCurrentRange;

	@FXML
	private Label lblStatPoints;

	@FXML
	private Label lblStatLastStatus;

	@FXML
	private NumberAxis axisTelemetryX;

	@FXML
	private NumberAxis axisTelemetryY;

	@FXML
	private LineChart<Number, Number> chartTelemetry;

	@FXML
	private Label lblRpiSummary;

	@FXML
	private TableView<SourceRow> tblRpiSources;

	@FXML
	private TableColumn<SourceRow, String> colSourceAddr;

	@FXML
	private TableColumn<SourceRow, String> colSourceLastSeen;

	@FXML
	private TableColumn<SourceRow, String> colSourceSeconds;

	@FXML
	private TableColumn<SourceRow, String> colSourceOnline;

	@FXML
	private TableColumn<SourceRow, String> colSourceAccepted;

	@FXML
	private TableColumn<SourceRow, String> colSourceLastModule;

	@FXML
	private TableView<ModuleRow> tblRpiModules;

	@FXML
	private TableColumn<ModuleRow, String> colModuleId;

	@FXML
	private TableColumn<ModuleRow, String> colModuleLastSeen;

	@FXML
	private TableColumn<ModuleRow, String> colModuleSeconds;

	@FXML
	private TableColumn<ModuleRow, String> colModuleOnline;

	private ScheduledExecutorService poller;
	private volatile boolean refreshRunning;

	public void initialize() {
		initCombos();
		initTables();
		initChart();
		wireActions();

		if ("stdin".equals(INPUT_MODE)) {
			lblConnection.setText("Input mode: stdin");
			startStdinReader();
			return;
		}

		lblConnection.setText("Input mode: API " + API_BASE);
		refreshFromApi();
		startApiPolling();
	}

	private void initCombos() {
		if (cmbLiveModule != null) {
			cmbLiveModule.getItems().setAll("1", "2", "3", "4");
			cmbLiveModule.setValue(String.valueOf(DEFAULT_MODULE_ID));
		}
		if (cmbStatsModule != null) {
			cmbStatsModule.getItems().setAll("1", "2", "3", "4");
			cmbStatsModule.setValue(String.valueOf(DEFAULT_MODULE_ID));
		}
		if (cmbStatsRange != null) {
			cmbStatsRange.getItems().setAll("5 min", "15 min", "30 min", "1 h", "3 h", "6 h", "12 h", "24 h");
			cmbStatsRange.setValue("1 h");
		}
	}

	private void initTables() {
		if (colEventTime != null) {
			colEventTime.setCellValueFactory(data -> data.getValue().timestampProperty());
			colEventModule.setCellValueFactory(data -> data.getValue().moduleProperty());
			colEventSeverity.setCellValueFactory(data -> data.getValue().severityProperty());
			colEventCode.setCellValueFactory(data -> data.getValue().codeProperty());
			colEventMessage.setCellValueFactory(data -> data.getValue().messageProperty());
		}

		if (colSourceAddr != null) {
			colSourceAddr.setCellValueFactory(data -> data.getValue().sourceProperty());
			colSourceLastSeen.setCellValueFactory(data -> data.getValue().lastSeenProperty());
			colSourceSeconds.setCellValueFactory(data -> data.getValue().secondsProperty());
			colSourceOnline.setCellValueFactory(data -> data.getValue().onlineProperty());
			colSourceAccepted.setCellValueFactory(data -> data.getValue().acceptedProperty());
			colSourceLastModule.setCellValueFactory(data -> data.getValue().lastModuleProperty());
		}

		if (colModuleId != null) {
			colModuleId.setCellValueFactory(data -> data.getValue().moduleProperty());
			colModuleLastSeen.setCellValueFactory(data -> data.getValue().lastSeenProperty());
			colModuleSeconds.setCellValueFactory(data -> data.getValue().secondsProperty());
			colModuleOnline.setCellValueFactory(data -> data.getValue().onlineProperty());
		}
	}

	private void initChart() {
		if (chartTelemetry == null) {
			return;
		}
		axisTelemetryX.setForceZeroInRange(false);
		axisTelemetryX.setAutoRanging(true);
		axisTelemetryY.setAutoRanging(true);
		chartTelemetry.setTitle("Telemetry Trends");
	}

	private void wireActions() {
		if (cmbLiveModule != null) {
			cmbLiveModule.setOnAction(event -> triggerManualRefresh());
		}
		if (cmbStatsModule != null) {
			cmbStatsModule.setOnAction(event -> triggerManualRefresh());
		}
		if (cmbStatsRange != null) {
			cmbStatsRange.setOnAction(event -> triggerManualRefresh());
		}
		if (btnRefreshEvents != null) {
			btnRefreshEvents.setOnAction(event -> triggerManualRefresh());
		}
		if (btnRefreshStats != null) {
			btnRefreshStats.setOnAction(event -> triggerManualRefresh());
		}
	}

	private void triggerManualRefresh() {
		if ("stdin".equals(INPUT_MODE)) {
			return;
		}
		Thread refreshThread = new Thread(this::refreshFromApi, "gui-manual-refresh");
		refreshThread.setDaemon(true);
		refreshThread.start();
	}

	private void startApiPolling() {
		poller = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "gui-api-poller");
			thread.setDaemon(true);
			return thread;
		});
		poller.scheduleAtFixedRate(this::refreshFromApi, 0, 1, TimeUnit.SECONDS);
	}

	private void startStdinReader() {
		Thread readThread = new Thread(() -> {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
				String line;
				while ((line = in.readLine()) != null) {
					String data = line.trim();
					if (!data.isEmpty()) {
						Platform.runLater(() -> parseAndUpdateLiveFromLine(data));
					}
				}
			} catch (IOException ex) {
				Platform.runLater(() -> lblLiveStatusLine.setText("Input stream error: " + ex.getMessage()));
			}
		}, "gui-stdin-reader");
		readThread.setDaemon(true);
		readThread.start();
	}

	private void refreshFromApi() {
		if (refreshRunning) {
			return;
		}
		refreshRunning = true;
		try {
			int liveModule = getSelectedModule(cmbLiveModule);
			int statsModule = getSelectedModule(cmbStatsModule);
			int sinceMinutes = parseRangeMinutes();

			LiveSnapshot live = parseLiveSnapshot(httpGet("/api/latest?moduleId=" + liveModule), liveModule);
			StatsSnapshot stats = parseStatsSnapshot(httpGet("/api/statistics?moduleId=" + statsModule + "&sinceMinutes=" + sinceMinutes));
			List<HistoryPoint> history = parseHistory(httpGet("/api/history?moduleId=" + statsModule + "&sinceMinutes=" + sinceMinutes + "&limit=" + HISTORY_LIMIT));
			List<EventRow> events = parseEvents(httpGet("/api/events?limit=40"));
			RpiSnapshot rpi = parseRpiSnapshot(httpGet("/api/rpi-status"));

			Platform.runLater(() -> {
				applyLive(live, liveModule);
				applyStats(stats, history, sinceMinutes);
				tblEvents.setItems(FXCollections.observableArrayList(events));
				applyRpi(rpi);
				lblConnection.setText("API OK | " + API_BASE + " | " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
			});
		} catch (Exception ex) {
			String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
			Platform.runLater(() -> lblConnection.setText("API error: " + msg));
		} finally {
			refreshRunning = false;
		}
	}

	private void parseAndUpdateLiveFromLine(String line) {
		if (!line.startsWith("BMS")) {
			lblLiveStatusLine.setText("stdin: " + line);
			return;
		}

		String[] parts = line.split(",");
		if (parts.length < 5) {
			lblLiveStatusLine.setText("Invalid BMS frame");
			return;
		}

		int moduleId = 1;
		int baseIndex = 1;
		if (parts.length >= 6 && isInteger(parts[1])) {
			int candidate = safeInt(parts[1], 1);
			if (candidate >= 1 && candidate <= 4) {
				moduleId = candidate;
				baseIndex = 2;
			}
		}

		if (moduleId != getSelectedModule(cmbLiveModule) || parts.length < baseIndex + 4) {
			return;
		}

		double voltage = safeDouble(parts[baseIndex], 0.0);
		double current = safeDouble(parts[baseIndex + 1], 0.0);
		double socRaw = safeDouble(parts[baseIndex + 2], 0.0);
		int statusCode = safeInt(parts[baseIndex + 3], 0);
		double socPercent = socRaw > 1000.0 ? socRaw / 1_000_000.0 : socRaw;

		lblVoltage.setText(String.format("%.3f V", voltage));
		lblCurrent.setText(String.format("%.3f A", current));
		lblSoc.setText(String.format("%.2f %%", socPercent));
		lblStatus.setText(statusCode + " (" + describeStatusCode(statusCode) + ")");
		lblLiveStatusLine.setText("Module " + moduleId + " | stdin | " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
	}

	private int getSelectedModule(ComboBox<String> combo) {
		if (combo == null || combo.getValue() == null) {
			return DEFAULT_MODULE_ID;
		}
		return safeInt(combo.getValue(), DEFAULT_MODULE_ID);
	}

	private int parseRangeMinutes() {
		if (cmbStatsRange == null || cmbStatsRange.getValue() == null) {
			return 60;
		}
		String text = cmbStatsRange.getValue().trim().toLowerCase();
		if (text.endsWith("h")) {
			return safeInt(text.replace("h", "").trim(), 1) * 60;
		}
		return safeInt(text.replace("min", "").trim(), 60);
	}

	private String httpGet(String endpoint) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + endpoint).openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(1500);
		connection.setReadTimeout(1500);
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();
		} finally {
			connection.disconnect();
		}
	}

	private LiveSnapshot parseLiveSnapshot(String json, int moduleId) {
		List<String> objects = extractObjectsFromArray(json);
		if (objects.isEmpty()) {
			return null;
		}
		String first = objects.get(0);
		LiveSnapshot snapshot = new LiveSnapshot();
		snapshot.moduleId = moduleId;
		snapshot.voltage = extractNumberField(first, "voltageV", 0.0);
		snapshot.current = extractNumberField(first, "currentA", 0.0);
		snapshot.soc = extractNumberField(first, "socPercent", 0.0);
		snapshot.statusCode = (int) extractNumberField(first, "statusCode", 0.0);
		snapshot.timestamp = extractStringField(first, "timestamp", "");
		return snapshot;
	}

	private StatsSnapshot parseStatsSnapshot(String json) {
		List<String> objects = extractObjectsFromArray(json);
		if (objects.isEmpty()) {
			return null;
		}
		String obj = objects.get(0);
		StatsSnapshot snapshot = new StatsSnapshot();
		snapshot.avgSoc = extractNumberField(obj, "avgSoc", 0.0);
		snapshot.minSoc = extractNumberField(obj, "minSoc", 0.0);
		snapshot.maxSoc = extractNumberField(obj, "maxSoc", 0.0);
		snapshot.minVoltage = extractNumberField(obj, "minVoltage", 0.0);
		snapshot.maxVoltage = extractNumberField(obj, "maxVoltage", 0.0);
		snapshot.minCurrent = extractNumberField(obj, "minCurrent", 0.0);
		snapshot.maxCurrent = extractNumberField(obj, "maxCurrent", 0.0);
		snapshot.points = (int) extractNumberField(obj, "sampleCount", 0.0);
		snapshot.lastStatusCode = (int) extractNumberField(obj, "lastStatusCode", 0.0);
		return snapshot;
	}

	private List<HistoryPoint> parseHistory(String json) {
		List<String> objects = extractObjectsFromArray(json);
		List<HistoryPoint> result = new ArrayList<>();
		for (String obj : objects) {
			HistoryPoint point = new HistoryPoint();
			point.voltage = extractNumberField(obj, "voltageV", 0.0);
			point.current = extractNumberField(obj, "currentA", 0.0);
			point.soc = extractNumberField(obj, "socPercent", 0.0);
			point.statusCode = extractNumberField(obj, "statusCode", 0.0);
			result.add(point);
		}
		Collections.reverse(result);
		return result;
	}

	private List<EventRow> parseEvents(String json) {
		List<String> objects = extractObjectsFromArray(json);
		List<EventRow> rows = new ArrayList<>();
		for (String obj : objects) {
			rows.add(new EventRow(
				extractStringField(obj, "timestamp", "-"),
				String.valueOf((int) extractNumberField(obj, "moduleId", 0.0)),
				extractStringField(obj, "severity", "-"),
				String.valueOf((int) extractNumberField(obj, "eventCode", 0.0)),
				extractStringField(obj, "message", "-")
			));
		}
		return rows;
	}

	private RpiSnapshot parseRpiSnapshot(String json) {
		RpiSnapshot snapshot = new RpiSnapshot();
		snapshot.overallOnline = extractBoolField(json, "overallOnline", false);
		snapshot.offlineThreshold = (int) extractNumberField(json, "offlineThresholdSec", 0.0);

		for (String obj : extractObjectsFromArray(extractArrayField(json, "sources"))) {
			snapshot.sources.add(new SourceRow(
				extractStringField(obj, "source", "-"),
				extractStringField(obj, "lastSeen", "-"),
				formatSeconds(extractNumberField(obj, "secondsSinceSeen", -1.0)),
				extractBoolField(obj, "online", false) ? "ONLINE" : "OFFLINE",
				String.valueOf((long) extractNumberField(obj, "acceptedCount", 0.0)),
				formatNullableInt(extractNumberField(obj, "lastModuleId", -1.0))
			));
		}

		for (String obj : extractObjectsFromArray(extractArrayField(json, "modules"))) {
			snapshot.modules.add(new ModuleRow(
				String.valueOf((int) extractNumberField(obj, "moduleId", 0.0)),
				extractStringField(obj, "lastSeen", "-"),
				formatSeconds(extractNumberField(obj, "secondsSinceSeen", -1.0)),
				extractBoolField(obj, "online", false) ? "ONLINE" : "OFFLINE"
			));
		}
		return snapshot;
	}

	private void applyLive(LiveSnapshot snapshot, int moduleId) {
		if (snapshot == null) {
			lblLiveStatusLine.setText("Module " + moduleId + " | waiting for API data");
			return;
		}
		lblVoltage.setText(String.format("%.3f V", snapshot.voltage));
		lblCurrent.setText(String.format("%.3f A", snapshot.current));
		lblSoc.setText(String.format("%.2f %%", snapshot.soc));
		lblStatus.setText(snapshot.statusCode + " (" + describeStatusCode(snapshot.statusCode) + ")");
		lblLiveStatusLine.setText("Module " + moduleId + " | ts=" + emptyToDash(snapshot.timestamp));
	}

	private void applyStats(StatsSnapshot stats, List<HistoryPoint> history, int sinceMinutes) {
		if (stats == null) {
			lblStatAvgSoc.setText("-");
			lblStatSocRange.setText("-");
			lblStatVoltageRange.setText("-");
			lblStatCurrentRange.setText("-");
			lblStatPoints.setText("0");
			lblStatLastStatus.setText("-");
			chartTelemetry.getData().clear();
			return;
		}

		lblStatAvgSoc.setText(String.format("%.2f %%", stats.avgSoc));
		lblStatSocRange.setText(String.format("%.2f - %.2f %%", stats.minSoc, stats.maxSoc));
		lblStatVoltageRange.setText(String.format("%.3f - %.3f V", stats.minVoltage, stats.maxVoltage));
		lblStatCurrentRange.setText(String.format("%.3f - %.3f A", stats.minCurrent, stats.maxCurrent));
		lblStatPoints.setText(String.valueOf(stats.points));
		lblStatLastStatus.setText(stats.lastStatusCode + " (" + describeStatusCode(stats.lastStatusCode) + ")");

		XYChart.Series<Number, Number> voltageSeries = new XYChart.Series<>();
		voltageSeries.setName("Voltage");
		XYChart.Series<Number, Number> currentSeries = new XYChart.Series<>();
		currentSeries.setName("Current");
		XYChart.Series<Number, Number> socSeries = new XYChart.Series<>();
		socSeries.setName("SOC %");
		XYChart.Series<Number, Number> statusSeries = new XYChart.Series<>();
		statusSeries.setName("Status");

		for (int i = 0; i < history.size(); i++) {
			HistoryPoint point = history.get(i);
			int x = i + 1;
			voltageSeries.getData().add(new XYChart.Data<>(x, point.voltage));
			currentSeries.getData().add(new XYChart.Data<>(x, point.current));
			socSeries.getData().add(new XYChart.Data<>(x, point.soc));
			statusSeries.getData().add(new XYChart.Data<>(x, point.statusCode));
		}

		chartTelemetry.setTitle("Telemetry trends (last " + sinceMinutes + " min)");
		chartTelemetry.getData().setAll(voltageSeries, currentSeries, socSeries, statusSeries);
	}

	private void applyRpi(RpiSnapshot snapshot) {
		if (snapshot == null) {
			lblRpiSummary.setText("No RPi status data.");
			tblRpiSources.setItems(FXCollections.observableArrayList());
			tblRpiModules.setItems(FXCollections.observableArrayList());
			return;
		}

		lblRpiSummary.setText(
			"Overall: " + (snapshot.overallOnline ? "ONLINE" : "OFFLINE") +
			" | Offline threshold: " + snapshot.offlineThreshold + "s"
		);
		tblRpiSources.setItems(FXCollections.observableArrayList(snapshot.sources));
		tblRpiModules.setItems(FXCollections.observableArrayList(snapshot.modules));
	}

	private static String extractArrayField(String json, String key) {
		int keyPos = json.indexOf("\"" + key + "\"");
		if (keyPos < 0) {
			return "[]";
		}
		int arrayStart = json.indexOf('[', keyPos);
		if (arrayStart < 0) {
			return "[]";
		}
		int arrayEnd = findMatchingBracket(json, arrayStart, '[', ']');
		if (arrayEnd < 0) {
			return "[]";
		}
		return json.substring(arrayStart, arrayEnd + 1);
	}

	private static List<String> extractObjectsFromArray(String jsonArray) {
		if (jsonArray == null || jsonArray.length() < 2) {
			return Collections.emptyList();
		}
		List<String> result = new ArrayList<>();
		boolean inString = false;
		int depth = 0;
		int start = -1;
		for (int i = 0; i < jsonArray.length(); i++) {
			char ch = jsonArray.charAt(i);
			char prev = i > 0 ? jsonArray.charAt(i - 1) : 0;
			if (ch == '"' && prev != '\\') {
				inString = !inString;
			}
			if (inString) {
				continue;
			}
			if (ch == '{') {
				if (depth == 0) {
					start = i;
				}
				depth++;
			} else if (ch == '}') {
				depth--;
				if (depth == 0 && start >= 0) {
					result.add(jsonArray.substring(start, i + 1));
					start = -1;
				}
			}
		}
		return result;
	}

	private static int findMatchingBracket(String text, int startIndex, char open, char close) {
		boolean inString = false;
		int depth = 0;
		for (int i = startIndex; i < text.length(); i++) {
			char ch = text.charAt(i);
			char prev = i > 0 ? text.charAt(i - 1) : 0;
			if (ch == '"' && prev != '\\') {
				inString = !inString;
			}
			if (inString) {
				continue;
			}
			if (ch == open) {
				depth++;
			} else if (ch == close) {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static String extractStringField(String objectJson, String key, String fallback) {
		Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
		Matcher m = p.matcher(objectJson);
		if (!m.find()) {
			return fallback;
		}
		return jsonUnescape(m.group(1));
	}

	private static double extractNumberField(String objectJson, String key, double fallback) {
		Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
		Matcher m = p.matcher(objectJson);
		if (!m.find()) {
			return fallback;
		}
		return safeDouble(m.group(1), fallback);
	}

	private static boolean extractBoolField(String objectJson, String key, boolean fallback) {
		Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(true|false)");
		Matcher m = p.matcher(objectJson);
		if (!m.find()) {
			return fallback;
		}
		return Boolean.parseBoolean(m.group(1));
	}

	private static String jsonUnescape(String value) {
		if (value == null) {
			return "";
		}
		return value
			.replace("\\\"", "\"")
			.replace("\\n", "\n")
			.replace("\\r", "\r")
			.replace("\\\\", "\\");
	}

	private static String formatSeconds(double secondsValue) {
		int seconds = (int) Math.round(secondsValue);
		return seconds < 0 ? "-" : String.valueOf(seconds);
	}

	private static String formatNullableInt(double value) {
		int number = (int) Math.round(value);
		return number < 0 ? "-" : String.valueOf(number);
	}

	private static String emptyToDash(String value) {
		return value == null || value.trim().isEmpty() ? "-" : value;
	}

	private static String describeStatusCode(int statusCode) {
		if (statusCode == 155) {
			return "Simulator nominal";
		}
		if (statusCode == 144) {
			return "Device reported";
		}
		return "Unknown";
	}

	private static int parseDisplayModuleId() {
		int value = safeInt(System.getenv("GUI_MODULE_ID"), 1);
		return value < 1 || value > 4 ? 1 : value;
	}

	private static String env(String key, String fallback) {
		String value = System.getenv(key);
		return value == null || value.trim().isEmpty() ? fallback : value.trim();
	}

	private static boolean isInteger(String value) {
		if (value == null || value.trim().isEmpty()) {
			return false;
		}
		try {
			Integer.parseInt(value.trim());
			return true;
		} catch (NumberFormatException ex) {
			return false;
		}
	}

	private static int safeInt(String value, int fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static double safeDouble(String value, double fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static final class LiveSnapshot {
		int moduleId;
		double voltage;
		double current;
		double soc;
		int statusCode;
		String timestamp;
	}

	private static final class StatsSnapshot {
		double avgSoc;
		double minSoc;
		double maxSoc;
		double minVoltage;
		double maxVoltage;
		double minCurrent;
		double maxCurrent;
		int points;
		int lastStatusCode;
	}

	private static final class HistoryPoint {
		double voltage;
		double current;
		double soc;
		double statusCode;
	}

	private static final class RpiSnapshot {
		boolean overallOnline;
		int offlineThreshold;
		final List<SourceRow> sources = new ArrayList<>();
		final List<ModuleRow> modules = new ArrayList<>();
	}

	public static final class EventRow {
		private final SimpleStringProperty timestamp;
		private final SimpleStringProperty module;
		private final SimpleStringProperty severity;
		private final SimpleStringProperty code;
		private final SimpleStringProperty message;

		EventRow(String timestamp, String module, String severity, String code, String message) {
			this.timestamp = new SimpleStringProperty(timestamp);
			this.module = new SimpleStringProperty(module);
			this.severity = new SimpleStringProperty(severity);
			this.code = new SimpleStringProperty(code);
			this.message = new SimpleStringProperty(message);
		}

		public SimpleStringProperty timestampProperty() { return timestamp; }
		public SimpleStringProperty moduleProperty() { return module; }
		public SimpleStringProperty severityProperty() { return severity; }
		public SimpleStringProperty codeProperty() { return code; }
		public SimpleStringProperty messageProperty() { return message; }
	}

	public static final class SourceRow {
		private final SimpleStringProperty source;
		private final SimpleStringProperty lastSeen;
		private final SimpleStringProperty seconds;
		private final SimpleStringProperty online;
		private final SimpleStringProperty accepted;
		private final SimpleStringProperty lastModule;

		SourceRow(String source, String lastSeen, String seconds, String online, String accepted, String lastModule) {
			this.source = new SimpleStringProperty(source);
			this.lastSeen = new SimpleStringProperty(lastSeen);
			this.seconds = new SimpleStringProperty(seconds);
			this.online = new SimpleStringProperty(online);
			this.accepted = new SimpleStringProperty(accepted);
			this.lastModule = new SimpleStringProperty(lastModule);
		}

		public SimpleStringProperty sourceProperty() { return source; }
		public SimpleStringProperty lastSeenProperty() { return lastSeen; }
		public SimpleStringProperty secondsProperty() { return seconds; }
		public SimpleStringProperty onlineProperty() { return online; }
		public SimpleStringProperty acceptedProperty() { return accepted; }
		public SimpleStringProperty lastModuleProperty() { return lastModule; }
	}

	public static final class ModuleRow {
		private final SimpleStringProperty module;
		private final SimpleStringProperty lastSeen;
		private final SimpleStringProperty seconds;
		private final SimpleStringProperty online;

		ModuleRow(String module, String lastSeen, String seconds, String online) {
			this.module = new SimpleStringProperty(module);
			this.lastSeen = new SimpleStringProperty(lastSeen);
			this.seconds = new SimpleStringProperty(seconds);
			this.online = new SimpleStringProperty(online);
		}

		public SimpleStringProperty moduleProperty() { return module; }
		public SimpleStringProperty lastSeenProperty() { return lastSeen; }
		public SimpleStringProperty secondsProperty() { return seconds; }
		public SimpleStringProperty onlineProperty() { return online; }
	}
}
