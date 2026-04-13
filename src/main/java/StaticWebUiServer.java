import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

public class StaticWebUiServer {
    private static final int DEFAULT_PORT = 8088;
    private static final Map<String, String> STATIC_FILES = Map.of(
        "/dashboard.html", "dashboard.html",
        "/dashboard.css", "dashboard.css",
        "/dashboard.js", "dashboard.js",
        "/favicon.ico", "favicon.ico"
    );

    public static void main(String[] args) throws IOException {
        int port = parseIntEnv("WEB_UI_PORT", DEFAULT_PORT);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new StaticHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("[WebUI] Serving src\\main\\resources on http://127.0.0.1:" + port + " ...");
    }

    private static int parseIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static final class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                redirect(exchange, "/dashboard.html");
                return;
            }

            String normalizedPath = sanitizePath(path);
            String resourceName = STATIC_FILES.get(normalizedPath);
            if (resourceName == null) {
                sendText(exchange, 404, "Not found");
                return;
            }

            try (InputStream input = StaticWebUiServer.class.getClassLoader().getResourceAsStream(resourceName)) {
                if (input == null) {
                    sendText(exchange, 404, "Resource not found");
                    return;
                }

                byte[] payload = input.readAllBytes();
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", contentType(resourceName));
                headers.set("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(payload);
                }
            }
        }

        private static String sanitizePath(String path) {
            String normalized = path.replace('\\', '/');
            if (normalized.contains("..")) {
                return "";
            }
            return normalized;
        }

        private static void redirect(HttpExchange exchange, String target) throws IOException {
            exchange.getResponseHeaders().set("Location", target);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        }

        private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        }

        private static String contentType(String resourceName) {
            String detected = URLConnection.guessContentTypeFromName(resourceName);
            return detected != null ? detected : "application/octet-stream";
        }
    }
}
