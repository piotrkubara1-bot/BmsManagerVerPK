import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FXMLController {
	private static final String INPUT_MODE = env("GUI_INPUT_MODE", "api").toLowerCase();
	private static final String API_BASE = env("GUI_API_BASE", "http://127.0.0.1:8090");
	private static final int DEFAULT_MODULE_ID = parseDisplayModuleId();

	@FXML
	private Label lblVoltage;

	@FXML
	private Label lblCurrent;

	@FXML
	private Label lblSoc;

	@FXML
	private Label lblStatus;

	@FXML
	private ComboBox<String> cmbModule;

	private ScheduledExecutorService poller;

	public void initialize() {
		if (cmbModule != null) {
			cmbModule.getItems().setAll("1", "2", "3", "4");
			cmbModule.setValue(String.valueOf(DEFAULT_MODULE_ID));
		}

		if ("stdin".equals(INPUT_MODE)) {
			startStdinReader();
			return;
		}

		startApiPolling();
	}

	private void startStdinReader() {
		Thread readThread = new Thread(() -> {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
				String inputLine;
				while ((inputLine = in.readLine()) != null) {
					final String data = inputLine.trim();
					if (!data.isEmpty()) {
						Platform.runLater(() -> parseAndUpdateUi(data));
					}
				}
			} catch (IOException e) {
				Platform.runLater(() -> lblStatus.setText("Input stream error: " + e.getMessage()));
			}
		}, "gui-stdin-reader");
		readThread.setDaemon(true);
		readThread.start();
	}

	private void startApiPolling() {
		poller = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "gui-api-poller");
			t.setDaemon(true);
			return t;
		});

		poller.scheduleAtFixedRate(() -> {
			try {
				int moduleId = getSelectedModuleId();
				String payload = fetchLatest(moduleId);
				if (payload == null || payload.trim().equals("[]") || payload.trim().isEmpty()) {
					Platform.runLater(() -> lblStatus.setText("Module " + moduleId + " | waiting for API data"));
					return;
				}
				Platform.runLater(() -> parseApiAndUpdateUi(payload, moduleId));
			} catch (Exception ex) {
				Platform.runLater(() -> lblStatus.setText("API error: " + ex.getMessage()));
			}
		}, 0, 1, TimeUnit.SECONDS);
	}

	private int getSelectedModuleId() {
		if (cmbModule == null || cmbModule.getValue() == null) {
			return DEFAULT_MODULE_ID;
		}
		return safeInt(cmbModule.getValue(), DEFAULT_MODULE_ID);
	}

	private String fetchLatest(int moduleId) throws IOException {
		String url = API_BASE + "/api/latest?moduleId=" + moduleId;
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
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

	private void parseApiAndUpdateUi(String json, int moduleId) {
		double voltage = extractNumber(json, "\"voltageV\":(-?\\d+(?:\\.\\d+)?)", 0.0);
		double current = extractNumber(json, "\"currentA\":(-?\\d+(?:\\.\\d+)?)", 0.0);
		double socPercent = extractNumber(json, "\"socPercent\":(-?\\d+(?:\\.\\d+)?)", 0.0);
		int statusCode = (int) extractNumber(json, "\"statusCode\":(-?\\d+)", 0.0);

		lblVoltage.setText(String.format("%.3f V", voltage));
		lblCurrent.setText(String.format("%.3f A", current));
		lblSoc.setText(String.format("%.2f %%", socPercent));

		String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		lblStatus.setText("API | Module " + moduleId + " | status=" + statusCode + " | " + now);
	}

	private double extractNumber(String json, String regex, double fallback) {
		Pattern pattern = Pattern.compile(regex);
		Matcher matcher = pattern.matcher(json);
		if (!matcher.find()) {
			return fallback;
		}
		return safeDouble(matcher.group(1), fallback);
	}

	private void parseAndUpdateUi(String line) {
		if (line.startsWith("EVENT")) {
			lblStatus.setText("Event: " + line);
			return;
		}

		if (!line.startsWith("BMS")) {
			return;
		}

		String[] parts = line.split(",");
		if (parts.length < 5) {
			lblStatus.setText("Invalid BMS frame");
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

		if (moduleId != DEFAULT_MODULE_ID || parts.length < baseIndex + 4) {
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

		String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		lblStatus.setText("Module " + moduleId + " | status=" + statusCode + " | " + now);
	}

	private static int parseDisplayModuleId() {
		String env = System.getenv("GUI_MODULE_ID");
		int value = safeInt(env, 1);
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
}
