import java.io.FileWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Scanner;

public class DataManager {
	private static final String INGEST_URL = env("BMS_API_INGEST_URL", "http://127.0.0.1:8090/api/ingest");
	private static final String LOCAL_LOG_PATH = env("BMS_LOCAL_LOG_PATH", "");

	public static void main(String[] args) {
		Scanner scanner = new Scanner(System.in);
		System.out.println("[DataManager] stdin forwarder started...");

		while (scanner.hasNextLine()) {
			String line = scanner.nextLine().trim();
			if (line.isEmpty()) {
				continue;
			}

			logLocally(line);
			sendToServer(line);
		}
	}

	private static void logLocally(String line) {
		if (LOCAL_LOG_PATH.isEmpty()) {
			return;
		}
		try (FileWriter fw = new FileWriter(LOCAL_LOG_PATH, true);
			 PrintWriter pw = new PrintWriter(fw)) {
			pw.println(Instant.now() + "," + line);
		} catch (Exception ex) {
			System.err.println("[DataManager] local log failed: " + ex.getMessage());
		}
	}

	private static void sendToServer(String data) {
		HttpURLConnection conn = null;
		try {
			URI uri = URI.create(INGEST_URL);
			URL url = uri.toURL();

			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setConnectTimeout(1500);
			conn.setReadTimeout(1500);
			conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");

			try (OutputStream os = conn.getOutputStream()) {
				os.write(data.getBytes(StandardCharsets.UTF_8));
			}

			conn.getResponseCode();
		} catch (Exception ex) {
			System.err.println("[DataManager] HTTP error: " + ex.getMessage());
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private static String env(String key, String fallback) {
		String value = System.getenv(key);
		return value == null || value.trim().isEmpty() ? fallback : value.trim();
	}
}
