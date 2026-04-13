import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BmsUartSender {

    private static final Path NATIVE_TMP_DIR = prepareNativeTempDirectory();

    private final String portName;
    private final int baudRate;
    private final int moduleId;
    private final String ingestUrl;
    private final int pollIntervalMs;
    private final boolean simulatorMode;
    private final Random simulatorRandom = new Random();

    private SerialPort port;
    private InputStream in;
    private OutputStream out;
    private volatile boolean connected = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public BmsUartSender(String[] args) {
        this.portName = resolvePortName(args);
        this.baudRate = Integer.parseInt(env("SERIAL_BAUD", "115200"));
        this.moduleId = Integer.parseInt(env("DEFAULT_MODULE_ID", "1"));
        this.ingestUrl = env("BMS_API_INGEST_URL", "http://127.0.0.1:8090/api/ingest");
        this.pollIntervalMs = Integer.parseInt(env("TINYBMS_POLL_INTERVAL_MS", "2000"));
        this.simulatorMode = "SIMULATED".equalsIgnoreCase(portName);
    }

    public void start() {
        System.out.println("[BmsUartSender] Starting sender...");
        System.out.println("Port: " + portName + " @ " + baudRate);
        System.out.println("Module ID: " + moduleId);
        System.out.println("Ingest URL: " + ingestUrl);
        System.out.println("Native temp dir: " + NATIVE_TMP_DIR.toAbsolutePath());
        if (simulatorMode) {
            System.out.println("[BmsUartSender] SIMULATED mode enabled.");
        }

        initPort();

        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, 10, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::pollAndSend, 1000, pollIntervalMs, TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            if (simulatorMode) {
                connected = true;
                return;
            }
            if (port == null || !port.isOpen() || !connected) {
                System.out.println("[BmsUartSender] Watchdog: Port closed or not connected, reconnecting...");
                resetConnectionState();
                initPort();
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private static Path prepareNativeTempDirectory() {
        try {
            String base = System.getProperty("user.home");
            Path tempDir = Path.of(base, ".bmsmanager", "tmp");

            Files.createDirectories(tempDir);
            Files.createDirectories(tempDir.resolve("jSerialComm"));

            // System.setProperty("jSerialComm.library.path", tempDir.resolve("jSerialComm").toAbsolutePath().toString());
            // System.out.println("[BmsUartSender] Using library path: " + System.getProperty("jSerialComm.library.path"));
            return tempDir;
        } catch (Exception e) {
            System.err.println("[BmsUartSender] Failed to prepare writable temp directory: " + e.getMessage());
            System.err.println("[BmsUartSender] jSerialComm may still fail if the default temp directory is blocked.");
            return Path.of(System.getProperty("user.home"), ".bmsmanager", "tmp");
        }
    }

    private static void cleanupOldJSerialCommFiles(Path root) {
        try {
            if (!Files.exists(root)) {
                return;
            }

            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase();
                    if (name.endsWith(".dll") || name.endsWith(".tmp") || name.endsWith(".lock")) {
                        try {
                            Files.deleteIfExists(file);
                        } catch (Exception ignored) {
                            // File may be locked by another process; ignore and continue.
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) {
                    if (!dir.equals(root)) {
                        try {
                            Files.deleteIfExists(dir);
                        } catch (Exception ignored) {
                            // Ignore directories that cannot be removed.
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
            // Cleanup is best-effort only.
        }
    }

    private void initPort() {
        try {
            if (simulatorMode) {
                resetConnectionState();
                connected = true;
                System.out.println("[BmsUartSender] Simulation source ready");
                return;
            }
            if (port != null && port.isOpen()) {
                port.closePort();
            }

            port = SerialPort.getCommPort(portName);
            port.setComPortParameters(baudRate, 8, 1, 0);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 1000, 0);

            if (port.openPort()) {
                in = port.getInputStream();
                out = port.getOutputStream();
                connected = true;
                System.out.println("[BmsUartSender] Port opened successfully");
            } else {
                resetConnectionState();
                System.err.println("[BmsUartSender] Failed to open port " + portName);
            }
        } catch (Throwable e) {
            resetConnectionState();
            System.err.println("[BmsUartSender] Error initializing port: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private void resetConnectionState() {
        connected = false;
        try {
            if (in != null) in.close();
            if (out != null) out.close();
        } catch (IOException ignored) {}
        in = null;
        out = null;
    }

    private void pollAndSend() {
        if (!connected) return;

        try {
            TinyBmsSnapshot snapshot = simulatorMode ? buildSimulatedSnapshot() : readSnapshot();
            if (snapshot != null) {
                String line = formatBmsLine(snapshot);
                sendToServer(line);
            }
        } catch (Exception e) {
            System.err.println("[BmsUartSender] Poll error: " + e.getMessage());
            resetConnectionState();
        }
    }

    private void sendHeartbeat() {
        sendToServer("HEARTBEAT," + moduleId);
    }

    private TinyBmsSnapshot buildSimulatedSnapshot() {
        float voltage = (float) (12.5 + simulatorRandom.nextDouble() * 2.2);
        float current = (float) (-8.0 + simulatorRandom.nextDouble() * 18.0);
        double socPercent = 45.0 + simulatorRandom.nextDouble() * 50.0;
        long socRaw = Math.round(socPercent * 1_000_000.0);
        int status = 155;
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            cells.add(3300 + simulatorRandom.nextInt(700));
        }
        return new TinyBmsSnapshot(voltage, current, socRaw, status, cells);
    }

    private TinyBmsSnapshot readSnapshot() throws Exception {
        Float voltage = readFloat(0x14);
        if (voltage == null) return null;

        Float current = readFloat(0x15);
        if (current == null) return null;

        Long socRaw = readUInt32(0x1A);
        if (socRaw == null) return null;

        Integer status = readUInt16(0x18);
        if (status == null) return null;

        List<Integer> cells = readCellVoltages();
        if (cells == null) return null;

        return new TinyBmsSnapshot(voltage, current, socRaw, status, cells);
    }

    private String formatBmsLine(TinyBmsSnapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("BMS,").append(moduleId).append(",");
        sb.append(String.format("%.3f", s.voltageV)).append(",");
        sb.append(String.format("%.3f", s.currentA)).append(",");
        sb.append(s.socRaw).append(",");
        sb.append(s.statusCode);

        for (int cellMv : s.cellsMv) {
            sb.append(",").append(cellMv);
        }
        return sb.toString();
    }

    private void sendToServer(String data) {
        HttpURLConnection conn = null;
        try {
            URI uri = URI.create(ingestUrl);
            URL url = uri.toURL();

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(data.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                System.err.println("[BmsUartSender] Server returned " + code + " for: " + data);
            }
        } catch (Exception ex) {
            System.err.println("[BmsUartSender] HTTP error: " + ex.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // --- UART Helpers ---

    private byte[] sendRaw(byte[] data, int expectedLen) throws IOException {
        if (!connected || port == null || in == null || out == null) return null;

        byte[] pkt = addCRC(data);
        out.write(pkt);
        out.flush();

        byte[] buf = new byte[expectedLen];
        int totalRead = 0;
        long deadline = System.currentTimeMillis() + 1000;

        while (totalRead < expectedLen && System.currentTimeMillis() < deadline) {
            int available = in.available();
            if (available > 0) {
                int read = in.read(buf, totalRead, expectedLen - totalRead);
                if (read > 0) {
                    totalRead += read;
                }
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        if (totalRead < expectedLen) {
            return null;
        }

        if (buf[0] != (byte) 0xAA) return null;

        return buf;
    }

    private Float readFloat(int cmd) throws IOException {
        byte[] r = sendRaw(new byte[]{(byte) 0xAA, (byte) cmd}, 8);
        if (r == null || r[1] != (byte) cmd) return null;
        return ByteBuffer.wrap(r, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    private Long readUInt32(int cmd) throws IOException {
        byte[] r = sendRaw(new byte[]{(byte) 0xAA, (byte) cmd}, 8);
        if (r == null || r[1] != (byte) cmd) return null;
        return (long) ByteBuffer.wrap(r, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    private Integer readUInt16(int cmd) throws IOException {
        byte[] r = sendRaw(new byte[]{(byte) 0xAA, (byte) cmd}, 6);
        if (r == null || r[1] != (byte) cmd) return null;
        return ByteBuffer.wrap(r, 2, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    private List<Integer> readCellVoltages() throws IOException {
        byte[] cmd = addCRC(new byte[]{(byte) 0xAA, 0x1C});
        if (out == null || in == null) return null;

        out.write(cmd);
        out.flush();

        byte[] header = new byte[3];
        int read = in.read(header);
        if (read < 3 || header[0] != (byte) 0xAA || header[1] != 0x1C) return null;

        int payloadLen = header[2] & 0xFF;
        byte[] body = new byte[payloadLen + 2];
        int totalBodyRead = 0;
        long deadline = System.currentTimeMillis() + 1000;

        while (totalBodyRead < body.length && System.currentTimeMillis() < deadline) {
            int r = in.read(body, totalBodyRead, body.length - totalBodyRead);
            if (r > 0) {
                totalBodyRead += r;
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        if (totalBodyRead < body.length) return null;

        int cellCount = payloadLen / 2;
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < cellCount; i++) {
            int v = ((body[i * 2 + 1] & 0xFF) << 8) | (body[i * 2] & 0xFF);
            int cellMv = (v >= 10000) ? v / 10 : v;
            cells.add(cellMv);
        }
        return cells;
    }

    private byte[] addCRC(byte[] d) {
        int crc = 0xFFFF;
        for (byte b : d) {
            crc ^= b & 0xFF;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1;
            }
        }

        byte[] o = Arrays.copyOf(d, d.length + 2);
        o[o.length - 2] = (byte) (crc & 0xFF);
        o[o.length - 1] = (byte) (crc >> 8);
        return o;
    }

    private String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String resolvePortName(String[] args) {
        String fallback = env("SERIAL_PORT", "COM5");
        if (args == null || args.length == 0) {
            return fallback;
        }

        for (String arg : args) {
            if (arg == null) {
                continue;
            }

            String trimmed = arg.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("--port=")) {
                String value = trimmed.substring("--port=".length()).trim();
                if (!value.isEmpty()) {
                    return normalizePort(value);
                }
            }

            if (!trimmed.startsWith("--")) {
                return normalizePort(trimmed);
            }
        }

        return normalizePort(fallback);
    }

    private String normalizePort(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.equalsIgnoreCase("SIM") || trimmed.equalsIgnoreCase("SIMULATED")) {
            return "SIMULATED";
        }
        return trimmed;
    }

    public static void main(String[] args) {
        new BmsUartSender(args).start();
    }

    private static class TinyBmsSnapshot {
        final float voltageV;
        final float currentA;
        final long socRaw;
        final int statusCode;
        final List<Integer> cellsMv;

        TinyBmsSnapshot(float v, float a, long soc, int status, List<Integer> cells) {
            this.voltageV = v;
            this.currentA = a;
            this.socRaw = soc;
            this.statusCode = status;
            this.cellsMv = cells;
        }
    }
}
