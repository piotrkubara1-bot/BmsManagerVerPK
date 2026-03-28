import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Controller {
	public static void main(String[] args) {
		Process pC = null;
		Process pApi = null;
		Process pGui = null;

		try {
			boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
			int apiPort = safeInt(System.getenv("BMS_API_PORT"), 8090);
			String fifoPath = env("BMS_FIFO_IN", "/tmp/to_java");

			if (!isWindows) {
				pC = new ProcessBuilder("./pc_interface").inheritIO().start();
			} else {
				System.out.println("[Controller] Windows mode: reading telemetry from stdin (no FIFO bridge).");
			}

			String classpath = "bin" + File.pathSeparator + "lib/*";
			pApi = new ProcessBuilder("java", "-cp", classpath, "BmsApiServer").inheritIO().start();

			List<String> guiCommand = new ArrayList<>();
			guiCommand.add("java");

			String fxPath = System.getenv("JAVAFX_PATH");
			String fxModules = env("MODULES", "javafx.controls,javafx.fxml");
			if (fxPath != null && !fxPath.trim().isEmpty()) {
				guiCommand.add("--module-path");
				guiCommand.add(fxPath.trim());
				guiCommand.add("--add-modules");
				guiCommand.add(fxModules);
			}

			guiCommand.add("-cp");
			guiCommand.add("bin");
			guiCommand.add("MainGUI");
			pGui = new ProcessBuilder(guiCommand).start();

			Process finalPC = pC;
			Process finalPApi = pApi;
			Process finalPGui = pGui;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				if (finalPC != null) {
					finalPC.destroy();
				}
				if (finalPApi != null) {
					finalPApi.destroy();
				}
				if (finalPGui != null) {
					finalPGui.destroy();
				}
				System.out.println("[Controller] Processes stopped.");
			}));

			BufferedReader reader = isWindows
				? new BufferedReader(new InputStreamReader(System.in))
				: new BufferedReader(new FileReader(fifoPath));

			PrintWriter guiIn = new PrintWriter(pGui.getOutputStream(), true);
			String line;
			System.out.println("[Controller] Forwarding loop started.");

			while ((line = reader.readLine()) != null) {
				String payload = line.trim();
				if (payload.isEmpty()) {
					continue;
				}

				guiIn.println(payload);
				guiIn.flush();

				postToApi(payload, apiPort);
				System.out.println("[Controller] forwarded: " + payload);
			}
		} catch (Exception ex) {
			System.err.println("[Controller] fatal error: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	private static void postToApi(String line, int apiPort) {
		HttpURLConnection connection = null;
		try {
			URL url = new URL("http://127.0.0.1:" + apiPort + "/api/ingest");
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(1000);
			connection.setReadTimeout(1000);
			connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");

			byte[] body = line.getBytes(StandardCharsets.UTF_8);
			try (OutputStream os = connection.getOutputStream()) {
				os.write(body);
			}
			connection.getResponseCode();
		} catch (Exception ex) {
			System.err.println("[Controller] API post failed: " + ex.getMessage());
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	private static String env(String key, String fallback) {
		String value = System.getenv(key);
		return value == null || value.trim().isEmpty() ? fallback : value.trim();
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
}
