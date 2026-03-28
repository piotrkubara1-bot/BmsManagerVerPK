import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Random;

public class BmsTestFeeder {
    public static void main(String[] args) {
        String mode = "single";
        int module = 1;
        int count = 120;
        int intervalMs = 1000;
        String endpoint = "http://127.0.0.1:8090/api/ingest";

        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                mode = arg.substring("--mode=".length()).trim().toLowerCase();
            } else if (arg.startsWith("--module=")) {
                module = safeInt(arg.substring("--module=".length()), 1);
            } else if (arg.startsWith("--count=")) {
                count = safeInt(arg.substring("--count=".length()), 120);
            } else if (arg.startsWith("--interval-ms=")) {
                intervalMs = safeInt(arg.substring("--interval-ms=".length()), 1000);
            } else if (arg.startsWith("--endpoint=")) {
                endpoint = arg.substring("--endpoint=".length()).trim();
            }
        }

        if (module < 1 || module > 4) {
            module = 1;
        }

        System.out.println("[BmsTestFeeder] mode=" + mode + " count=" + count + " intervalMs=" + intervalMs + " endpoint=" + endpoint);
        Random random = new Random();

        for (int i = 0; i < count; i++) {
            String payload;
            if ("single".equals(mode)) {
                payload = buildBmsLine(random, module);
            } else if ("multi".equals(mode) || "all".equals(mode)) {
                StringBuilder sb = new StringBuilder();
                for (int mod = 1; mod <= 4; mod++) {
                    if (mod > 1) {
                        sb.append('\n');
                    }
                    sb.append(buildBmsLine(random, mod));
                }
                payload = sb.toString();
            } else {
                payload = buildBmsLine(random, module);
            }

            post(endpoint, payload);
            if (i % 15 == 0) {
                String eventLine = "EVENT," + module + "," + (100 + (i % 10)) + ",INFO,simulator tick " + Instant.now();
                post(endpoint, eventLine);
            }

            try {
                Thread.sleep(Math.max(50, intervalMs));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("[BmsTestFeeder] Completed.");
    }

    private static String buildBmsLine(Random random, int module) {
        double voltage = 12.0 + (random.nextDouble() * 4.0);
        double current = -5.0 + (random.nextDouble() * 15.0);
        double soc = 40.0 + (random.nextDouble() * 58.0);
        int socRaw = (int) (soc * 1_000_000.0);
        int status = 155;

        int c1 = 3200 + random.nextInt(1000);
        int c2 = 3200 + random.nextInt(1000);
        int c3 = 3200 + random.nextInt(1000);
        int c4 = 3200 + random.nextInt(1000);

        return String.format(
            "BMS,%d,%.3f,%.3f,%d,%d,%d,%d,%d,%d",
            module,
            voltage,
            current,
            socRaw,
            status,
            c1,
            c2,
            c3,
            c4
        );
    }

    private static void post(String endpoint, String body) {
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(endpoint);
            URL url = uri.toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            connection.getResponseCode();
        } catch (Exception ex) {
            System.err.println("[BmsTestFeeder] POST failed: " + ex.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static int safeInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }
}