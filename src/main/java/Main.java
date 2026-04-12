public class Main {
    public static void main(String[] args) {
        // Force jSerialComm to re-extract the correct native library for this platform
        System.setProperty("jSerialComm.library.path",
                System.getProperty("java.io.tmpdir") + "/jSerialComm_" + System.getProperty("os.arch"));
        new BmsUartSender().start();
    }
}
