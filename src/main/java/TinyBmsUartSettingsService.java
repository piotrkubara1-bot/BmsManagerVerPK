import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class TinyBmsUartSettingsService implements AutoCloseable {
    private static final byte RESET_COMMAND = 0x02;
    private static final byte OPTION_CLEAR_EVENTS = 0x01;
    private static final byte OPTION_CLEAR_STATISTICS = 0x02;
    private static final byte OPTION_RESET_BMS = 0x05;
    private static final Map<String, RegisterBinding> REGISTER_MAP = new LinkedHashMap<>();

    static {
        REGISTER_MAP.put("overvoltage_protection_v", new RegisterBinding(0x012C, 1000.0));
        REGISTER_MAP.put("undervoltage_protection_v", new RegisterBinding(0x012E, 1000.0));
        REGISTER_MAP.put("charge_overcurrent_a", new RegisterBinding(0x0134, 1.0));
        REGISTER_MAP.put("discharge_overcurrent_a", new RegisterBinding(0x0135, 1.0));
        REGISTER_MAP.put("discharge_temperature_high_c", new RegisterBinding(0x0139, 1.0));
        REGISTER_MAP.put("early_balancing_threshold_v", new RegisterBinding(0x013B, 1000.0));
    }

    private final SerialPort port;
    private final InputStream input;
    private final OutputStream output;

    public TinyBmsUartSettingsService(String portName, int baudRate) throws IOException {
        if (portName == null || portName.trim().isEmpty()) {
            throw new IOException("Serial port is required.");
        }
        if ("SIMULATED".equalsIgnoreCase(portName.trim())) {
            throw new IOException("Maintenance commands are unavailable in SIMULATED mode.");
        }

        port = SerialPort.getCommPort(portName.trim());
        port.setComPortParameters(baudRate, 8, 1, 0);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 1200, 0);

        if (!port.openPort()) {
            throw new IOException("Failed to open port " + portName.trim());
        }

        input = port.getInputStream();
        output = port.getOutputStream();
    }

    public static boolean supportsKey(String key) {
        return REGISTER_MAP.containsKey(key);
    }

    public static String supportedKeysSummary() {
        return String.join(", ", REGISTER_MAP.keySet());
    }

    public void writeSetting(String key, double value) throws IOException {
        RegisterBinding binding = REGISTER_MAP.get(key);
        if (binding == null) {
            throw new IOException("Setting is not supported for UART write: " + key);
        }

        int encoded = (int) Math.round(value * binding.scaleFactor);
        byte[] command = new byte[] {
            (byte) 0xAA,
            0x0D,
            0x04,
            (byte) (binding.register & 0xFF),
            (byte) ((binding.register >> 8) & 0xFF),
            (byte) (encoded & 0xFF),
            (byte) ((encoded >> 8) & 0xFF)
        };

        sendWrite(command);
    }

    public void applySafeLiIonProfile() throws IOException {
        writeSetting("overvoltage_protection_v", 4.2);
        writeSetting("undervoltage_protection_v", 3.0);
    }

    public void resetBms() throws IOException {
        sendMaintenanceCommand(OPTION_RESET_BMS);
    }

    public void clearEvents() throws IOException {
        sendMaintenanceCommand(OPTION_CLEAR_EVENTS);
    }

    public void clearStatistics() throws IOException {
        sendMaintenanceCommand(OPTION_CLEAR_STATISTICS);
    }

    private void sendMaintenanceCommand(byte option) throws IOException {
        byte[] command = new byte[] {(byte) 0xAA, RESET_COMMAND, option};
        byte[] response = sendRequest(command, 5, 1200L);
        if (response == null || response.length < 3) {
            throw new IOException("No response from TinyBMS.");
        }
        if (response[0] != (byte) 0xAA || response[1] != 0x01 || response[2] != RESET_COMMAND) {
            throw new IOException("Unexpected TinyBMS response.");
        }
    }

    private void sendWrite(byte[] data) throws IOException {
        sendRequest(data, 128, 800L);
    }

    private byte[] sendRequest(byte[] data, int maxResponseBytes, long timeoutMillis) throws IOException {
        byte[] packet = addCrc(data);
        output.write(packet);
        output.flush();

        long deadline = System.currentTimeMillis() + timeoutMillis;
        byte[] buffer = new byte[Math.max(maxResponseBytes, 16)];
        while (System.currentTimeMillis() < deadline) {
            int available = input.available();
            if (available <= 0) {
                sleepQuietly(20L);
                continue;
            }
            int read = input.read(buffer, 0, Math.min(buffer.length, available));
            if (read > 0) {
                return Arrays.copyOf(buffer, read);
            }
        }
        return null;
    }

    private static void sleepQuietly(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("UART write interrupted.", ex);
        }
    }

    private static byte[] addCrc(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= b & 0xFF;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1;
            }
        }

        byte[] output = Arrays.copyOf(data, data.length + 2);
        output[output.length - 2] = (byte) (crc & 0xFF);
        output[output.length - 1] = (byte) ((crc >> 8) & 0xFF);
        return output;
    }

    @Override
    public void close() {
        try {
            input.close();
        } catch (Exception ignored) {
        }
        try {
            output.close();
        } catch (Exception ignored) {
        }
        try {
            if (port.isOpen()) {
                port.closePort();
            }
        } catch (Exception ignored) {
        }
    }

    private static final class RegisterBinding {
        final int register;
        final double scaleFactor;

        RegisterBinding(int register, double scaleFactor) {
            this.register = register;
            this.scaleFactor = scaleFactor;
        }
    }
}
