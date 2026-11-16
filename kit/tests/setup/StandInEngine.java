import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for the engine program, for the tests of appmod start only. It prints the settings it was started with, connects to
 * NATS_URL and prints the first line the server sends, waits a second, and ends. It is not the engine.
 */
public final class StandInEngine {

    public static void main(String[] args) throws Exception {
        System.out.println("ENGINE ALLOWED_ROOTS=" + System.getenv("SUBSCRIBER_ALLOWED_ROOTS"));
        System.out.println("ENGINE AGENT_TOOL=" + System.getenv("SUBSCRIBER_AGENT_TOOL"));
        System.out.println("ENGINE DATA_DIR=" + System.getenv("SUBSCRIBER_DATA_DIR"));
        System.out.println("ENGINE NATS_URL=" + System.getenv("NATS_URL"));
        System.out.println("ENGINE JAVA=" + System.getProperty("java.home"));
        URI uri = URI.create(System.getenv("NATS_URL"));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 2000);
            socket.setSoTimeout(2000);
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[40];
            int read = in.read(buffer);
            System.out.println("ENGINE NATS: " + (read > 0 ? new String(buffer, 0, read, StandardCharsets.UTF_8).strip() : "(nothing)"));
        } catch (Exception e) {
            System.out.println("ENGINE NATS: not reachable");
        }
        Thread.sleep(1000);
    }
}
